package app.bizdrivelog.drive

import app.bizdrivelog.data.LedgerProfile
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import app.bizdrivelog.export.Ledger
import app.bizdrivelog.export.LedgerPeriod
import app.bizdrivelog.export.LedgerPeriodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 서비스 재시작 시 저장된 상태와 현재 상태 비교 */
class RecoveryTest {
    private val open = Trip(id = 1, startedAt = 1_000L, startOdometerKm = 100.0)
    private val saved = LastDriveState(108.0, 5_000L, 8)
    private val now = 9_000L

    @Test fun `열린 운행 + 주행 중이면 같은 운행으로 이어간다`() {
        assertEquals(Recovery.Plan(), Recovery.plan(saved, open, moving = true, currentKm = 120.0, recordedKm = 100.0, now = now))
    }

    @Test fun `열린 운행 + P면 저장 값으로 닫는다`() {
        val plan = Recovery.plan(saved, open, moving = false, currentKm = 108.0, recordedKm = 100.0, now = now)
        assertEquals(Recovery.End(108.0, 5_000L), plan.close)
        assertNull(plan.gap)
    }

    @Test fun `저장 뒤에 더 달렸으면 그 구간은 기록 공백이다 (주차·다음 운행까지 지난 경우)`() {
        val plan = Recovery.plan(saved, open, moving = false, currentKm = 131.5, recordedKm = 100.0, now = now)
        assertEquals(Recovery.End(108.0, 5_000L), plan.close)
        assertEquals(Recovery.Gap(108.0, 5_000L, 131.5, now), plan.gap)
    }

    @Test fun `운행이 닫혀 있는데 계기판이 늘었으면 기록 공백이다`() {
        val plan = Recovery.plan(saved, null, moving = false, currentKm = 120.0, recordedKm = 108.0, now = now)
        assertNull(plan.close)
        assertEquals(Recovery.Gap(108.0, 5_000L, 120.0, now), plan.gap)
    }

    @Test fun `주행 중 시작이어도 빠진 구간을 먼저 남긴다`() {
        assertEquals(12.0, Recovery.plan(saved, null, moving = true, currentKm = 120.0, recordedKm = 108.0, now = now).gap!!.let { it.toKm - it.fromKm }, 1e-9)
    }

    @Test fun `정차 중 멈췄다 돌아오면 아무 일도 없다`() {
        assertEquals(Recovery.Plan(), Recovery.plan(saved, null, moving = false, currentKm = 108.0, recordedKm = 108.0, now = now))
    }

    @Test fun `이미 기록된 구간은 공백을 다시 만들지 않는다`() {
        assertNull(Recovery.plan(saved, null, moving = false, currentKm = 120.0, recordedKm = 120.0, now = now).gap)
    }

    @Test fun `계기판이 줄었으면 공백을 만들지 않고 저장 값으로 닫는다`() {
        val plan = Recovery.plan(saved, open, moving = false, currentKm = 0.0, recordedKm = 100.0, now = now)
        assertEquals(108.0, plan.close!!.km, 0.0)
        assertNull(plan.gap)
    }

    @Test fun `저장 상태가 없으면 현재 값으로 닫고 공백은 판단하지 않는다`() {
        val plan = Recovery.plan(null, open, moving = false, currentKm = 120.0, recordedKm = 100.0, now = now)
        assertEquals(Recovery.End(120.0, now), plan.close)
        assertNull(plan.gap)
        assertEquals(Recovery.Plan(), Recovery.plan(null, null, moving = false, currentKm = 120.0, recordedKm = 100.0, now = now))
    }

    @Test fun `저장 값이 운행 시작보다 앞서면 운행 시작 값으로 닫는다`() {
        val plan = Recovery.plan(LastDriveState(90.0, 500L, 4), open, moving = false, currentKm = 103.0, recordedKm = 90.0, now = now)
        assertEquals(Recovery.End(100.0, 1_000L), plan.close)
        assertEquals(100.0, plan.gap!!.fromKm, 0.0)
    }

    @Test fun `계기판을 못 읽으면 닫기만 한다`() {
        val plan = Recovery.plan(saved, open, moving = false, currentKm = null, recordedKm = 100.0, now = now)
        assertEquals(108.0, plan.close!!.km, 0.0)
        assertNull(plan.gap)
    }

    @Test fun `저장은 0_5km 또는 30초마다만`() {
        val last = LastDriveState(100.0, 0L, 8)
        assertFalse(DriveStateSave.shouldSave(last, 100.0, 999_999L))
        assertFalse(DriveStateSave.shouldSave(last, 100.4, 29_999L))
        assertTrue(DriveStateSave.shouldSave(last, 100.4, 30_000L))
        assertTrue(DriveStateSave.shouldSave(last, 100.5, 1L))
        assertTrue(DriveStateSave.shouldSave(null, 100.0, 0L))
    }

    @Test fun `기록 공백은 엑셀 비고에 표시되고 미확정이면 집계에서 빠진다`() {
        val zone = ZoneId.of("Asia/Seoul")
        val at = LocalDate.of(2026, 10, 20).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val gap = Trip(id = 2, startedAt = at, startOdometerKm = 108.0, endedAt = at + 1000, endOdometerKm = 120.0, recordGap = true)
        val period = LedgerPeriod(LedgerPeriodKind.THIS_MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
        val pending = Ledger.build(listOf(TripRecord(gap, emptyList())), LedgerProfile(), period, zone)
        assertTrue(pending.rows.isEmpty())
        assertEquals(12.0, pending.pendingKm, 0.0)
        val done = gap.copy(mode = TripMode.BUSINESS, purpose = "회의", confirmedAt = at + 2000)
        val row = Ledger.build(listOf(TripRecord(done, emptyList())), LedgerProfile(), period, zone).rows.single()
        assertEquals("[업무] 회의 (기록 공백)", row.remark)
    }
}
