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
