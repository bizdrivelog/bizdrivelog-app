package app.bizdrivelog.drive

import app.bizdrivelog.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P→R→P, P→D→P 같은 조작은 운행으로 남기지 않는다 */
class ParkingMoveTest {

    private val start = 12346.0
    private val open = Trip(id = 1, startedAt = 1_000_000L, startOdometerKm = start)

    private fun check(seconds: Long, km: Double) =
        TripEnd.isParkingMove(open, open.startedAt + seconds * 1000, start + km)

    @Test fun `계기판이 그대로면 시간과 무관하게 버린다`() {
        assertTrue(check(10, 0.0))
        assertTrue(check(1800, 0.0)) // D에서 30분 공회전
    }

    @Test fun `1분 미만이면서 0_3km 미만이면 버린다`() {
        assertTrue(check(59, 0.1))
        assertTrue(check(30, 0.2))
    }

    @Test fun `경계값은 운행으로 남긴다`() {
        assertFalse(check(60, 0.1))
        assertFalse(check(59, 0.3))
    }

    @Test fun `1분 미만이어도 실제 이동 거리가 있으면 남긴다`() {
        assertFalse(check(40, 0.5))
    }

    @Test fun `누적거리 부동소수 오차로 0_1km를 놓치지 않는다`() {
        // 12353.5 - 12353.4 = 0.0999…
        val t = open.copy(startOdometerKm = 12353.4)
        assertFalse(TripEnd.isParkingMove(t, t.startedAt + 600_000, 12353.5))
    }

    @Test fun `계기판 값 이상은 버리지 않고 표시 대상으로 둔다`() {
        assertFalse(check(10, -5.0))
    }

    @Test fun `정차 2분 후 재출발은 별도 운행으로 각각 판단한다`() {
        // 합치지 않으므로 앞 운행이 끝난 뒤 뒤 운행도 자기 거리로만 판단한다
        val first = TripEnd.isParkingMove(open, open.startedAt + 900_000, start + 8.0)
        val second = open.copy(id = 2, startedAt = open.startedAt + 1_020_000, startOdometerKm = start + 8.0)
        assertFalse(first)
        assertFalse(TripEnd.isParkingMove(second, second.startedAt + 300_000, start + 10.0))
        assertEquals(2.0, second.copy(endOdometerKm = start + 10.0).distanceKm!!, 0.0)
    }
}
