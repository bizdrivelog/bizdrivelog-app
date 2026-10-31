package app.bizdrivelog.drive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 둘 다 백그라운드 포그라운드 서비스 시작이 허용되는 예외 (앱 업데이트 뒤에도 기록을 이어간다)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { DriveRecorderService.start(context) }.onFailure { Log.w("BizDriveLog", "service start refused: ${it.message}") }
        }
    }
}
