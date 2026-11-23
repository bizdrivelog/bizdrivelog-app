package app.bizdrivelog.voice

import app.bizdrivelog.data.LedgerProfile
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import app.bizdrivelog.export.Ledger
import app.bizdrivelog.export.LedgerPeriod
import app.bizdrivelog.export.LedgerPeriodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Gleo SHOW_DOWNLOAD_QR: 주행 중이거나 확정 운행이 없으면 파일을 만들지 않고 fail */
class GleoQrPrecheckTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val period = LedgerPeriod(LedgerPeriodKind.LAST_MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))

    private fun ledger(confirmed: Boolean): Ledger {
        val at = LocalDate.of(2026, 10, 20).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val trip = Trip(
            id = 1, startedAt = at, startOdometerKm = 12000.0, endedAt = at + 600_000, endOdometerKm = 12012.4,
            mode = TripMode.BUSINESS, purpose = "회의", confirmedAt = if (confirmed) at + 700_000 else null,
        )
        return Ledger.build(listOf(TripRecord(trip, emptyList())), LedgerProfile(), period, zone)
    }

    @Test fun `확정 운행이 없으면 fail`() {
        val result = Gleo.qrPrecheck(ledger(confirmed = false), restricted = false)!!
        assertEquals(Gleo.Status.FAIL, result.status)
        assertEquals("No confirmed trips for last month. Please confirm trips on the screen first.", result.message)
    }

    @Test fun `주행 중이면 확정 운행이 있어도 fail`() {
        val result = Gleo.qrPrecheck(ledger(confirmed = true), restricted = true)!!
        assertEquals(Gleo.Status.FAIL, result.status)
        assertEquals("The QR code cannot be shown while driving. Please try again after parking.", result.message)
    }

    @Test fun `정차 중이고 확정 운행이 있으면 통과`() = assertNull(Gleo.qrPrecheck(ledger(confirmed = true), restricted = false))
}
