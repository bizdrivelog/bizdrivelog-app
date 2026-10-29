package app.bizdrivelog

import android.app.Application
import app.bizdrivelog.data.AppDatabase

class BizDriveLogApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
}
