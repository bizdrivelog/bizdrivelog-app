package app.bizdrivelog.export

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import app.bizdrivelog.BuildConfig
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

sealed interface QrState {
    data object Idle : QrState
    /** 차량이 Wi-Fi(휴대폰 핫스팟)에 연결되어 있지 않음 */
    data object NoWifi : QrState
    data class Waiting(val url: String, val address: String, val expiresAt: Long, val debugOverride: Boolean) : QrState
    data object Downloaded : QrState
    data object Expired : QrState
    data class Failed(val message: String) : QrState
}

/**
 * 휴대폰으로 받기. QR 화면이 떠 있는 동안만 앱 안에 HTTP 서버를 띄우고, 추측 불가능한 일회용 토큰 URL을 QR로 보여 준다.
 * 다운로드가 한 번 끝나거나 5분이 지나면 서버를 바로 종료한다. 외부 서버를 쓰지 않는다.
 */
class QrDownloadController(private val context: Context, private val onState: (QrState) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var server: OneTimeFileServer? = null
    private val expire = Runnable { stop(QrState.Expired) }

    fun start(file: File, downloadName: String) {
        stop(QrState.Idle)
        val override = QrHostOverride.get(context)
        // 릴리스: 차량 Wi-Fi 주소에만 바인딩. 디버그 덮어쓰기(에뮬레이터 + adb forward)일 때만 전체 바인딩
        val host = override?.host ?: wifiIpv4() ?: return onState(QrState.NoWifi)
        val bindHost = if (override == null) host else null
        val token = newToken()
        val started = listOf(PORT, 0).firstNotNullOfOrNull { port ->
            runCatching {
                OneTimeFileServer(bindHost, port, file, downloadName, token) { handler.post { stop(QrState.Downloaded) } }
                    .also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
            }.onFailure { Log.w(TAG, "server start failed on port $port: ${it.message}") }.getOrNull()
        } ?: return onState(QrState.Failed("서버를 시작하지 못했습니다"))
        server = started
        val address = "http://$host:${override?.port ?: started.listeningPort}"
        val url = "$address/d/$token"
        handler.postDelayed(expire, TTL_MS)
        Log.i(TAG, "qr server up bind=${bindHost ?: "0.0.0.0"}:${started.listeningPort} address=$address")
        if (BuildConfig.DEBUG) Log.d(TAG, "qr url $url")
        onState(QrState.Waiting(url, address, System.currentTimeMillis() + TTL_MS, override != null))
    }

    /** 서버가 떠 있을 때만 종료하고 상태를 바꾼다 (이미 끝난 상태를 덮어쓰지 않게) */
    fun stop(next: QrState) {
        val running = server ?: return
        server = null
        handler.removeCallbacks(expire)
        runCatching { running.stop() }
        Log.i(TAG, "qr server stopped -> $next")
        onState(next)
    }

    private fun wifiIpv4(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        return cm.allNetworks
            .filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    private fun newToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "BizDriveLog"
        const val PORT = 8765
        const val TTL_MS = 5 * 60 * 1000L
    }
}

/** GET /d/{token} 하나만 응답. 파일을 끝까지 보낸 뒤에야 사용 처리(중간에 끊긴 요청은 만료 전 재시도 가능). */
private class OneTimeFileServer(
    bindHost: String?,
    port: Int,
    private val file: File,
    private val downloadName: String,
    private val token: String,
    private val onDownloaded: () -> Unit,
) : NanoHTTPD(bindHost, port) {
    private val used = AtomicBoolean(false)

    override fun serve(session: IHTTPSession): Response {
        val given = session.uri.removePrefix("/d/")
        if (!session.uri.startsWith("/d/") || !MessageDigest.isEqual(given.toByteArray(), token.toByteArray())) {
            return text(Response.Status.NOT_FOUND, "Not found")
        }
        if (session.method != Method.GET) return text(Response.Status.METHOD_NOT_ALLOWED, "GET only")
        if (used.get()) return text(Response.Status.GONE, "이미 받은 링크입니다. 차량에서 다시 QR을 만들어 주세요.")
        val length = file.length()
        val body = object : FilterInputStream(FileInputStream(file)) {
            var sent = 0L
            override fun read(): Int = super.read().also { if (it >= 0) sent++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) sent += it }
            @Throws(IOException::class)
            override fun close() {
                super.close()
                if (sent >= length && used.compareAndSet(false, true)) onDownloaded()
            }
        }
        return newFixedLengthResponse(Response.Status.OK, XLSX_MIME, body, length).apply {
            addHeader("Content-Disposition", contentDisposition(downloadName))
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
        }
    }

    private fun text(status: Response.Status, message: String) =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", message).apply { addHeader("Cache-Control", "no-store") }

    companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        /** 한글 파일명 유지: RFC 6266 filename*(UTF-8) + 영문 대체 이름 */
        fun contentDisposition(name: String): String {
            // 한글을 못 읽는 클라이언트용 대체 이름: 영숫자만 남김 (운행기록부_12가3456_202610 → BizDriveLog_123456_202610)
            val ascii = "BizDriveLog_" + name.substringBeforeLast('.').filter { it.isLetterOrDigit() && it.code < 0x80 || it == '_' || it == '-' }
                .trim('_').replace(Regex("_+"), "_") + ".xlsx"
            val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
        }
    }
}
