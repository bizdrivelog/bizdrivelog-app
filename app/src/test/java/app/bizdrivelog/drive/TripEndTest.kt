package app.bizdrivelog.drive

import app.bizdrivelog.data.PlaceKind
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TripEndTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(iso: String) = LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli()

    /** 운행 중 DB에 있는 행: 출발 위치 없음 */
    private fun open(id: Long = 7, start: String = "2026-10-30T18:30", mode: TripMode? = null) =
        Trip(id = id, startedAt = at(start), startOdometerKm = 12381.5, mode = mode, modeBy = if (mode != null) "Gleo 음성 명령" else "")

    private fun close(trip: Trip, start: TripEnd.StartPoint?, to: PlaceKind?, lat: Double = 37.5665) =
        TripEnd.close(trip, start, trip.startedAt + 2_100_000, 12394.0, lat, 126.978, to, zone)

    private val fromOffice = TripEnd.StartPoint(7, 37.5, 127.036, PlaceKind.OFFICE)

    @Test fun openTripRowHasNoStartLocation() {
        val trip = open()
        assertNull(trip.startLat)
        assertNull(trip.startLng)
        assertNull(trip.fromPlace)
    }

    @Test fun businessTripWritesStartFromMemory() {
        val r = close(open(), fromOffice, null, lat = 37.52)
        assertEquals(TripMode.BUSINESS, r.trip.mode)
        assertEquals(37.5, r.trip.startLat!!, 0.0)
        assertEquals(127.036, r.trip.startLng!!, 0.0)
        assertEquals(PlaceKind.OFFICE, r.trip.fromPlace)
        assertEquals(37.52, r.trip.endLat!!, 0.0)
    }

    @Test fun commuteUsesStartPlaceFromMemory() {
        val r = close(open(), fromOffice, PlaceKind.HOME)
        assertEquals(TripMode.COMMUTE, r.trip.mode)
        assertEquals("퇴근", r.trip.purpose)
    }

    @Test fun personalTripNeverGetsCoordinates() {
        // 토요일 → 개인 제안: 메모리에 있던 출발 좌표도 쓰지 않는다
        val r = close(open(start = "2026-10-31T14:00"), fromOffice, PlaceKind.HOME)
        assertEquals(TripMode.PERSONAL, r.trip.mode)
        listOf(r.trip.startLat, r.trip.startLng, r.trip.endLat, r.trip.endLng).forEach { assertNull(it) }
        assertNull(r.trip.fromPlace)
        assertNull(r.trip.toPlace)
    }

    @Test fun restartedServiceSuggestsBusinessWithoutLocation() {
        // 서비스 재시작으로 메모리가 비면 퇴근(회사→자택)처럼 보여도 업무로 제안
        val r = close(open(), null, PlaceKind.HOME)
        assertEquals(TripMode.BUSINESS, r.trip.mode)
        assertTrue(r.suggestion.reason.contains("출발 위치 없음"))
        assertNull(r.trip.startLat)
        assertNull(r.trip.fromPlace)
        assertEquals(PlaceKind.HOME, r.trip.toPlace)
    }

    @Test fun startPointOfAnotherTripIsIgnored() {
        val r = close(open(id = 8), fromOffice, PlaceKind.HOME)
        assertEquals(TripMode.BUSINESS, r.trip.mode)
        assertNull(r.trip.startLat)
    }

    @Test fun gleoPresetIsKept() {
        val r = close(open(mode = TripMode.PERSONAL), fromOffice, null)
        assertTrue(r.preset)
        assertEquals(TripMode.PERSONAL, r.trip.mode)
        assertNull(r.trip.startLat)
        assertFalse(r.trip.modeBy.isEmpty())
    }
}
