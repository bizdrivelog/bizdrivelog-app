package app.bizdrivelog.drive

import app.bizdrivelog.data.PlaceKind
import app.bizdrivelog.data.TripMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 운행 종료 시 3모드 자동 제안. 확정은 사용자가 정차 화면에서 한다. */
object TripClassifier {

    data class Suggestion(val mode: TripMode, val reason: String, val purpose: String = "")

    fun suggest(startedAt: Long, from: PlaceKind?, to: PlaceKind?, zone: ZoneId = ZoneId.systemDefault()): Suggestion {
        val date = Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
        val dayOff = dayOffName(date)
        return when {
            dayOff != null -> Suggestion(TripMode.PERSONAL, "$dayOff 운행")
            from == PlaceKind.HOME && to == PlaceKind.OFFICE ->
                Suggestion(TripMode.COMMUTE, "자택 영역 이탈 → 회사 영역 진입", "출근")
            from == PlaceKind.OFFICE && to == PlaceKind.HOME ->
                Suggestion(TripMode.COMMUTE, "회사 영역 이탈 → 자택 영역 진입", "퇴근")
            else -> Suggestion(TripMode.BUSINESS, "평일 · 자택↔회사 외 구간")
        }
    }

    fun dayOffName(date: LocalDate): String? = when {
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY -> "주말"
        else -> HOLIDAYS[date]?.let { "공휴일($it)" }
    }

    // 2026년 관공서 공휴일 (대체공휴일 포함). 해마다 정부 월력요항으로 갱신해야 함
    private val HOLIDAYS: Map<LocalDate, String> = mapOf(
        "2026-01-01" to "신정",
        "2026-02-16" to "설날 연휴",
        "2026-02-17" to "설날",
        "2026-02-18" to "설날 연휴",
        "2026-03-01" to "삼일절",
        "2026-03-02" to "대체공휴일",
        "2026-05-05" to "어린이날",
        "2026-05-24" to "부처님오신날",
        "2026-05-25" to "대체공휴일",
        "2026-06-03" to "지방선거일",
        "2026-06-06" to "현충일",
        "2026-08-15" to "광복절",
        "2026-08-17" to "대체공휴일",
        "2026-09-24" to "추석 연휴",
        "2026-09-25" to "추석",
        "2026-09-26" to "추석 연휴",
        "2026-10-03" to "개천절",
        "2026-10-05" to "대체공휴일",
        "2026-10-09" to "한글날",
        "2026-12-25" to "성탄절",
    ).mapKeys { LocalDate.parse(it.key) }
}
