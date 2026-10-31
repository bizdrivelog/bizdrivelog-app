package app.bizdrivelog.drive

import app.bizdrivelog.data.PlaceKind
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripMode
import java.time.ZoneId

/**
 * 운행 종료 처리. 출발 위치(좌표·장소)는 운행 중 DB에 쓰지 않고 서비스 메모리(StartPoint)에만 둔다.
 * 종료 시 개인 운행이 아니면 그때 DB에 쓰고, 개인 운행이면 버린다 → 개인 운행 좌표는 DB에 한 번도 기록되지 않는다.
 * 서비스가 운행 중 재시작돼 StartPoint가 없으면 출퇴근·개인을 판단하지 않고 업무로 제안한다(사용자가 확인 카드에서 고침).
 */
object TripEnd {

    data class StartPoint(val tripId: Long, val lat: Double?, val lng: Double?, val place: PlaceKind?)

    data class Result(val trip: Trip, val suggestion: TripClassifier.Suggestion, val preset: Boolean)

    /** 계기판 최소 단위가 0.1km라 이보다 작으면(표시 0.0km) 움직이지 않은 것으로 본다 */
    const val MIN_TRIP_KM = 0.1
    /** 주차 자리 옮기기: 1분 미만이면서 0.3km 미만. 시간만으로 버리면 실제 이동 거리가 기록에서 빠진다 */
    const val PARKING_MOVE_MS = 60_000L
    const val PARKING_MOVE_KM = 0.3

    /**
     * P→R→P, P→D→P처럼 이동이 거의 없는 조작인지. 맞으면 운행으로 남기지 않는다.
     * 계기판 값 이상(종료 < 시작)은 조작이 아니라 표시해야 할 문제이므로 해당하지 않는다.
     */
    fun isParkingMove(open: Trip, endedAt: Long, odometerKm: Double): Boolean {
        val km = open.copy(endOdometerKm = odometerKm).distanceKm ?: return false
        return km < MIN_TRIP_KM || (endedAt - open.startedAt < PARKING_MOVE_MS && km < PARKING_MOVE_KM)
    }

    fun close(
        open: Trip,
        start: StartPoint?,
        endedAt: Long,
        odometerKm: Double,
        endLat: Double?,
        endLng: Double?,
        to: PlaceKind?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Result {
        val known = start?.takeIf { it.tripId == open.id }
        // 주행 중 Gleo로 이미 분류·목적을 말했으면 그 값을 유지한다
        val preset = open.mode?.let { TripClassifier.Suggestion(it, open.modeBy, open.purpose) }
        val suggestion = preset ?: TripClassifier.suggest(open.startedAt, known?.place, to, zone, startKnown = known != null)
        val keep = suggestion.mode != TripMode.PERSONAL
        val trip = open.copy(
            endedAt = endedAt,
            endOdometerKm = odometerKm,
            startLat = known?.lat.takeIf { keep },
            startLng = known?.lng.takeIf { keep },
            endLat = endLat.takeIf { keep },
            endLng = endLng.takeIf { keep },
            fromPlace = known?.place.takeIf { keep },
            toPlace = to.takeIf { keep },
            mode = suggestion.mode,
            modeBy = suggestion.reason,
            purpose = suggestion.purpose,
        )
        return Result(trip, suggestion, preset != null)
    }
}
