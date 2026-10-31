package app.bizdrivelog.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

const val RECORD_GAP_LABEL = "기록 공백"

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
    /** ④ 사용자(차량 이용자) — 운행 시작 시점 설정값을 남겨 나중에 설정을 바꿔도 기록이 바뀌지 않게 한다 */
    val userDept: String? = null,
    val userName: String? = null,
    /** 기록 공백: 서비스가 멈춘 동안 계기판이 늘어난 구간. 시작·종료 시각은 정확하지 않고 분류는 사용자가 확인한다 */
    @ColumnInfo(defaultValue = "0") val recordGap: Boolean = false,
) {
    /** 계기판 값 이상: 종료 누적거리가 시작보다 작다 (계기판 초기화·교체 등). 거리를 알 수 없어 집계에서 뺀다 */
    val odometerAnomaly: Boolean get() = endOdometerKm != null && endOdometerKm < startOdometerKm

    /** 계기판 값이 0.1km 단위라 소수 첫째 자리로 맞춘다 (12353.4 - 12351.0 = 2.3999…). 계기판 값 이상이면 null */
    val distanceKm: Double? get() = endOdometerKm?.takeIf { it >= startOdometerKm }?.let { Math.round((it - startOdometerKm) * 10) / 10.0 }
    val isConfirmed: Boolean get() = confirmedAt != null
}
