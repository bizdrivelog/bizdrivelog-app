package app.bizdrivelog.ui

import app.bizdrivelog.data.Correction
import app.bizdrivelog.data.CorrectionField
import app.bizdrivelog.data.PlaceKind
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/** 목록·상세의 구간과 둘째 줄 문구: 정정 반영값을 쓰고, 위치 문구는 실제로 저장된 위치를 따른다 */
class TripListTextTest {

    private val business = Trip(
        id = 5, startedAt = 0, startOdometerKm = 100.0, endedAt = 1, endOdometerKm = 124.3,
        startLat = 37.5, startLng = 127.0, endLat = 37.4, endLng = 127.1, fromPlace = PlaceKind.HOME,
        mode = TripMode.BUSINESS, purpose = "거래처 방문", visitPlace = "한빛상사", confirmedAt = 2,
    )

    /** 종료 시 개인이라 좌표·장소가 처음부터 없는 운행 */
    private val personal = business.copy(
        startLat = null, startLng = null, endLat = null, endLng = null, fromPlace = null,
        mode = TripMode.PERSONAL, purpose = "", visitPlace = "",
    )

    private fun correction(field: CorrectionField, before: String, after: String, id: Long = 1) =
        Correction(id = id, tripId = 5, field = field, before = before, after = after, reason = "운행 모드 오분류", createdAt = 3)

    @Test fun `상세 구간은 정정된 방문처를 쓴다`() {
        val record = TripRecord(business, listOf(correction(CorrectionField.VISIT_PLACE, "한빛상사", "대한전자")))
        assertEquals("자택 → 대한전자", routeText(business, record))
        assertEquals("자택 → 한빛상사", routeText(business))
    }

    @Test fun `원래 개인 운행은 위치 미저장`() {
        assertEquals("개인 운행 · 위치 미저장", subLine(TripRecord(personal, emptyList())))
    }

    @Test fun `업무에서 개인으로 정정한 운행은 원본 구간이 남아 있다고 표시한다`() {
        val record = TripRecord(business, listOf(correction(CorrectionField.MODE, "BUSINESS", "PERSONAL")))
        assertEquals("개인 운행(정정) · 원본 구간 보존", subLine(record))
    }

    @Test fun `업무 운행은 목적을 표시한다`() {
        assertEquals("거래처 방문", subLine(TripRecord(business, emptyList())))
    }
}
