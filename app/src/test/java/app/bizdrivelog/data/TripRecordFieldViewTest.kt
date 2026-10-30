package app.bizdrivelog.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 운행 상세: 정정된 항목에만 "정정됨"과 원본을 붙인다 */
class TripRecordFieldViewTest {

    private val trip = Trip(
        id = 5, startedAt = 0, startOdometerKm = 100.0, endedAt = 1, endOdometerKm = 124.3,
        mode = TripMode.BUSINESS, purpose = "납품·배송", visitPlace = "다라물산", confirmedAt = 2,
    )

    private fun correction(field: CorrectionField, before: String, after: String, id: Long = 1) =
        Correction(id = id, tripId = 5, field = field, before = before, after = after, reason = "목적 입력 오류", createdAt = 3)

    private fun views(vararg corrections: Correction) =
        TripRecord(trip, corrections.toList()).fieldViews.associateBy { it.field }

    @Test
    fun purposeOnlyCorrection_marksOnlyPurpose() {
        val v = views(correction(CorrectionField.PURPOSE, "납품·배송", "거래처 미팅"))
        assertFalse(v.getValue(CorrectionField.MODE).corrected)
        assertNull(v.getValue(CorrectionField.MODE).original)
        assertEquals("BUSINESS", v.getValue(CorrectionField.MODE).current)
        assertTrue(v.getValue(CorrectionField.PURPOSE).corrected)
        assertEquals("거래처 미팅", v.getValue(CorrectionField.PURPOSE).current)
        assertEquals("납품·배송", v.getValue(CorrectionField.PURPOSE).original)
        assertFalse(v.getValue(CorrectionField.VISIT_PLACE).corrected)
        assertEquals("다라물산", v.getValue(CorrectionField.VISIT_PLACE).current)
    }

    @Test
    fun modeCorrection_marksOnlyMode() {
        val v = views(correction(CorrectionField.MODE, "BUSINESS", "COMMUTE"))
        assertTrue(v.getValue(CorrectionField.MODE).corrected)
        assertEquals("COMMUTE", v.getValue(CorrectionField.MODE).current)
        assertEquals("BUSINESS", v.getValue(CorrectionField.MODE).original)
        assertFalse(v.getValue(CorrectionField.PURPOSE).corrected)
        assertFalse(v.getValue(CorrectionField.VISIT_PLACE).corrected)
    }

    @Test
    fun noCorrection_nothingMarked() {
        assertTrue(views().values.none { it.corrected })
    }

    @Test
    fun repeatedCorrections_showLatestValueAndOriginalFromTrip() {
        val v = views(
            correction(CorrectionField.VISIT_PLACE, "다라물산", "다라물산 본사", id = 1),
            correction(CorrectionField.VISIT_PLACE, "다라물산 본사", "다라물산 2공장", id = 2),
        )
        assertEquals("다라물산 2공장", v.getValue(CorrectionField.VISIT_PLACE).current)
        assertEquals("다라물산", v.getValue(CorrectionField.VISIT_PLACE).original)
    }
}
