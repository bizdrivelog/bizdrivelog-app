package app.bizdrivelog.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 운행 1건. 계기판 누적거리 단위는 km (Vehicle SDK getCurrentDistanceDriven = AAOS PERF_ODOMETER).
 * endedAt == null 이면 아직 운행 중.
 */
@Entity(tableName = "trips")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val startOdometerKm: Double,
    val endedAt: Long? = null,
    val endOdometerKm: Double? = null,
) {
    /** 계기판 값이 0.1km 단위라 소수 첫째 자리로 맞춘다 (12353.4 - 12351.0 = 2.3999…) */
    val distanceKm: Double? get() = endOdometerKm?.let { Math.round((it - startOdometerKm) * 10) / 10.0 }
}
