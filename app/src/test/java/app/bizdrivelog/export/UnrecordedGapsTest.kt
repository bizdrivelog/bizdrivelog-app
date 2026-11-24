package app.bizdrivelog.export

import app.bizdrivelog.data.LedgerProfile
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 이어짐 검사: 앞 운행 ⑥과 다음 운행 ⑤ 사이에 기록 없이 늘어난 계기판 거리 (안내만, ⑪에는 넣지 않음) */
class UnrecordedGapsTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val october = LedgerPeriod(LedgerPeriodKind.THIS_MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))

    private fun trip(
        id: Long, start: Double, end: Double?, day: Int = 29, hour: Int = 8 + id.toInt(),
        confirmed: Boolean = true, recordGap: Boolean = false,
    ): Trip {
        val at = LocalDate.of(2026, 10, 1).plusDays(day - 1L).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        return Trip(
            id = id, startedAt = at, startOdometerKm = start, endedAt = end?.let { at + 600_000 }, endOdometerKm = end,
            mode = TripMode.BUSINESS, purpose = "회의", confirmedAt = if (confirmed && end != null) at + 700_000 else null, recordGap = recordGap,
        )
    }

    private fun ledger(vararg trips: Trip, period: LedgerPeriod = october) =
        Ledger.build(trips.map { TripRecord(it, emptyList()) }, LedgerProfile(), period, zone)

    @Test fun `이어진 운행이면 구간 없음`() {
        val l = ledger(trip(1, 12000.0, 12012.3), trip(2, 12012.3, 12020.0))
        assertEquals(UnrecordedGaps(0, 0.0), l.unrecorded)
        assertNull(LedgerExporter.unrecordedNote(l))
    }

    @Test fun `주차 조작으로 버린 0_2 km는 한 곳으로 센다`() {
        val l = ledger(trip(1, 12000.0, 12012.3), trip(2, 12012.5, 12020.0))
        assertEquals(UnrecordedGaps(1, 0.2), l.unrecorded)
        assertEquals("※ 운행 사이 기록 없이 계기판이 늘어난 구간 1곳, 합계 0.2 km(주차 자리 이동 등). ⑪에 포함되지 않음", LedgerExporter.unrecordedNote(l))
    }

    @Test fun `여러 곳이면 곳 수와 합계`() =
        assertEquals(UnrecordedGaps(2, 0.3), ledger(trip(1, 12000.0, 12012.3), trip(2, 12012.5, 12020.0), trip(3, 12020.1, 12030.0)).unrecorded)

    @Test fun `계기판이 줄어든 차이(초기화)는 세지 않는다`() =
        assertEquals(UnrecordedGaps(0, 0.0), ledger(trip(1, 12000.0, 12012.3), trip(2, 0.0, 12.4)).unrecorded)

    @Test fun `0_1 km 미만은 세지 않고 0_1 km는 부동소수 오차가 있어도 센다`() {
        assertEquals(UnrecordedGaps(0, 0.0), ledger(trip(1, 12000.0, 12012.3), trip(2, 12012.34, 12020.0)).unrecorded)
        // 12012.4 - 12012.3 = 0.09999…
        assertEquals(UnrecordedGaps(1, 0.1), ledger(trip(1, 12000.0, 12012.3), trip(2, 12012.4, 12020.0)).unrecorded)
    }

    @Test fun `다음 운행이 기간 안일 때만 센다`() {
        val sept = trip(1, 12000.0, 12012.3, day = 0) // 9/30
        val oct = trip(2, 12012.5, 12020.0, day = 1)
        val nov = trip(3, 12020.4, 12030.0, day = 32) // 11/1
        assertEquals(UnrecordedGaps(1, 0.2), ledger(sept, oct, nov).unrecorded)
    }

    @Test fun `미확정 운행이 사이에 있으면 이어진 것으로 본다`() =
        assertEquals(
            UnrecordedGaps(0, 0.0),
            ledger(trip(1, 12000.0, 12012.3), trip(2, 12012.3, 12015.0, confirmed = false), trip(3, 12015.0, 12020.0)).unrecorded,
        )

    @Test fun `기록 공백 운행은 앞뒤를 이어 준다`() =
        assertEquals(
            UnrecordedGaps(0, 0.0),
            ledger(trip(1, 12000.0, 12012.3), trip(2, 12012.3, 12017.0, recordGap = true), trip(3, 12017.0, 12020.0)).unrecorded,
        )

    @Test fun `진행 중 운행도 앞 운행과 비교한다`() =
        assertEquals(UnrecordedGaps(1, 0.2), ledger(trip(1, 12000.0, 12012.3), trip(2, 12012.5, null)).unrecorded)

    @Test fun `입력 순서와 관계없이 출발 순으로 비교한다`() =
        assertEquals(UnrecordedGaps(1, 0.2), ledger(trip(2, 12012.5, 12020.0), trip(1, 12000.0, 12012.3)).unrecorded)

    @Test fun `총주행거리와 파일 식별값은 바뀌지 않는다`() {
        val l = ledger(trip(1, 12000.0, 12012.3), trip(2, 12012.5, 12020.0))
        val without = Ledger(l.profile, l.period, l.rows, l.anomalies, l.pending)
        assertEquals(19.8, l.totals.totalKm, 0.0)
        assertEquals(without.totals, l.totals)
        assertEquals(LedgerExporter.fileId(without), LedgerExporter.fileId(l))
    }
}
