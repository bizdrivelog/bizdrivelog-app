package app.bizdrivelog.drive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 서비스가 갱신하고 화면이 구독하는 현재 차량 상태. */
data class DriveStatus(
    val gear: Int? = null,
    val odometerKm: Double? = null,
    /** 운행 중이면 출발 시점 계기판 누적거리, 주차 중이면 null */
    val tripStartOdometerKm: Double? = null,
) {
    val isDriving: Boolean get() = tripStartOdometerKm != null
    val tripDistanceKm: Double?
        get() = tripStartOdometerKm?.let { start -> odometerKm?.let { it - start } }
}

object DriveState {
    private val _status = MutableStateFlow(DriveStatus())
    val status: StateFlow<DriveStatus> = _status.asStateFlow()

    internal fun update(transform: (DriveStatus) -> DriveStatus) {
        _status.value = transform(_status.value)
    }
}
