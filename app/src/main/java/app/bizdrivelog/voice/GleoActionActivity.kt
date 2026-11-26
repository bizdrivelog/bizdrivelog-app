package app.bizdrivelog.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.bizdrivelog.ui.MainActivity

/**
 * Gleo component = "activity" 함수(SHOW_DOWNLOAD_QR)의 입구. 매니페스트에서 Gleo만 가진 서명 권한
 * ai.umos.gleo.permission.APP_ACTION을 요구한다. 화면 없이 요청을 [Gleo.qrRequests]에 넣고 MainActivity로 넘긴 뒤 바로 끝난다.
 */
class GleoActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Gleo.SHOW_DOWNLOAD_QR) {
            Gleo.qrRequests.put(Gleo.params(intent))
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
