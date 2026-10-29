package app.bizdrivelog.export

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 디버그 빌드 전용: QR에 찍을 주소를 맥의 LAN IP로 덮어쓴다 (에뮬레이터는 NAT라 휴대폰이 직접 못 들어옴).
 * 휴대폰 → 맥 IP:port (tools/qr_relay.py) → adb forward → 에뮬레이터 앱 서버(8765).
 */
object QrHostOverride {
    class Host(val host: String, val port: Int)

    private const val PREFS = "debug_qr_host"

    fun get(context: Context): Host? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val host = prefs.getString("host", null) ?: return null
        return Host(host, prefs.getInt("port", QrDownloadController.PORT))
    }

    internal fun set(context: Context, host: String?, port: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (host == null) clear() else putString("host", host).putInt("port", port)
        }.apply()
    }
}

/**
 * adb shell am broadcast --user 10 -n app.bizdrivelog/.export.DebugQrHostReceiver --es host <맥 LAN IP> --ei port 8765
 * adb shell am broadcast --user 10 -n app.bizdrivelog/.export.DebugQrHostReceiver --ez clear true
 */
class DebugQrHostReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val clear = intent.getBooleanExtra("clear", false)
        val host = if (clear) null else intent.getStringExtra("host")
        QrHostOverride.set(context, host, intent.getIntExtra("port", QrDownloadController.PORT))
        Log.i("BizDriveLog", "debug qr host override = ${host ?: "(cleared)"}")
    }
}
