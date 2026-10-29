package app.bizdrivelog.export

import app.bizdrivelog.data.Correction
import app.bizdrivelog.data.CorrectionField
import app.bizdrivelog.data.LedgerProfile
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 집계·엑셀은 확정 운행만, 정정 반영값으로 */
class LedgerConfirmedTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val period = LedgerPeriod(LedgerPeriodKind.THIS_MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))

    private fun trip(id: Long, km: Double, mode: TripMode, confirmed: Boolean): Trip {
        val at = LocalDate.of(2026, 10, 30).atTime(8, 0).atZone(zone).toInstant().toEpochMilli() + id * 3_600_000
        val start = 12000.0 + id * 100
        return Trip(
            id = id, startedAt = at, startOdometerKm = start, endedAt = at + 600_000, endOdometerKm = start + km,
            mode = mode, confirmedAt = if (confirmed) at + 700_000 else null,
        )
    }

    private fun modeCorrection(tripId: Long, before: TripMode, after: TripMode) =
        Correction(id = 1, tripId = tripId, field = CorrectionField.MODE, before = before.name, after = after.name, reason = "운행 모드 오분류", createdAt = 0)

    private val records = listOf(
        TripRecord(trip(1, 12.4, TripMode.BUSINESS, confirmed = true), emptyList()),
        // 운행 #4처럼 개인 → 출퇴근 정정
        TripRecord(trip(2, 12.5, TripMode.PERSONAL, confirmed = true), listOf(modeCorrection(2, TripMode.PERSONAL, TripMode.COMMUTE))),
        TripRecord(trip(3, 11.6, TripMode.BUSINESS, confirmed = false), emptyList()),
    )
    private val ledger = Ledger.build(records, LedgerProfile(), period, zone)

    @Test fun unconfirmedTripIsNotInRowsOrTotals() {
        assertEquals(listOf(1L, 2L), ledger.rows.map { it.record.trip.id })
        assertEquals(24.9, ledger.totals.totalKm, 0.0)
    }

    @Test fun unconfirmedTripIsCountedSeparately() {
        assertEquals(listOf(3L), ledger.pending.map { it.trip.id })
        assertEquals(11.6, ledger.pendingKm, 0.0)
    }

    @Test fun correctedModeIsUsedInTotals() {
        val corrected = ledger.rows.first { it.record.trip.id == 2L }
        assertEquals(12.5, corrected.commuteKm!!, 0.0)
        assertEquals(null, corrected.businessKm)
        // 업무 12.4 + 출퇴근 12.5 = 24.9 → 100%
        assertEquals(24.9, ledger.totals.businessUseKm, 0.0)
        assertEquals(100.0, ledger.totals.ratioPercent, 0.0)
    }

    @Test fun remarkShowsCorrectedModeWithoutUnconfirmedMark() {
        val remark = ledger.rows.first { it.record.trip.id == 2L }.remark
        assertTrue(remark.startsWith("[출퇴근]"))
        assertTrue(remark.contains("정정 1건"))
        assertFalse(ledger.rows.any { it.remark.contains("미확정") })
    }

    @Test fun allUnconfirmedGivesEmptyLedger() {
        val only = Ledger.build(listOf(records[2]), LedgerProfile(), period, zone)
        assertTrue(only.rows.isEmpty())
        assertEquals(0.0, only.totals.ratioPercent, 0.0)
        assertEquals(1, only.pending.size)
    }
}
