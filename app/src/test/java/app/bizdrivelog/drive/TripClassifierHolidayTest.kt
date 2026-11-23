package app.bizdrivelog.drive

import app.bizdrivelog.data.PlaceKind
import app.bizdrivelog.data.TripMode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** 공휴일은 개인 제안, 공휴일 목록이 없는 해의 평일은 근거에 그 사실을 남긴다 */
class TripClassifierHolidayTest {

    private val zone = ZoneId.of("Asia/Seoul")

    private fun suggest(at: String) = TripClassifier.suggest(
        LocalDateTime.parse(at).atZone(zone).toInstant().toEpochMilli(), PlaceKind.HOME, PlaceKind.OFFICE, zone,
    )

    @Test fun `2026 노동절과 제헌절은 개인`() {
        assertEquals(TripClassifier.Suggestion(TripMode.PERSONAL, "공휴일(노동절) 운행"), suggest("2026-05-01T08:10"))
        assertEquals(TripClassifier.Suggestion(TripMode.PERSONAL, "공휴일(제헌절) 운행"), suggest("2026-07-17T08:10"))
    }

    @Test fun `2027 대체공휴일은 개인`() =
        assertEquals(TripClassifier.Suggestion(TripMode.PERSONAL, "공휴일(대체공휴일) 운행"), suggest("2027-05-03T08:10"))

    @Test fun `2027 부처님오신날은 개인`() =
        assertEquals(TripClassifier.Suggestion(TripMode.PERSONAL, "공휴일(부처님오신날) 운행"), suggest("2027-05-13T08:10"))

    @Test fun `공휴일 목록이 없는 해의 평일은 근거에 표시한다`() = assertEquals(
        TripClassifier.Suggestion(TripMode.COMMUTE, "자택 영역 이탈 → 회사 영역 진입 (공휴일 정보 없음)", "출근"),
        suggest("2028-03-08T08:10"),
    )
}
