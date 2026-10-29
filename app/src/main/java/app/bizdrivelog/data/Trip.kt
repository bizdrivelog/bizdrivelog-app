package app.bizdrivelog.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TripMode(val label: String) {
    BUSINESS("업무"),
    COMMUTE("출퇴근"),
    PERSONAL("개인"),
}

/**
 * 운행 1건. 계기판 누적거리 단위는 km (Vehicle SDK getCurrentDistanceDriven = AAOS PERF_ODOMETER).
 * endedAt == null 이면 아직 운행 중. confirmedAt != null 이면 확정·잠금 (DB 트리거로 수정 차단, 이후 변경은 정정 이력으로만).
 * 개인 운행은 좌표·장소를 남기지 않는다 (거리만 기록).
 */
@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val startOdometerKm: Double,
    val endedAt: Long? = null,
    val endOdometerKm: Double? = null,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val endLat: Double? = null,
    val endLng: Double? = null,
    val fromPlace: PlaceKind? = null,
    val toPlace: PlaceKind? = null,
    val mode: TripMode? = null,
    /** 분류 근거 (자동 제안 사유 또는 "직접 선택") */
    val modeBy: String = "",
    val purpose: String = "",
    val visitPlace: String = "",
    val confirmedAt: Long? = null,
) {
    /** 계기판 값이 0.1km 단위라 소수 첫째 자리로 맞춘다 (12353.4 - 12351.0 = 2.3999…) */
    val distanceKm: Double? get() = endOdometerKm?.let { Math.round((it - startOdometerKm) * 10) / 10.0 }
    val isConfirmed: Boolean get() = confirmedAt != null
}
