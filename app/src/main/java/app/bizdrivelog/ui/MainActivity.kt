package app.bizdrivelog.ui

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bizdrivelog.BizDriveLogApp
import app.bizdrivelog.drive.DriveRecorderService
import app.bizdrivelog.drive.DriveState

class MainActivity : ComponentActivity() {

    private var car: Car? = null
    private var uxRestrictionsManager: CarUxRestrictionsManager? = null

    /** 주행 규제 중이면 최소 화면만 그린다 (Pleos 주행 규제 개발 가이드: DO 선언 앱은 CarUxRestrictions를 직접 확인) */
    private var restricted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DriveRecorderService.start(this)
        connectUxRestrictions()

        val trips = (application as BizDriveLogApp).database.tripDao().observeAll()
        setContent {
            val status by DriveState.status.collectAsStateWithLifecycle()
            val tripList by trips.collectAsStateWithLifecycle(initialValue = emptyList())
            BizDriveLogTheme {
                if (restricted) DrivingScreen(status) else HomeScreen(status, tripList)
            }
        }
    }

    override fun onDestroy() {
        runCatching { uxRestrictionsManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        super.onDestroy()
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
    }
}
