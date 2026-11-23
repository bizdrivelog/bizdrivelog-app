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

    /** startKnown = false: 출발 위치를 모름(운행 중 앱 재시작). 출퇴근·개인을 판단하지 않고 업무로 제안한다 */
    fun suggest(startedAt: Long, from: PlaceKind?, to: PlaceKind?, zone: ZoneId = ZoneId.systemDefault(), startKnown: Boolean = true): Suggestion {
        val date = Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()
        val dayOff = dayOffName(date)
        val suggestion = when {
            dayOff != null -> Suggestion(TripMode.PERSONAL, "$dayOff 운행")
            !startKnown -> Suggestion(TripMode.BUSINESS, "평일 · 출발 위치 없음 (앱 재시작)")
            from == PlaceKind.HOME && to == PlaceKind.OFFICE ->
                Suggestion(TripMode.COMMUTE, "자택 영역 이탈 → 회사 영역 진입", "출근")
            from == PlaceKind.OFFICE && to == PlaceKind.HOME ->
                Suggestion(TripMode.COMMUTE, "회사 영역 이탈 → 자택 영역 진입", "퇴근")
            else -> Suggestion(TripMode.BUSINESS, "평일 · 자택↔회사 외 구간")
        }
        // 공휴일 목록이 없는 해에는 평일로 본 근거가 확실하지 않음을 남긴다 (주말 판단은 영향 없음)
        return if (dayOff == null && date.year !in COVERED_YEARS) suggestion.copy(reason = "${suggestion.reason} (공휴일 정보 없음)") else suggestion
    }

    /** 아래 공휴일 목록이 들어 있는 해 */
    val COVERED_YEARS = setOf(2026, 2027)

    fun dayOffName(date: LocalDate): String? = when {
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY -> "주말"
        else -> HOLIDAYS[date]?.let { "공휴일($it)" }
    }

    // 관공서 공휴일 (대체공휴일 포함). 해마다 정부 월력요항으로 갱신하고 COVERED_YEARS에 그 해를 넣어야 함
    // 출처: 2027년은 우주항공청 2027년도 월력요항 보도(2026-06-29), 부처님오신날은 한국천문연구원 음력 자료(음력 4/1 = 5/6 → 4/8 = 5/13)
    // 노동절·제헌절은 2026년부터 관공서 공휴일 (관공서의 공휴일에 관한 규정 개정, 2026-04 국무회의 의결)
    private val HOLIDAYS: Map<LocalDate, String> = mapOf(
        "2026-01-01" to "신정",
        "2026-02-16" to "설날 연휴",
        "2026-02-17" to "설날",
        "2026-02-18" to "설날 연휴",
        "2026-03-01" to "삼일절",
        "2026-03-02" to "대체공휴일",
        "2026-05-01" to "노동절",
        "2026-05-05" to "어린이날",
        "2026-05-24" to "부처님오신날",
        "2026-05-25" to "대체공휴일",
        "2026-06-03" to "지방선거일",
        "2026-06-06" to "현충일",
        "2026-07-17" to "제헌절",
        "2026-08-15" to "광복절",
        "2026-08-17" to "대체공휴일",
        "2026-09-24" to "추석 연휴",
        "2026-09-25" to "추석",
        "2026-09-26" to "추석 연휴",
        "2026-10-03" to "개천절",
        "2026-10-05" to "대체공휴일",
        "2026-10-09" to "한글날",
        "2026-12-25" to "성탄절",
        "2027-01-01" to "신정",
        "2027-02-06" to "설날 연휴",
        "2027-02-07" to "설날",
        "2027-02-08" to "설날 연휴",
        "2027-02-09" to "대체공휴일",
        "2027-03-01" to "삼일절",
        "2027-05-01" to "노동절",
        "2027-05-03" to "대체공휴일",
        "2027-05-05" to "어린이날",
        "2027-05-13" to "부처님오신날",
        "2027-06-06" to "현충일",
        "2027-07-17" to "제헌절",
        "2027-07-19" to "대체공휴일",
        "2027-08-15" to "광복절",
        "2027-08-16" to "대체공휴일",
        "2027-09-14" to "추석 연휴",
        "2027-09-15" to "추석",
        "2027-09-16" to "추석 연휴",
        "2027-10-03" to "개천절",
        "2027-10-04" to "대체공휴일",
        "2027-10-09" to "한글날",
        "2027-10-11" to "대체공휴일",
        "2027-12-25" to "성탄절",
        "2027-12-27" to "대체공휴일",
    ).mapKeys { LocalDate.parse(it.key) }
}
