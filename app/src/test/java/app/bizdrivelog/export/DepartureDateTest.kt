package app.bizdrivelog.export

import app.bizdrivelog.data.LedgerProfile
import app.bizdrivelog.data.PlaceKind
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import app.bizdrivelog.drive.TripClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 날짜를 넘긴 운행의 ③사용일자·월·과세기간·요일 판단은 모두 출발 일시 기준 */
class DepartureDateTest {

    private val zone = ZoneId.of("Asia/Seoul")

    private fun millis(at: String) = LocalDateTime.parse(at).atZone(zone).toInstant().toEpochMilli()

    private fun record(id: Long, from: String, to: String, km: Double, confirmed: Boolean = true) = TripRecord(
        Trip(
            id = id, startedAt = millis(from), startOdometerKm = 12000.0, endedAt = millis(to), endOdometerKm = 12000.0 + km,
            mode = TripMode.BUSINESS, confirmedAt = if (confirmed) millis(to) else null,
        ),
        emptyList(),
    )

    private fun month(y: Int, m: Int) = LedgerPeriod.of(LedgerPeriodKind.THIS_MONTH, LocalDate.of(y, m, 15))
    private fun ledger(period: LedgerPeriod, vararg records: TripRecord) = Ledger.build(records.toList(), LedgerProfile(), period, zone)

    @Test fun `자정을 넘긴 운행은 출발한 날짜로 들어간다`() {
        val l = ledger(month(2026, 10), record(1, "2026-10-14T23:50:00", "2026-10-15T00:20:00", 20.0))
        assertEquals(LocalDate.of(2026, 10, 14), l.rows.single().date)
    }

    @Test fun `월말에 출발해 월초에 도착하면 출발한 달에만 집계한다`() {
        val trip = record(1, "2026-10-31T23:50:00", "2026-11-01T00:20:00", 20.0)
        assertEquals(20.0, ledger(month(2026, 10), trip).totals.totalKm, 0.0)
        assertEquals(LocalDate.of(2026, 10, 31), ledger(month(2026, 10), trip).rows.single().date)
        assertTrue(ledger(month(2026, 11), trip).rows.isEmpty())
    }

    @Test fun `연말에 출발해 새해에 도착하면 출발한 과세기간에 들어간다`() {
        val trip = record(1, "2026-12-31T23:50:00", "2027-01-01T00:20:00", 20.0)
        val y2026 = LedgerPeriod.of(LedgerPeriodKind.TAX_YEAR, LocalDate.of(2026, 6, 1))
        val y2027 = LedgerPeriod.of(LedgerPeriodKind.TAX_YEAR, LocalDate.of(2027, 6, 1))
        assertEquals(1, ledger(y2026, trip).rows.size)
        assertTrue(ledger(y2027, trip).rows.isEmpty())
    }

    @Test fun `월초 0시 00분 출발은 새 달이다`() {
        val trip = record(1, "2026-11-01T00:00:00", "2026-11-01T00:30:00", 20.0)
        assertTrue(ledger(month(2026, 10), trip).rows.isEmpty())
        assertEquals(1, ledger(month(2026, 11), trip).rows.size)
    }

    @Test fun `미확정 표시도 출발한 달 기준이다`() {
        val trip = record(1, "2026-10-31T23:50:00", "2026-11-01T00:20:00", 20.0, confirmed = false)
        assertEquals(1, ledger(month(2026, 10), trip).pending.size)
        assertTrue(ledger(month(2026, 11), trip).pending.isEmpty())
    }

    @Test fun `분류 제안의 요일도 출발 일시 기준이다`() {
        // 일요일 23:50 출발 → 월요일 도착: 주말 운행
        val sunday = TripClassifier.suggest(millis("2026-10-18T23:50:00"), PlaceKind.HOME, PlaceKind.OFFICE, zone)
        assertEquals(TripMode.PERSONAL, sunday.mode)
        // 금요일 23:50 출발 → 토요일 도착: 평일 운행
        val friday = TripClassifier.suggest(millis("2026-10-16T23:50:00"), PlaceKind.HOME, PlaceKind.OFFICE, zone)
        assertEquals(TripMode.COMMUTE, friday.mode)
    }
}
