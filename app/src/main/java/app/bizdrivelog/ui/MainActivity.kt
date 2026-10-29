package app.bizdrivelog.ui

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.bizdrivelog.BizDriveLogApp
import app.bizdrivelog.data.Correction
import app.bizdrivelog.data.CorrectionField
import app.bizdrivelog.data.Place
import app.bizdrivelog.data.PlaceKind
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import app.bizdrivelog.drive.DriveRecorderService
import app.bizdrivelog.drive.DriveState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val database by lazy { (application as BizDriveLogApp).database }
    private var car: Car? = null
    private var uxRestrictionsManager: CarUxRestrictionsManager? = null
    private var fusedLocation: FusedLocation? = null

    /** 주행 규제 중이면 최소 화면만 그린다 (Pleos 주행 규제 개발 가이드: DO 선언 앱은 CarUxRestrictions를 직접 확인) */
    private var restricted by mutableStateOf(false)
    private var showSettings by mutableStateOf(false)
    private var settingsMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveRecorderService.start(this)
        connectUxRestrictions()
        requestLocationPermission()
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
            BackHandler(enabled = showSettings) { showSettings = false }
            BizDriveLogTheme {
                when {
                    restricted -> DrivingScreen(status)
                    showSettings -> SettingsScreen(
                        places = placeList,
                        message = settingsMessage,
                        onRegister = ::registerCurrentLocation,
                        onBack = { showSettings = false },
                    )
                    else -> HomeScreen(
                        status = status,
                        records = recordList,
                        actions = HomeActions(
                            classify = ::classify,
                            confirm = ::confirm,
                            correct = ::correct,
                            openSettings = { settingsMessage = null; showSettings = true },
                        ),
                    )
                }
            }
        }
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
    }

    companion object {
        private const val TAG = "BizDriveLog"
        private const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
    }
}
