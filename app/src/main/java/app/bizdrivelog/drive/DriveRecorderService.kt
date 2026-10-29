package app.bizdrivelog.drive

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
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
import android.location.Location
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.bizdrivelog.BizDriveLogApp
import app.bizdrivelog.R
import app.bizdrivelog.data.Place
import app.bizdrivelog.data.PlaceDao
import app.bizdrivelog.data.PlaceKind
import app.bizdrivelog.data.ProfileStore
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripDao
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.ui.MainActivity
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 운행 자동 기록. GEAR_SELECTION이 P에서 다른 기어로 바뀌면 운행 시작, P로 돌아오면 운행 종료.
 * 시작·종료 시점의 계기판 누적거리(Odometer.getCurrentDistanceDriven, km)와 위치를 저장하고,
 * 종료 시 자택·회사 영역(FusedLocation addProximityAlert)과 요일로 3모드를 제안한다.
 * 차량·위치 콜백은 여러 스레드에서 오므로 이벤트 채널 하나로 모아 순서대로 처리한다.
 */
class DriveRecorderService : LifecycleService() {

    private sealed interface Event {
        data class Gear(val value: Int) : Event
        data class Odometer(val km: Double) : Event
        data class Places(val places: List<Place>) : Event
        data class FenceRegistered(val alertId: String, val kind: PlaceKind) : Event
        data class Fence(val kind: PlaceKind, val inside: Boolean) : Event
    }

    private val events = Channel<Event>(Channel.UNLIMITED)

    private lateinit var tripDao: TripDao
    private lateinit var placeDao: PlaceDao
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var fusedLocation: FusedLocation? = null

    private var lastGear: Int? = null
    private var lastOdometerKm: Double? = null

    private var places: List<Place> = emptyList()
    private val alertIds = mutableMapOf<PlaceKind, String>()
    /** addProximityAlert ENTER/EXIT로 받은 현재 영역 상태 */
    private val insideByAlert = mutableMapOf<PlaceKind, Boolean>()

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
        val database = (application as BizDriveLogApp).database
        tripDao = database.tripDao()
        placeDao = database.placeDao()
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        fusedLocation = runCatching { FusedLocation(applicationContext).also { it.initialize() } }
            .onFailure { Log.e(TAG, "FusedLocation unavailable", it) }
            .getOrNull()

        lifecycleScope.launch { for (event in events) handle(event) }
        lifecycleScope.launch { placeDao.observeAll().collect { events.send(Event.Places(it)) } }
        lifecycleScope.launch { connect() }
    }

    override fun onDestroy() {
        runCatching { vehicle?.getOdometer()?.unregisterDistanceDriven(distanceListener) }
        runCatching { propertyManager?.unregisterCallback(gearCallback) }
        alertIds.values.forEach { id -> runCatching { fusedLocation?.removeProximityAlert(id) } }
        runCatching { fusedLocation?.release() }
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
            is Event.Places -> registerFences(event.places)
            is Event.FenceRegistered -> {
                alertIds[event.kind] = event.alertId
                Log.i(TAG, "fence registered ${event.kind} id=${event.alertId}")
            }
            is Event.Fence -> {
                insideByAlert[event.kind] = event.inside
                Log.i(TAG, "fence ${event.kind} ${if (event.inside) "ENTER" else "EXIT"}")
            }
        }
    }

    private fun registerFences(newPlaces: List<Place>) {
        places = newPlaces
        val fused = fusedLocation ?: return
        alertIds.values.forEach { id -> runCatching { fused.removeProximityAlert(id) } }
        alertIds.clear()
        insideByAlert.clear()
        newPlaces.forEach { place ->
            val request = ProximityAlertRequest(
                place.lat, place.lng, place.radiusMeters, setOf(ProximityTransition.ENTER, ProximityTransition.EXIT),
            )
            runCatching {
                fused.addProximityAlert(request, object : ProximityAlertListener {
                    override fun onRegistered(alertId: String) {
                        events.trySend(Event.FenceRegistered(alertId, place.kind))
                    }

                    override fun onTransition(event: ProximityAlertEvent) {
                        events.trySend(Event.Fence(place.kind, event.transition == ProximityTransition.ENTER))
                    }

                    override fun onError(exception: Exception) {
                        Log.w(TAG, "fence ${place.kind} error: ${exception.message}")
                    }
                })
            }.onFailure { Log.e(TAG, "addProximityAlert failed", it) }
        }
    }

    private suspend fun onGearChanged(previous: Int?, gear: Int) {
        val open = tripDao.findOpen()
        val moving = gear != VehicleGear.GEAR_PARK && gear != VehicleGear.GEAR_UNKNOWN
        when {
            // previous == null: 서비스가 주행 중에 시작된 경우도 운행으로 잡는다
            moving && open == null && (previous == null || previous == VehicleGear.GEAR_PARK) -> startTrip()
            moving && open != null -> DriveState.update { it.copy(tripStartOdometerKm = open.startOdometerKm) }
            gear == VehicleGear.GEAR_PARK && open != null -> endTrip(open)
        }
    }

    private suspend fun startTrip() {
        val odometer = currentOdometer() ?: run { Log.e(TAG, "trip start skipped: no odometer"); return }
        val location = readLocation()
        val profile = ProfileStore(this).load()
        // 시작 좌표는 종료 시 분류에 필요해서 일단 저장하고, 개인 운행으로 끝나면 지운다
        val trip = Trip(
            startedAt = System.currentTimeMillis(),
            startOdometerKm = odometer,
            startLat = location?.latitude,
            startLng = location?.longitude,
            fromPlace = placeAt(location, "start"),
            userDept = profile.dept,
            userName = profile.userName,
        )
        tripDao.insert(trip)
        DriveState.update { it.copy(odometerKm = odometer, tripStartOdometerKm = odometer) }
        Log.i(TAG, "trip started at $odometer km from=${trip.fromPlace}")
    }

    private suspend fun endTrip(open: Trip) {
        val odometer = currentOdometer() ?: open.startOdometerKm
        val location = readLocation()
        val to = placeAt(location, "end")
        val suggestion = TripClassifier.suggest(open.startedAt, open.fromPlace, to)
        val personal = suggestion.mode == TripMode.PERSONAL
        tripDao.update(
            open.copy(
                endedAt = System.currentTimeMillis(),
                endOdometerKm = odometer,
                startLat = open.startLat.takeUnless { personal },
                startLng = open.startLng.takeUnless { personal },
                endLat = location?.latitude.takeUnless { personal },
                endLng = location?.longitude.takeUnless { personal },
                fromPlace = open.fromPlace.takeUnless { personal },
                toPlace = to.takeUnless { personal },
                mode = suggestion.mode,
                modeBy = suggestion.reason,
                purpose = suggestion.purpose,
            )
        )
        DriveState.update { it.copy(odometerKm = odometer, tripStartOdometerKm = null) }
        Log.i(TAG, "trip ended at $odometer km (${odometer - open.startOdometerKm} km) ${open.fromPlace}->$to suggest=${suggestion.mode} (${suggestion.reason})")
    }

    /** 영역 알림(ENTER/EXIT) 상태를 우선 쓰고, 알림을 아직 못 받았으면 좌표와 반경으로 판단한다. */
    private fun placeAt(location: LocationData?, label: String): PlaceKind? {
        val byAlert = insideByAlert.filterValues { it }.keys.firstOrNull()
        val byDistance = location?.let { loc ->
            places.firstOrNull { place ->
                val result = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, place.lat, place.lng, result)
                result[0] <= place.radiusMeters
            }?.kind
        }
        Log.i(TAG, "place@$label alert=$byAlert distance=$byDistance alerts=$insideByAlert loc=${location?.latitude},${location?.longitude}")
        return byAlert ?: byDistance
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

    private suspend fun readLocation(): LocationData? = withTimeoutOrNull(3000) {
        suspendCancellableCoroutine { cont ->
            val fused = fusedLocation ?: return@suspendCancellableCoroutine cont.resume(null)
            runCatching {
                fused.getLastKnownLocation(object : FusedLocationListener {
                    override fun onChanged(location: LocationData?) {
                        if (cont.isActive) cont.resume(location)
                    }

                    override fun onError(exception: Exception) {
                        Log.w(TAG, "location read failed: ${exception.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                })
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
