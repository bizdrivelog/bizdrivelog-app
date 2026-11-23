package app.bizdrivelog.export

import app.bizdrivelog.data.LedgerProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** 내보낸 파일 이름: 번호판이 비면 그 부분을 뺀다 */
class FileNameTest {

    private val period = LedgerPeriod(LedgerPeriodKind.THIS_MONTH, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))

    private fun name(plate: String) = LedgerExporter.fileName(Ledger(LedgerProfile(plate = plate), period, emptyList()))

    @Test fun `번호판이 있으면 공백을 빼고 넣는다`() = assertEquals("운행기록부_12가3456_202611.xlsx", name("12가 3456"))

    @Test fun `번호판이 없으면 기간만 붙인다`() = assertEquals("운행기록부_202611.xlsx", name(""))
}
