package app.bizdrivelog.export

import app.bizdrivelog.data.LedgerProfile
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 엑셀 아래 안내 줄: 한 운행은 한 줄에만. 계기판 값 이상은 확정 여부와 관계없이 이상 줄에서만 센다.
 * 이상 운행의 주행 전 계기판은 앞 운행의 주행 후 값과 같게 둬서 이어짐 안내 줄이 섞이지 않게 한다
 */
class FootNotesTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val period = LedgerPeriod(LedgerPeriodKind.THIS_MONTH, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))

    private fun trip(id: Long, start: Double, end: Double, confirmed: Boolean): Trip {
        val at = LocalDate.of(2026, 10, 29).atTime(9, 0).atZone(zone).toInstant().toEpochMilli() + id * 3_600_000
        return Trip(
            id = id, startedAt = at, startOdometerKm = start, endedAt = at + 600_000, endOdometerKm = end,
            mode = TripMode.BUSINESS, purpose = "회의", confirmedAt = if (confirmed) at + 700_000 else null,
        )
    }

    private val normal = trip(1, 12000.0, 12012.3, confirmed = true)

    /** 파일 식별값 줄을 뺀 ※ 안내 줄 */
    private fun notes(vararg trips: Trip): List<String> {
        val ledger = Ledger.build(trips.map { TripRecord(it, emptyList()) }, LedgerProfile(), period, zone)
        val all = LedgerExporter.footNotes(ledger)
        assertTrue(all.last().startsWith("파일 식별값: BDL-"))
        return all.dropLast(1)
    }

    @Test fun `확정된 이상 운행만 있으면 이상 줄만`() = assertEquals(
        listOf("※ 계기판 값 이상 1건은 거리를 알 수 없어 제외했습니다."),
        notes(normal, trip(2, 12012.3, 0.0, confirmed = true)),
    )

    @Test fun `미확정 운행만 있으면 미확정 줄만`() = assertEquals(
        listOf("※ 미확정 운행 1건은 제외했습니다. 확정한 뒤 다시 내보내면 포함됩니다."),
        notes(normal, trip(2, 12012.3, 12020.0, confirmed = false)),
    )

    // 확정해도 거리를 알 수 없어 포함되지 않으므로 "확정하면 포함" 줄에 넣지 않는다
    @Test fun `미확정이면서 이상이면 이상 줄에서만 센다`() = assertEquals(
        listOf("※ 계기판 값 이상 1건은 거리를 알 수 없어 제외했습니다."),
        notes(normal, trip(2, 12012.3, 0.0, confirmed = false)),
    )

    // 주차 조작으로 버린 이동처럼 운행 사이에 기록 없이 늘어난 거리. 이상 줄 다음, 파일 식별값 앞
    @Test fun `운행 사이 늘어난 계기판은 이상 줄 다음에 안내한다`() = assertEquals(
        listOf(
            "※ 계기판 값 이상 1건은 거리를 알 수 없어 제외했습니다.",
            "※ 운행 사이 기록 없이 계기판이 늘어난 구간 1곳, 합계 0.2 km(주차 자리 이동 등). ⑪에 포함되지 않음",
        ),
        notes(normal, trip(2, 12012.3, 0.0, confirmed = true), trip(3, 0.2, 5.0, confirmed = true)),
    )
}
