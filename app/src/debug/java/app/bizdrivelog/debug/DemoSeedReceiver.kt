package app.bizdrivelog.debug

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import app.bizdrivelog.BizDriveLogApp
import app.bizdrivelog.data.LedgerProfile
import app.bizdrivelog.data.Place
import app.bizdrivelog.data.PlaceKind
import app.bizdrivelog.data.ProfileStore
import app.bizdrivelog.data.TripActions
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import app.bizdrivelog.voice.VoiceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 디버그 빌드 전용: 시연 데이터 생성(tools/demo_seed.py)에서 사람이 화면에서 하는 단계만 대신한다.
 * 운행 자체는 Virtual Console(기어·누적거리)·GPS·에뮬레이터 시계로 재현하고 앱이 스스로 기록한다.
 * 확정·정정은 화면과 같은 TripActions 경로를 타므로 DB 잠금 트리거도 그대로 적용된다.
 * 한글은 adb 인자로 넘기기 어려워 문자열 extra는 모두 base64(UTF-8)로 받는다.
 *
 * adb shell am broadcast --user 10 -n app.bizdrivelog/.debug.DemoSeedReceiver --es op <op> ...
 *  - profile: company, bizNo, carModel, plate, dept, userName
 *  - place:   kind(HOME|OFFICE), lat, lng (평문)
 *  - voice:   --ez enabled true|false
 *  - confirm: 가장 최근 미확정 운행을 분류 카드처럼 저장 후 확정. mode(평문), purpose, visit (생략 시 제안값 유지)
 *  - clip:    text (base64). 클립보드에 넣음
 *  - correct: --el tripId N, mode(평문, 생략 시 현재값), purpose, visit, reason
 */
class DemoSeedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as BizDriveLogApp
        CoroutineScope(Dispatchers.IO).launch {
            val op = intent.getStringExtra("op")
            runCatching { handle(app, op, intent) }
                .onSuccess { Log.i(TAG, "seed $op ok $it") }
                .onFailure { Log.e(TAG, "seed $op failed: ${it.message}", it) }
            pending.finish()
        }
    }

    private suspend fun handle(app: BizDriveLogApp, op: String?, intent: Intent): String {
        val dao = app.database.tripDao()
        return when (op) {
            "profile" -> {
                ProfileStore(app).save(
                    LedgerProfile(
                        company = intent.text("company"), bizNo = intent.text("bizNo"), carModel = intent.text("carModel"),
                        plate = intent.text("plate"), dept = intent.text("dept"), userName = intent.text("userName"),
                    )
                )
                "missing=${ProfileStore(app).load().missing}"
            }
            "place" -> {
                val kind = PlaceKind.valueOf(intent.getStringExtra("kind").orEmpty())
                val place = Place(
                    kind = kind,
                    lat = intent.getStringExtra("lat").orEmpty().toDouble(),
                    lng = intent.getStringExtra("lng").orEmpty().toDouble(),
                    registeredAt = System.currentTimeMillis(),
                )
                app.database.placeDao().upsert(place)
                "$kind=${place.lat},${place.lng}"
            }
            "voice" -> {
                VoiceSettings(app).enabled = intent.getBooleanExtra("enabled", true)
                "enabled=${VoiceSettings(app).enabled}"
            }
            "confirm" -> {
                val trip = dao.findLatestUnconfirmed() ?: error("no unconfirmed trip")
                val mode = intent.getStringExtra("mode")?.let { TripMode.valueOf(it) } ?: trip.mode ?: error("trip ${trip.id} has no mode")
                // 분류 카드와 같은 규칙: 제안과 다른 분류를 고르면 근거가 바뀌고 목적·방문처를 비운다
                val same = mode == trip.mode
                val modeBy = if (same) trip.modeBy else "정차 중 화면에서 선택"
                val purpose = intent.textOrNull("purpose") ?: if (same) trip.purpose else ""
                val visit = intent.textOrNull("visit") ?: if (same) trip.visitPlace else ""
                check(TripActions.canConfirm(mode, purpose)) { "trip ${trip.id}: business trip needs a purpose" }
                TripActions.classify(dao, trip.id, mode, modeBy, purpose, visit)
                TripActions.confirm(dao, trip.id)
                val saved = dao.find(trip.id)!!
                check(saved.isConfirmed) { "trip ${trip.id} not confirmed" }
                "trip=${saved.id} mode=${saved.mode} purpose=${saved.purpose} visit=${saved.visitPlace} km=${saved.distanceKm} by=${saved.modeBy}"
            }
            "correct" -> {
                val id = intent.getLongExtra("tripId", -1)
                val trip = dao.find(id) ?: error("no trip $id")
                val record = TripRecord(trip, dao.getCorrections().filter { it.tripId == id })
                val mode = intent.getStringExtra("mode")?.let { TripMode.valueOf(it) } ?: record.effectiveMode ?: error("trip $id has no mode")
                val changes = TripActions.correct(
                    dao, record, mode,
                    intent.textOrNull("purpose") ?: record.effectivePurpose,
                    intent.textOrNull("visit") ?: record.effectiveVisitPlace,
                    intent.text("reason"),
                )
                check(changes.isNotEmpty()) { "trip $id: nothing corrected (not confirmed, no change or no reason)" }
                "trip=$id changes=$changes"
            }
            // 영상 촬영용: adb로 한글을 입력할 수 없어 클립보드에 넣고 KEYCODE_PASTE로 붙인다
            "clip" -> withContext(Dispatchers.Main) {
                app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("seed", intent.text("text")))
                "clip set"
            }
            else -> error("unknown op $op")
        }
    }

    private fun Intent.textOrNull(key: String): String? =
        getStringExtra(key)?.let { String(Base64.decode(it, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8) }

    private fun Intent.text(key: String): String = textOrNull(key).orEmpty()

    private companion object {
        const val TAG = "BizDriveLogSeed"
    }
}
