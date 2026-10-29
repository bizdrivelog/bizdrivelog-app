package app.bizdrivelog.ui

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.bizdrivelog.BizDriveLogApp
import app.bizdrivelog.data.Correction
import app.bizdrivelog.data.CorrectionField
import app.bizdrivelog.data.LedgerProfile
import app.bizdrivelog.data.Place
import app.bizdrivelog.data.PlaceKind
import app.bizdrivelog.data.ProfileStore
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import app.bizdrivelog.drive.DriveRecorderService
import app.bizdrivelog.drive.DriveState
import app.bizdrivelog.export.Ledger
import app.bizdrivelog.export.LedgerExporter
import app.bizdrivelog.export.QrDownloadController
import app.bizdrivelog.export.QrState
import app.bizdrivelog.export.LedgerPeriod
import app.bizdrivelog.voice.Gleo
import app.bizdrivelog.voice.VoiceSettings
import app.bizdrivelog.voice.VoiceState
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val database by lazy { (application as BizDriveLogApp).database }
    private var car: Car? = null
    private var uxRestrictionsManager: CarUxRestrictionsManager? = null
    private var fusedLocation: FusedLocation? = null

    /** 주행 규제 중이면 최소 화면만 그린다 (Pleos 주행 규제 개발 가이드: DO 선언 앱은 CarUxRestrictions를 직접 확인) */
    private var restricted by mutableStateOf(false)
    private enum class Screen { HOME, SETTINGS, EXPORT }

    private var screen by mutableStateOf(Screen.HOME)
    private var settingsMessage by mutableStateOf<String?>(null)
    private val profileStore by lazy { ProfileStore(this) }
    private var profile by mutableStateOf(LedgerProfile())
    private var exportState by mutableStateOf(ExportState())
    private var qrState by mutableStateOf<QrState>(QrState.Idle)
    private var canShare by mutableStateOf(false)
    private val qrController by lazy { QrDownloadController(applicationContext) { qrState = it } }
    private val voiceSettings by lazy { VoiceSettings(this) }
    private var voiceEnabled by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveRecorderService.start(this)
        connectUxRestrictions()
        requestLocationPermission()
        profile = profileStore.load()
        voiceEnabled = voiceSettings.enabled
        fusedLocation = runCatching { FusedLocation(applicationContext).also { it.initialize() } }.getOrNull()

        val tripDao = database.tripDao()
        val records = combine(tripDao.observeAll(), tripDao.observeCorrections()) { trips, corrections ->
            val byTrip = corrections.groupBy { it.tripId }
            trips.map { TripRecord(it, byTrip[it.id].orEmpty()) }
        }
        val places = database.placeDao().observeAll()

        setContent {
            val status by DriveState.status.collectAsStateWithLifecycle()
            val recordList by records.collectAsStateWithLifecycle(initialValue = emptyList())
            val placeList by places.collectAsStateWithLifecycle(initialValue = emptyList())
            val voicePhase by VoiceState.phase.collectAsStateWithLifecycle()
            BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
            BizDriveLogTheme {
                when {
                    restricted -> DrivingScreen(status)
                    screen == Screen.SETTINGS -> SettingsScreen(
                        places = placeList,
                        message = settingsMessage,
                        profile = profile,
                        voiceEnabled = voiceEnabled,
                        onVoiceEnabled = ::toggleVoice,
                        onRegister = ::registerCurrentLocation,
                        onSaveProfile = { profileStore.save(it); profile = profileStore.load(); settingsMessage = "머리 정보를 저장했습니다" },
                        onBack = { screen = Screen.HOME },
                    )
                    screen == Screen.EXPORT -> ExportScreen(
                        records = recordList,
                        profile = profile,
                        state = exportState,
                        qrState = qrState,
                        canShare = canShare,
                        onExport = ::export,
                        onShare = ::share,
                        onStartQr = ::startQr,
                        onCloseQr = { qrController.stop(QrState.Idle); qrState = QrState.Idle },
                        onBack = { screen = Screen.HOME },
                    )
                    else -> HomeScreen(
                        status = status,
                        records = recordList,
                        voice = if (voiceEnabled) voicePhase else null,
                        actions = HomeActions(
                            classify = ::classify,
                            confirm = ::confirm,
                            correct = ::correct,
                            askVoice = { DriveRecorderService.ask(this, it.id) },
                            openSettings = { settingsMessage = null; screen = Screen.SETTINGS },
                            openExport = {
                                exportState = ExportState()
                                canShare = shareHandlers(Intent(Intent.ACTION_SEND).setType(XLSX_MIME)) > 0
                                screen = Screen.EXPORT
                            },
                        ),
                    )
                }
            }
        }
        handleGleo(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleGleo(intent)
    }

    /** 화면이 가려지거나 꺼지면 QR 서버도 닫는다 */
    override fun onStop() {
        qrController.stop(QrState.Idle)
        qrState = QrState.Idle
        super.onStop()
    }

    override fun onDestroy() {
        runCatching { uxRestrictionsManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        runCatching { fusedLocation?.release() }
        super.onDestroy()
    }

    private fun classify(trip: Trip, mode: TripMode, modeBy: String, purpose: String, visitPlace: String) = dbAction {
        database.tripDao().classify(trip.id, mode, modeBy, purpose.trim(), visitPlace.trim())
    }

    private fun confirm(trip: Trip) = dbAction {
        database.tripDao().confirm(trip.id, System.currentTimeMillis())
        Log.i(TAG, "trip ${trip.id} confirmed")
    }

    private fun correct(record: TripRecord, mode: TripMode, purpose: String, visitPlace: String, reason: String) = dbAction {
        val now = System.currentTimeMillis()
        val changes = listOfNotNull(
            Triple(CorrectionField.MODE, record.effectiveMode?.name.orEmpty(), mode.name),
            Triple(CorrectionField.PURPOSE, record.effectivePurpose, purpose.trim()),
            Triple(CorrectionField.VISIT_PLACE, record.effectiveVisitPlace, visitPlace.trim()),
        ).filter { (_, before, after) -> before != after }
        if (changes.isEmpty() || reason.isBlank() || !record.trip.isConfirmed) return@dbAction
        database.tripDao().insertCorrections(
            changes.map { (field, before, after) ->
                Correction(tripId = record.trip.id, field = field, before = before, after = after, reason = reason.trim(), createdAt = now)
            }
        )
        Log.i(TAG, "trip ${record.trip.id} corrected: ${changes.map { it.first }} reason=$reason")
    }

    /** 앱 전용 저장소(files/exports)에 xlsx를 만든다 */
    private fun export(ledger: Ledger) {
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { LedgerExporter.write(ledger, File(filesDir, "exports")) } }
                .onSuccess { file ->
                    exportState = ExportState(file = file)
                    Log.i(TAG, "exported ${file.absolutePath} rows=${ledger.rows.size} total=${ledger.totals.totalKm} biz=${ledger.totals.businessUseKm} ratio=${ledger.totals.ratioPercent}")
                }
                .onFailure {
                    Log.e(TAG, "export failed", it)
                    exportState = ExportState(message = "엑셀 파일을 만들지 못했습니다 (${it.message})")
                }
        }
    }

    /** 안드로이드 공유(ACTION_SEND). 받을 앱이 없으면 그 사실만 알린다 */
    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(XLSX_MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (shareHandlers(send) == 0) {
            exportState = exportState.copy(message = "이 차량에는 파일을 받을 앱이 없습니다 (ACTION_SEND 처리 앱 0개)")
            return
        }
        runCatching { startActivity(Intent.createChooser(send, "운행기록부 보내기")) }
            .onFailure { exportState = exportState.copy(message = "보내기 화면을 열지 못했습니다 (${it.message})") }
    }

    private fun shareHandlers(intent: Intent): Int =
        packageManager.queryIntentActivities(intent, 0).size.also { Log.i(TAG, "share handlers=$it") }

    /** 휴대폰으로 받기: 주행 중에는 비활성 */
    private fun startQr(file: File) {
        if (restricted) return
        qrController.start(file, file.name)
    }

    private fun toggleVoice(enabled: Boolean) {
        voiceSettings.enabled = enabled
        voiceEnabled = enabled
        if (!enabled) DriveRecorderService.stopVoice(this)
    }

    /** Gleo SHOW_DOWNLOAD_QR (component = "activity"): 엑셀을 만들고 QR을 띄운 뒤 결과를 Gleo에 돌려준다 */
    private fun handleGleo(intent: Intent?) {
        if (intent?.action != Gleo.SHOW_DOWNLOAD_QR) return
        setIntent(Intent(this, MainActivity::class.java))
        val params = Gleo.params(intent)
        Log.i(TAG, "gleo action ${Gleo.SHOW_DOWNLOAD_QR} params=$params")
        if (restricted) {
            Gleo.respond(this, Gleo.SHOW_DOWNLOAD_QR, Gleo.Result(Gleo.Status.FAIL, "The QR code cannot be shown while driving. Please try again after parking."))
            return
        }
        lifecycleScope.launch {
            val result = runCatching {
                withTimeoutOrNull(Gleo.DEADLINE_MS) {
                    val dao = database.tripDao()
                    val corrections = dao.getCorrections().groupBy { it.tripId }
                    val records = dao.getAll().map { TripRecord(it, corrections[it.id].orEmpty()) }
                    val ledger = Ledger.build(records, profile, LedgerPeriod.of(Gleo.periodKind(params)))
                    val file = withContext(Dispatchers.IO) { LedgerExporter.write(ledger, File(filesDir, "exports")) }
                    exportState = ExportState(file = file)
                    canShare = shareHandlers(Intent(Intent.ACTION_SEND).setType(XLSX_MIME)) > 0
                    screen = Screen.EXPORT
                    qrController.start(file, file.name)
                    when (qrState) {
                        is QrState.Waiting -> Gleo.Result(Gleo.Status.SUCCESS, "The QR code is on the screen. Scan it with your phone camera within five minutes.")
                        QrState.NoWifi -> Gleo.Result(Gleo.Status.FAIL, "The vehicle is not connected to Wi-Fi. Connect it to your phone's hotspot and try again.")
                        else -> Gleo.Result(Gleo.Status.FAIL, "The download server could not be started.")
                    }
                } ?: Gleo.Result(Gleo.Status.FAIL, "A timeout occurred while processing the request.")
            }.getOrElse {
                Log.e(TAG, "gleo qr failed", it)
                Gleo.Result(Gleo.Status.FAIL, "An error occurred while preparing the logbook file.")
            }
            Gleo.respond(this@MainActivity, Gleo.SHOW_DOWNLOAD_QR, result)
        }
    }

    private fun registerCurrentLocation(kind: PlaceKind) {
        val fused = fusedLocation ?: run { settingsMessage = "위치 서비스를 사용할 수 없습니다"; return }
        runCatching {
            fused.getLastKnownLocation(object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    if (location == null) {
                        settingsMessage = "현재 위치를 가져오지 못했습니다"
                        return
                    }
                    dbAction {
                        database.placeDao().upsert(
                            Place(kind = kind, lat = location.latitude, lng = location.longitude, registeredAt = System.currentTimeMillis())
                        )
                        settingsMessage = "${kind.label}을(를) 현재 위치로 등록했습니다"
                        Log.i(TAG, "place $kind = ${location.latitude},${location.longitude}")
                    }
                }

                override fun onError(exception: Exception) {
                    settingsMessage = "현재 위치를 가져오지 못했습니다 (${exception.message})"
                }
            })
        }.onFailure { settingsMessage = "현재 위치를 가져오지 못했습니다 (${it.message})" }
    }

    private fun dbAction(block: suspend () -> Unit) {
        lifecycleScope.launch { runCatching { block() }.onFailure { Log.e(TAG, "db action failed", it) } }
    }

    private fun requestLocationPermission() {
        if (checkSelfPermission(FUSED_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(FUSED_LOCATION), 1)
        }
    }

    private fun connectUxRestrictions() {
        runCatching {
            car = Car.createCar(applicationContext)
            val manager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            uxRestrictionsManager = manager
            manager?.currentCarUxRestrictions?.let(::apply)
            manager?.registerListener(::apply)
        }.onFailure { Log.e(TAG, "CarUxRestrictionsManager unavailable", it) }
    }

    private fun apply(restrictions: CarUxRestrictions) {
        Log.i(TAG, "uxRestrictions requiresDO=${restrictions.isRequiresDistractionOptimization} active=${restrictions.activeRestrictions}")
        restricted = restrictions.isRequiresDistractionOptimization
        if (restricted) qrController.stop(QrState.Idle)
    }

    companion object {
        private const val TAG = "BizDriveLog"
        private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
    }
}
