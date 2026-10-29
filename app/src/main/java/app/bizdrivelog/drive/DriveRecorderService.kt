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
import app.bizdrivelog.voice.VoiceAnswer
import app.bizdrivelog.voice.VoiceGuide
import app.bizdrivelog.voice.VoiceSettings
import kotlinx.coroutines.Job
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
 * 음성(설정에서 끌 수 있음): 운행 시작 시 짧은 안내만, 종료(P) 후 분류·목적을 묻고 답을 분류 확인 카드에 채운다.
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
    private val voiceSettings by lazy { VoiceSettings(this) }
    private var voice: VoiceGuide? = null
    private var voiceJob: Job? = null

    private var lastGear: Int? = null
    private var lastOdometerKm: Double? = null
    /** 진행 중 운행의 출발 위치 (메모리 전용) */
    private var tripStart: TripEnd.StartPoint? = null

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_ASK -> intent.getLongExtra(EXTRA_TRIP_ID, -1).takeIf { it > 0 }?.let { id ->
                lifecycleScope.launch { tripDao.find(id)?.let { askAbout(it.id, it.mode) } }
            }
            ACTION_STOP_VOICE -> stopVoice()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVoice()
        runCatching { voice?.release() }
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
        // 출발 위치는 종료 시 분류에만 필요하므로 DB에 쓰지 않고 메모리에만 둔다 (개인 운행이면 끝까지 저장 안 됨)
        val trip = Trip(
            startedAt = System.currentTimeMillis(),
            startOdometerKm = odometer,
            userDept = profile.dept,
            userName = profile.userName,
        )
        val id = tripDao.insert(trip)
        tripStart = TripEnd.StartPoint(id, location?.latitude, location?.longitude, placeAt(location, "start"))
        DriveState.update { it.copy(odometerKm = odometer, tripStartOdometerKm = odometer) }
        Log.i(TAG, "trip started at $odometer km from=${tripStart?.place} (start point in memory only)")
        // 주행 중에는 화면 입력 없이 이 한 마디만
        stopVoice()
        if (voiceSettings.enabled) voiceJob = lifecycleScope.launch { voice().speak("운행 기록을 시작합니다") }
    }

    private suspend fun endTrip(open: Trip) {
        val odometer = currentOdometer() ?: open.startOdometerKm
        val location = readLocation()
        val to = placeAt(location, "end")
        val start = tripStart
        tripStart = null
        val (closed, suggestion, preset) = TripEnd.close(open, start, System.currentTimeMillis(), odometer, location?.latitude, location?.longitude, to)
        tripDao.update(closed)
        DriveState.update { it.copy(odometerKm = odometer, tripStartOdometerKm = null) }
        Log.i(TAG, "trip ended at $odometer km (${odometer - open.startOdometerKm} km) ${closed.fromPlace}->${closed.toPlace} start=${if (start?.tripId == open.id) "memory" else "missing"} suggest=${suggestion.mode} (${suggestion.reason})")
        if (!preset) askAbout(open.id, suggestion.mode)
    }

    /** 정차 후 음성 질문. 인식 실패·무응답이면 아무것도 하지 않고 화면 카드 입력으로 넘어간다. */
    private fun askAbout(tripId: Long, suggested: TripMode?) {
        if (!voiceSettings.enabled) return
        stopVoice()
        voiceJob = lifecycleScope.launch {
            val guide = voice()
            val text = guide.ask(question(suggested)) ?: return@launch
            val answer = VoiceAnswer.parse(text, suggested)
            val trip = tripDao.find(tripId)
            Log.i(TAG, "voice answer trip=$tripId \"$text\" -> $answer")
            if (answer?.mode == null || trip == null || trip.isConfirmed) return@launch
            val purpose = answer.purpose.ifEmpty { if (answer.mode == trip.mode) trip.purpose else "" }
            tripDao.classify(tripId, answer.mode, "음성 응답 “$text”", purpose, answer.visitPlace)
            guide.speak("입력했습니다. " + listOf(answer.mode.label, purpose).filter { it.isNotEmpty() }.joinToString(", ") + ". 화면에서 확정해 주세요")
        }
    }

    private fun question(suggested: TripMode?): String = when (suggested) {
        TripMode.COMMUTE -> "이번 운행은 출퇴근으로 기록할까요? 다르면 말씀해 주세요"
        TripMode.PERSONAL -> "이번 운행은 개인 운행으로 기록할까요? 업무였다면 목적을 말씀해 주세요"
        else -> "이번 운행은 업무로 기록할까요? 목적을 말씀해 주세요"
    }

    private fun voice(): VoiceGuide = voice ?: VoiceGuide(this).also { voice = it }

    private fun stopVoice() {
        voiceJob?.cancel()
        voiceJob = null
        voice?.stop()
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

        private const val ACTION_ASK = "app.bizdrivelog.action.ASK_VOICE"
        private const val ACTION_STOP_VOICE = "app.bizdrivelog.action.STOP_VOICE"
        private const val EXTRA_TRIP_ID = "tripId"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DriveRecorderService::class.java))
        }

        /** 분류 확인 카드의 "음성으로 답하기" */
        fun ask(context: Context, tripId: Long) {
            context.startService(Intent(context, DriveRecorderService::class.java).setAction(ACTION_ASK).putExtra(EXTRA_TRIP_ID, tripId))
        }

        fun stopVoice(context: Context) {
            context.startService(Intent(context, DriveRecorderService::class.java).setAction(ACTION_STOP_VOICE))
        }

        /** Float 그대로 Double로 바꾸면 12346.1f → 12346.099609375가 되므로 문자열을 거친다 */
        private fun Float.toKm(): Double = toString().toDouble()
    }
}
