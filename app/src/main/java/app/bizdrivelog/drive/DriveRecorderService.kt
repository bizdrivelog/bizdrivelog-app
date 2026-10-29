package app.bizdrivelog.drive

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.bizdrivelog.BizDriveLogApp
import app.bizdrivelog.R
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripDao
import app.bizdrivelog.ui.MainActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 운행 자동 기록. GEAR_SELECTION이 P에서 다른 기어로 바뀌면 운행 시작, P로 돌아오면 운행 종료.
 * 시작·종료 시점의 계기판 누적거리(Odometer.getCurrentDistanceDriven, km)를 저장한다.
 * 차량 콜백은 여러 스레드에서 오므로 이벤트 채널 하나로 모아 순서대로 처리한다.
 */
class DriveRecorderService : LifecycleService() {

    private sealed interface Event {
        data class Gear(val value: Int) : Event
        data class Odometer(val km: Double) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    private lateinit var tripDao: TripDao
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

    private var lastGear: Int? = null
    private var lastOdometerKm: Double? = null

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            (value.value as? Int)?.let { events.trySend(Event.Gear(it)) }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "gear error event area=$areaId")
        }
    }

    private val distanceListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(value: Float?) {
            value?.let { events.trySend(Event.Odometer(it.toKm())) }
        }

        override fun onFailed(e: Exception) {
            Log.w(TAG, "odometer listener failed", e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        tripDao = (application as BizDriveLogApp).database.tripDao()
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        lifecycleScope.launch { for (event in events) handle(event) }
        lifecycleScope.launch { connect() }
    }

    override fun onDestroy() {
        runCatching { vehicle?.getOdometer()?.unregisterDistanceDriven(distanceListener) }
        runCatching { propertyManager?.unregisterCallback(gearCallback) }
        runCatching { vehicle?.release() }
        runCatching { car?.disconnect() }
        events.close()
        super.onDestroy()
    }

    private suspend fun connect() {
        vehicle = Vehicle(applicationContext).also { it.initialize() }
        // Vehicle.initialize()는 비동기 연결이라 첫 조회가 성공할 때까지 재시도한 뒤 구독한다
        for (attempt in 1..30) {
            val km = readOdometer()
            if (km != null) {
                events.send(Event.Odometer(km))
                break
            }
            delay(1000)
        }
        vehicle?.getOdometer()?.registerDistanceDriven(distanceListener)

        car = Car.createCar(applicationContext)
        propertyManager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        val manager = propertyManager ?: run { Log.e(TAG, "CarPropertyManager unavailable"); return }
        runCatching { manager.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0)?.value }
            .onSuccess { gear -> gear?.let { events.send(Event.Gear(it)) } }
            .onFailure { Log.e(TAG, "gear read failed", it) }
        manager.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Odometer -> {
                // registerDistanceDriven은 값이 그대로여도 2~4초마다 다시 보낸다
                if (event.km == lastOdometerKm) return
                lastOdometerKm = event.km
                DriveState.update { it.copy(odometerKm = event.km) }
                Log.i(TAG, "odometer ${event.km} km")
            }
            is Event.Gear -> {
                if (event.value == lastGear) return
                val previous = lastGear
                lastGear = event.value
                DriveState.update { it.copy(gear = event.value) }
                Log.i(TAG, "gear $previous -> ${event.value}")
                onGearChanged(previous, event.value)
            }
        }
    }

    private suspend fun onGearChanged(previous: Int?, gear: Int) {
        val open = tripDao.findOpen()
        val moving = gear != VehicleGear.GEAR_PARK && gear != VehicleGear.GEAR_UNKNOWN
        when {
            // previous == null: 서비스가 주행 중에 시작된 경우도 운행으로 잡는다
            moving && open == null && (previous == null || previous == VehicleGear.GEAR_PARK) -> {
                val odometer = currentOdometer() ?: run { Log.e(TAG, "trip start skipped: no odometer"); return }
                val trip = Trip(startedAt = System.currentTimeMillis(), startOdometerKm = odometer)
                tripDao.insert(trip)
                DriveState.update { it.copy(odometerKm = odometer, tripStartOdometerKm = odometer) }
                Log.i(TAG, "trip started at $odometer km")
            }
            moving && open != null -> DriveState.update { it.copy(tripStartOdometerKm = open.startOdometerKm) }
            gear == VehicleGear.GEAR_PARK && open != null -> {
                val odometer = currentOdometer() ?: open.startOdometerKm
                tripDao.update(open.copy(endedAt = System.currentTimeMillis(), endOdometerKm = odometer))
                DriveState.update { it.copy(odometerKm = odometer, tripStartOdometerKm = null) }
                Log.i(TAG, "trip ended at $odometer km (${odometer - open.startOdometerKm} km)")
            }
        }
    }

    /** 시작·종료 시점 값은 구독 지연(최대 수 초)을 피하려고 다시 조회하고, 실패하면 마지막 수신값을 쓴다. */
    private suspend fun currentOdometer(): Double? =
        readOdometer()?.also { lastOdometerKm = it } ?: lastOdometerKm

    private suspend fun readOdometer(): Double? = withTimeoutOrNull(3000) {
        suspendCancellableCoroutine { cont ->
            val odometer = vehicle?.getOdometer() ?: return@suspendCancellableCoroutine cont.resume(null)
            runCatching {
                odometer.getCurrentDistanceDriven(
                    { value -> if (cont.isActive) cont.resume(value?.toKm()) },
                    { e -> Log.w(TAG, "odometer read failed: ${e.message}"); if (cont.isActive) cont.resume(null) },
                )
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("운행 자동 기록 중")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "BizDriveLog"
        private const val CHANNEL_ID = "drive_recorder"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DriveRecorderService::class.java))
        }

        /** Float 그대로 Double로 바꾸면 12346.1f → 12346.099609375가 되므로 문자열을 거친다 */
        private fun Float.toKm(): Double = toString().toDouble()
    }
}
