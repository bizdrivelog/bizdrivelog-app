package app.bizdrivelog.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.bizdrivelog.BizDriveLogApp
import app.bizdrivelog.data.ProfileStore
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import app.bizdrivelog.export.Ledger
import app.bizdrivelog.export.LedgerPeriod
import app.bizdrivelog.export.LedgerPeriodKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Locale

/**
 * Gleo Interface (assets/schema.json). Gleo가 function 이름을 Intent action으로, 인자를 ACTION_PARAM(JSON 문자열)으로 보내고,
 * 앱은 APP_ACTION_RESPONSE 브로드캐스트로 결과(success/asking/fail)를 돌려준다. Gleo는 7초 뒤 취소하므로 그 안에 답한다.
 */
object Gleo {
    const val SET_TRIP_PURPOSE = "SET_TRIP_PURPOSE"
    const val GET_BUSINESS_RATIO = "GET_BUSINESS_RATIO"
    const val SHOW_DOWNLOAD_QR = "SHOW_DOWNLOAD_QR"

    private const val TAG = "BizDriveLog"
    private const val ACTION_PARAM = "ACTION_PARAM"
    private const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    /** Gleo 7초 타임아웃보다 먼저 fail로 답한다 */
    const val DEADLINE_MS = 5_000L

    enum class Status(val value: String) { SUCCESS("success"), ASKING("asking"), FAIL("fail") }

    data class Result(val status: Status, val message: String, val data: JSONArray? = null)

    /** 공식 예시는 ACTION_PARAM 값이 따옴표로 한 번 더 감싸진 경우가 있어 둘 다 받는다 */
    fun params(intent: Intent): JSONObject {
        val raw = intent.getStringExtra(ACTION_PARAM)?.trim().orEmpty()
        if (raw.isEmpty()) return JSONObject()
        return runCatching {
            when (val value = JSONTokener(raw).nextValue()) {
                is JSONObject -> value
                is String -> JSONObject(value)
                else -> JSONObject()
            }
        }.getOrElse {
            runCatching { JSONObject(raw.removeSurrounding("\"").replace("\\\"", "\"")) }.getOrDefault(JSONObject())
        }
    }

    fun respond(context: Context, functionName: String, result: Result) {
        runCatching {
            val json = JSONObject().apply {
                put("functionName", functionName)
                put("status", result.status.value)
                put("message", result.message)
                result.data?.let { put("data", it.toString()) }
            }
            context.sendBroadcast(
                Intent(RESPONSE_ACTION)
                    .putExtra("packageName", context.packageName)
                    .putExtra("response", json.toString())
            )
            Log.i(TAG, "gleo response $json")
        }.onFailure { Log.e(TAG, "gleo response failed", it) }
    }

    /** 진행 중 운행(주행 중 명령)이 있으면 그 운행, 없으면 가장 최근의 미확정 운행에 목적을 적는다. 확정된 운행은 건드리지 않는다. */
    suspend fun setTripPurpose(context: Context, params: JSONObject): Result {
        val dao = (context.applicationContext as BizDriveLogApp).database.tripDao()
        val trip = dao.findOpen() ?: dao.findLatestUnconfirmed()
            ?: return Result(Status.FAIL, "There is no unconfirmed trip to update. Confirmed trips can only be corrected on the screen.")
        val mode = when (params.optString("tripType").lowercase(Locale.ROOT)) {
            "commute" -> TripMode.COMMUTE
            "personal" -> TripMode.PERSONAL
            else -> TripMode.BUSINESS
        }
        val purpose = params.optString("purpose").trim()
        val visitPlace = params.optString("visitPlace").trim()
        if (mode == TripMode.BUSINESS && purpose.isEmpty()) {
            return Result(Status.ASKING, "What was the purpose of this business trip?", JSONArray().put(JSONObject().put("missingField", "purpose")))
        }
        val savedPurpose = if (mode == TripMode.PERSONAL) "" else purpose
        val savedVisit = if (mode == TripMode.BUSINESS) visitPlace else ""
        dao.classify(trip.id, mode, "Gleo 음성 명령", savedPurpose, savedVisit)
        Log.i(TAG, "gleo set trip ${trip.id} mode=$mode purpose=$savedPurpose visit=$savedVisit open=${trip.endedAt == null}")
        val data = JSONArray().put(
            JSONObject()
                .put("tripId", trip.id)
                .put("tripType", mode.name.lowercase(Locale.ROOT))
                .put("purpose", savedPurpose)
                .put("visitPlace", savedVisit)
                .put("inProgress", trip.endedAt == null)
        )
        val message = when {
            trip.endedAt == null -> "The trip type was saved for the current trip. Please confirm it on the screen after parking."
            else -> "The trip type was saved. Please confirm the trip on the screen."
        }
        return Result(Status.SUCCESS, message, data)
    }

    suspend fun businessRatio(context: Context, params: JSONObject): Result {
        val dao = (context.applicationContext as BizDriveLogApp).database.tripDao()
        val corrections = dao.getCorrections().groupBy { it.tripId }
        val records = dao.getAll().map { TripRecord(it, corrections[it.id].orEmpty()) }
        val period = LedgerPeriod.of(periodKind(params))
        val totals = Ledger.build(records, ProfileStore(context).load(), period).totals
        val name = when (period.kind) {
            LedgerPeriodKind.THIS_MONTH -> "This month"
            LedgerPeriodKind.LAST_MONTH -> "Last month"
            LedgerPeriodKind.TAX_YEAR -> "This year"
        }
        val message = String.format(
            Locale.US, "%s, the business-use ratio is %.1f percent: %.1f km of %.1f km driven.",
            name, totals.ratioPercent, totals.businessUseKm, totals.totalKm,
        )
        val data = JSONArray().put(
            JSONObject()
                .put("period", period.label)
                .put("totalKm", totals.totalKm)
                .put("businessUseKm", totals.businessUseKm)
                .put("businessUseRatioPercent", totals.ratioPercent)
        )
        return Result(Status.SUCCESS, message, data)
    }

    fun periodKind(params: JSONObject): LedgerPeriodKind = when (params.optString("period").lowercase(Locale.ROOT)) {
        "last_month" -> LedgerPeriodKind.LAST_MONTH
        "this_year" -> LedgerPeriodKind.TAX_YEAR
        else -> LedgerPeriodKind.THIS_MONTH
    }
}

/** component = "service" 함수 (화면 없이 처리): SET_TRIP_PURPOSE, GET_BUSINESS_RATIO */
class GleoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val params = Gleo.params(intent)
        Log.i("BizDriveLog", "gleo action $action params=$params")
        val pending = goAsync()
        scope.launch {
            val result = runCatching {
                withTimeoutOrNull(Gleo.DEADLINE_MS) {
                    when (action) {
                        Gleo.SET_TRIP_PURPOSE -> Gleo.setTripPurpose(context, params)
                        Gleo.GET_BUSINESS_RATIO -> Gleo.businessRatio(context, params)
                        else -> Gleo.Result(Gleo.Status.FAIL, "This function is not supported.")
                    }
                } ?: Gleo.Result(Gleo.Status.FAIL, "A timeout occurred while processing the request.")
            }.getOrElse {
                Log.e("BizDriveLog", "gleo action failed", it)
                Gleo.Result(Gleo.Status.FAIL, "An error occurred while processing the request.")
            }
            Gleo.respond(context, action, result)
            pending.finish()
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
