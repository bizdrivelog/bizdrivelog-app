package app.bizdrivelog.export

import app.bizdrivelog.data.LedgerProfile
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class OdometerAnomalyTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val day = LocalDate.of(2026, 10, 29)
    private val period = LedgerPeriod(LedgerPeriodKind.THIS_MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))

    private fun trip(id: Long, start: Double, end: Double?, mode: TripMode = TripMode.BUSINESS): Trip {
        val at = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli() + id * 3_600_000
        return Trip(id = id, startedAt = at, startOdometerKm = start, endedAt = end?.let { at + 600_000 }, endOdometerKm = end, mode = mode)
    }

    private fun build(vararg trips: Trip) = Ledger.build(trips.map { TripRecord(it, emptyList()) }, LedgerProfile(), period, zone)

    @Test fun normalTripHasDistance() {
        val t = trip(1, 12043.2, 12050.0)
        assertFalse(t.odometerAnomaly)
        assertEquals(6.8, t.distanceKm!!, 0.0)
    }

    @Test fun sameOdometerIsZeroNotAnomaly() {
        val t = trip(1, 12050.0, 12050.0)
        assertFalse(t.odometerAnomaly)
        assertEquals(0.0, t.distanceKm!!, 0.0)
    }

    @Test fun endBelowStartIsAnomaly() {
        val t = trip(1, 12050.0, 0.0)
        assertTrue(t.odometerAnomaly)
        assertNull(t.distanceKm)
        assertEquals(0.0, TripRecord(t, emptyList()).businessUseKm, 0.0)
    }

    @Test fun openTripIsNotAnomaly() = assertFalse(trip(1, 12050.0, null).odometerAnomaly)

    @Test fun anomalyExcludedFromTotals() {
        val ledger = build(
            trip(1, 12000.0, 12012.3),
            trip(2, 12012.3, 12020.5, TripMode.PERSONAL),
            trip(3, 12050.0, 0.0),
        )
        assertEquals(listOf(1L, 2L), ledger.rows.map { it.record.trip.id })
        assertEquals(listOf(3L), ledger.anomalies.map { it.trip.id })
        assertEquals(20.5, ledger.totals.totalKm, 0.0)
        assertEquals(12.3, ledger.totals.businessUseKm, 0.0)
        assertEquals(60.0, ledger.totals.ratioPercent, 0.0)
    }
}
