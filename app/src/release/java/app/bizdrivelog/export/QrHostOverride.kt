package app.bizdrivelog.export

import android.content.Context

/** 릴리스 빌드: QR 주소 덮어쓰기 없음 (항상 차량 Wi-Fi 주소) */
object QrHostOverride {
    class Host(val host: String, val port: Int)

    @Suppress("UNUSED_PARAMETER")
    fun get(context: Context): Host? = null
}
