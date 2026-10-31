package app.bizdrivelog.drive

import android.content.Context
import app.bizdrivelog.data.Trip

/** 마지막으로 저장한 차량 상태. 서비스가 멈췄다 다시 시작될 때 현재 상태와 비교한다 */
data class LastDriveState(val odometerKm: Double, val at: Long, val gear: Int?)

/** 쓰기를 줄이려고 기어 변화 외에는 계기판이 0.5 km 늘었거나, 값이 바뀐 채 30초가 지났을 때만 저장한다 (60 km/h면 시간당 약 120회) */
object DriveStateSave {
    const val STEP_KM = 0.5
    const val INTERVAL_MS = 30_000L

    fun shouldSave(last: LastDriveState?, km: Double, now: Long): Boolean = when {
        last == null -> true
        km == last.odometerKm -> false
        Math.abs(km - last.odometerKm) >= STEP_KM - 1e-6 -> true
        else -> now - last.at >= INTERVAL_MS
    }
}

class DriveStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("drive_state", Context.MODE_PRIVATE)

    fun load(): LastDriveState? {
        val km = prefs.getString(KM, null)?.toDoubleOrNull() ?: return null
        return LastDriveState(km, prefs.getLong(AT, 0), prefs.getInt(GEAR, -1).takeIf { it >= 0 })
    }

    /** 프로세스가 곧바로 죽어도 남도록 commit (IO 스레드에서 호출) */
    fun save(state: LastDriveState) {
        prefs.edit().putString(KM, state.odometerKm.toString()).putLong(AT, state.at).putInt(GEAR, state.gear ?: -1).commit()
    }

    private companion object {
        const val KM = "odometerKm"
        const val AT = "at"
        const val GEAR = "gear"
    }
}

/**
 * 서비스 재시작 시 저장된 마지막 상태와 현재 상태 비교.
 * - 열린 운행 + 지금 P: 마지막 저장 값·시각으로 닫는다
 * - 열린 운행 + 지금 주행 중: 같은 운행으로 이어간다
 * - 그 뒤로 계기판이 늘어나 있으면 그 구간을 기록 공백 운행으로 남긴다 (거리가 빠지지 않게)
 * 저장 상태가 없으면(첫 실행·이전 버전) 공백을 판단할 수 없어 현재 값으로 닫기만 한다.
 */
object Recovery {
    /** 계기판 최소 단위. 이보다 작으면 공백으로 보지 않는다 */
    private const val MIN_GAP_KM = TripEnd.MIN_TRIP_KM

    data class End(val km: Double, val at: Long)
    data class Gap(val fromKm: Double, val fromAt: Long, val toKm: Double, val toAt: Long)
    data class Plan(val close: End? = null, val gap: Gap? = null)

    fun plan(saved: LastDriveState?, open: Trip?, moving: Boolean, currentKm: Double?, recordedKm: Double?, now: Long): Plan {
        if (open != null && moving) return Plan()
        if (saved == null) return Plan(close = open?.let { End(currentKm ?: it.startOdometerKm, now) })
        val close = open?.let { End(maxOf(saved.odometerKm, it.startOdometerKm), maxOf(saved.at, it.startedAt)) }
        val fromKm = close?.km ?: maxOf(saved.odometerKm, recordedKm ?: saved.odometerKm)
        val fromAt = close?.at ?: saved.at
        val gap = currentKm?.takeIf { gapKm(fromKm, it) >= MIN_GAP_KM }?.let { Gap(fromKm, fromAt, it, now) }
        return Plan(close, gap)
    }

    private fun gapKm(from: Double, to: Double) = Math.round((to - from) * 10) / 10.0
}
