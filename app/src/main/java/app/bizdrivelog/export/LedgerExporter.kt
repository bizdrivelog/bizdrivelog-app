package app.bizdrivelog.export

import app.bizdrivelog.data.CorrectionField
import app.bizdrivelog.data.LedgerProfile
import app.bizdrivelog.data.RECORD_GAP_LABEL
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import app.bizdrivelog.data.displayValue
import org.dhatim.fastexcel.PaperSize
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class LedgerPeriodKind { THIS_MONTH, LAST_MONTH, TAX_YEAR }

/** 내보내기 기간. 과세기간은 사업연도 = 역년(1/1~12/31)으로 가정 */
data class LedgerPeriod(val kind: LedgerPeriodKind, val start: LocalDate, val end: LocalDate) {
    val label: String
        get() = when (kind) {
            LedgerPeriodKind.THIS_MONTH -> "이번 달 (${start.monthValue}월)"
            LedgerPeriodKind.LAST_MONTH -> "지난 달 (${start.monthValue}월)"
            LedgerPeriodKind.TAX_YEAR -> "과세기간 (${start.year}년)"
        }
    val fileSuffix: String get() = if (kind == LedgerPeriodKind.TAX_YEAR) "${start.year}" else start.format(DateTimeFormatter.ofPattern("yyyyMM"))
    val formText: String get() = "${start.format(DOT)} ～ ${end.format(DOT)}"

    operator fun contains(date: LocalDate) = !date.isBefore(start) && !date.isAfter(end)

    companion object {
        private val DOT = DateTimeFormatter.ofPattern("yyyy.MM.dd")

        fun of(kind: LedgerPeriodKind, today: LocalDate = LocalDate.now()): LedgerPeriod = when (kind) {
            LedgerPeriodKind.THIS_MONTH -> YearMonth.from(today).let { LedgerPeriod(kind, it.atDay(1), it.atEndOfMonth()) }
            LedgerPeriodKind.LAST_MONTH -> YearMonth.from(today).minusMonths(1).let { LedgerPeriod(kind, it.atDay(1), it.atEndOfMonth()) }
            LedgerPeriodKind.TAX_YEAR -> LedgerPeriod(kind, LocalDate.of(today.year, 1, 1), LocalDate.of(today.year, 12, 31))
        }
    }
}

/** 별지 서식 한 줄 (정정 반영값) */
data class LedgerRow(
    val record: TripRecord,
    val date: LocalDate,
    val dept: String,
    val userName: String,
    val distanceKm: Double,
    val commuteKm: Double?,
    val businessKm: Double?,
    val remark: String,
)

data class LedgerTotals(val totalKm: Double, val businessUseKm: Double) {
    /** ⑬ 업무사용비율 (%, 소수 첫째 자리) */
    val ratioPercent: Double get() = if (totalKm > 0) Math.round(businessUseKm / totalKm * 1000) / 10.0 else 0.0
}

/**
 * rows·합계는 확정 운행만 (확정 기록만 제출한다). 정정이 있으면 정정 반영값.
 * pending: 기간 안의 미확정 운행 (rows·합계에서 제외, 화면에 따로 표시)
 * anomalies: 기간 안의 계기판 값 이상 운행 (거리를 알 수 없어 rows·합계에서 제외)
 */
class Ledger(
    val profile: LedgerProfile,
    val period: LedgerPeriod,
    val rows: List<LedgerRow>,
    val anomalies: List<TripRecord> = emptyList(),
    val pending: List<TripRecord> = emptyList(),
) {
    val totals = LedgerTotals(round1(rows.sumOf { it.distanceKm }), round1(rows.sumOf { (it.commuteKm ?: 0.0) + (it.businessKm ?: 0.0) }))
    val corrections = rows.flatMap { it.record.corrections }
    val pendingKm = round1(pending.sumOf { it.trip.distanceKm ?: 0.0 })

    companion object {
        fun build(records: List<TripRecord>, profile: LedgerProfile, period: LedgerPeriod, zone: ZoneId = ZoneId.systemDefault()): Ledger {
            fun inPeriod(record: TripRecord) = Instant.ofEpochMilli(record.trip.startedAt).atZone(zone).toLocalDate() in period
            val rows = records
                .filter { it.trip.endedAt != null && it.trip.isConfirmed && it.trip.distanceKm != null }
                .map { it to Instant.ofEpochMilli(it.trip.startedAt).atZone(zone).toLocalDate() }
                .filter { (_, date) -> date in period }
                .sortedBy { (record, _) -> record.trip.startedAt }
                .map { (record, date) ->
                    val km = record.trip.distanceKm!!
                    LedgerRow(
                        record = record,
                        date = date,
                        dept = record.trip.userDept ?: profile.dept,
                        userName = record.trip.userName ?: profile.userName,
                        distanceKm = km,
                        commuteKm = km.takeIf { record.effectiveMode == TripMode.COMMUTE },
                        businessKm = km.takeIf { record.effectiveMode == TripMode.BUSINESS },
                        remark = remarkOf(record),
                    )
                }
            val anomalies = records.filter { it.trip.odometerAnomaly && inPeriod(it) }
            val pending = records.filter { it.trip.endedAt != null && !it.trip.isConfirmed && inPeriod(it) }
            return Ledger(profile, period, rows, anomalies, pending)
        }

        /** ⑩ 비고: [구분] 업무 목적 · 방문처 (정정 n건) */
        private fun remarkOf(record: TripRecord): String = buildString {
            append("[${record.effectiveMode?.label ?: "미분류"}]")
            val detail = listOf(record.effectivePurpose, record.effectiveVisitPlace).filter { it.isNotBlank() }.joinToString(" · ")
            if (detail.isNotEmpty()) append(" $detail")
            if (record.trip.recordGap) append(" ($RECORD_GAP_LABEL)")
            if (record.corrections.isNotEmpty()) append(" (정정 ${record.corrections.size}건)")
        }
    }
}

/**
 * 국세청 별지 서식(업무용승용차 운행기록부, 2022.4.1 개정) 레이아웃의 xlsx.
 * 칸 배치는 demo/lib/ledgerSheet.ts, 서식은 demo/lib/exportXlsx.ts와 같다.
 * 열: A ③ · B·C ④(부서/성명) · D ⑤ · E ⑥ · F ⑦ · G ⑧ · H ⑨ · I ⑩
 */
object LedgerExporter {
    private const val KM = "#,##0.0"
    private val COL_WIDTHS = listOf(16.0, 10.0, 10.0, 15.0, 15.0, 12.0, 12.0, 12.0, 34.0)
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd(E)", Locale.KOREA)
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd(E) HH:mm", Locale.KOREA)

    private enum class Style { TITLE, SECTION, HEAD, BLANK, VALUE, NOTE }
    private enum class Fmt { KM, PCT }

    fun fileName(ledger: Ledger) = "운행기록부_${ledger.profile.plate.replace(Regex("\\s"), "")}_${ledger.period.fileSuffix}.xlsx"

    fun write(ledger: Ledger, dir: File, zone: ZoneId = ZoneId.systemDefault()): File {
        dir.mkdirs()
        val file = File(dir, fileName(ledger))
        file.outputStream().use { out ->
            val wb = Workbook(out, "BizDriveLog", "1.0")
            wb.properties().setTitle("업무용승용차 운행기록부").setCompany(ledger.profile.company)
            writeLedgerSheet(wb.newWorksheet("운행기록부"), ledger)
            writeCorrectionSheet(wb.newWorksheet("정정 이력"), ledger, zone)
            wb.finish()
        }
        return file
    }

    private fun writeLedgerSheet(ws: Worksheet, ledger: Ledger) {
        ws.pageOrientation("landscape")
        ws.paperSize(PaperSize.A4_PAPER)
        ws.setFitToPage(true)
        ws.fitToWidth(1.toShort())
        COL_WIDTHS.forEachIndexed { i, w -> ws.width(i, w) }
        val p = ledger.profile
        fun add(range: String, value: Any?, style: Style, fmt: Fmt? = null, align: String? = null, bold: Boolean = false) =
            cell(ws, range, value, style, fmt, align, bold)

        // 상단: 왼쪽 과세기간, 가운데 제목, 오른쪽 상호명·사업자등록번호
        add("A1:C1", "과세기간", Style.HEAD)
        add("A2:C2", ledger.period.formText, Style.VALUE)
        add("D1:G2", "업무용승용차 운행기록부", Style.TITLE)
        add("H1", "상호명", Style.HEAD)
        add("I1", p.company, Style.VALUE)
        add("H2", "사업자등록번호", Style.HEAD)
        add("I2", p.bizNo, Style.VALUE)

        add("A4", "1. 기본정보", Style.SECTION)
        add("A5:D5", "①차종", Style.HEAD)
        add("E5:I5", "②자동차등록번호", Style.HEAD)
        add("A6:D6", p.carModel, Style.VALUE)
        add("E6:I6", p.plate, Style.VALUE)

        add("A8", "2. 업무용 사용비율 계산", Style.SECTION)
        add("A9:A11", "③사용일자\n(요일)", Style.HEAD)
        add("B9:C10", "④사용자", Style.HEAD)
        add("B11", "부서", Style.HEAD)
        add("C11", "성명", Style.HEAD)
        add("D9:I9", "운행 내역", Style.HEAD)
        add("D10:D11", "⑤주행 전\n계기판의 거리(㎞)", Style.HEAD)
        add("E10:E11", "⑥주행 후\n계기판의 거리(㎞)", Style.HEAD)
        add("F10:F11", "⑦주행거리(㎞)", Style.HEAD)
        add("G10:H10", "업무용 사용거리(㎞)", Style.HEAD)
        add("G11", "⑧출·퇴근용(㎞)", Style.HEAD)
        add("H11", "⑨일반 업무용(㎞)", Style.HEAD)
        add("I10:I11", "⑩비고", Style.HEAD)

        var r = 12
        for (row in ledger.rows) {
            val trip = row.record.trip
            add("A$r", row.date.format(DAY), Style.VALUE)
            add("B$r", row.dept, Style.VALUE)
            add("C$r", row.userName, Style.VALUE)
            // ⑤⑥은 원본 계기판 값 (정정 대상 아님)
            listOf("D" to trip.startOdometerKm, "E" to trip.endOdometerKm, "F" to row.distanceKm, "G" to row.commuteKm, "H" to row.businessKm)
                .forEach { (col, v) -> add("$col$r", v, Style.VALUE, Fmt.KM, "right") }
            add("I$r", row.remark, Style.VALUE, align = "left")
            r++
        }

        // 하단 집계: ③④ 자리는 회색 빈칸, ⑪=⑤~⑦ 폭, ⑫=⑧⑨ 폭, ⑬=⑩ 폭
        val tl = r
        val tv = r + 1
        add("A$tl:C$tv", null, Style.BLANK)
        add("D$tl:F$tl", "⑪과세기간 총주행거리(㎞)", Style.HEAD)
        add("G$tl:H$tl", "⑫과세기간 업무용 사용거리(㎞)", Style.HEAD)
        add("I$tl", "⑬업무사용비율(⑫/⑪)", Style.HEAD)
        add("D$tv:F$tv", ledger.totals.totalKm, Style.VALUE, Fmt.KM, bold = true)
        add("G$tv:H$tv", ledger.totals.businessUseKm, Style.VALUE, Fmt.KM, bold = true)
        add("I$tv", ledger.totals.ratioPercent / 100, Style.VALUE, Fmt.PCT, bold = true)

        var foot = tv + 2
        if (ledger.corrections.isNotEmpty()) {
            add("A$foot", "※ 정정 ${ledger.corrections.size}건 반영값입니다. 원본 값과 정정 내역은 '정정 이력' 시트를 참조하세요.", Style.NOTE)
            foot++
        }
        if (ledger.pending.isNotEmpty()) {
            add("A$foot", "※ 미확정 운행 ${ledger.pending.size}건은 제외했습니다. 확정한 뒤 다시 내보내면 포함됩니다.", Style.NOTE)
            foot++
        }
        add("A$foot", "파일 식별값: ${fileId(ledger)}  (이 파일에 담긴 운행·정정 이력으로 계산한 값 · 같은 기록에서 내보낸 파일인지 구분하는 용도이며 위변조를 증명하지는 않습니다)", Style.NOTE)

        listOf(1, 2).forEach { ws.rowHeight(it - 1, 22.0) }
        listOf(9, 10, 11, tl).forEach { ws.rowHeight(it - 1, 24.0) }
    }

    private fun writeCorrectionSheet(ws: Worksheet, ledger: Ledger, zone: ZoneId) {
        listOf(8.0, 10.0, 16.0, 10.0, 40.0, 40.0, 20.0).forEachIndexed { i, w -> ws.width(i, w) }
        listOf("정정번호", "운행번호", "정정일시", "항목", "정정 전(원본)", "정정 후", "사유").forEachIndexed { i, h ->
            ws.value(0, i, h)
            ws.style(0, i).bold().borderStyle("thin").borderColor("808080").fillColor("EFEFEF").set()
        }
        ledger.corrections.forEachIndexed { i, c ->
            val field = when (c.field) {
                CorrectionField.MODE -> "구분"
                CorrectionField.PURPOSE -> "⑩비고(목적)"
                CorrectionField.VISIT_PLACE -> "⑩비고(방문처)"
            }
            val stamp = Instant.ofEpochMilli(c.createdAt).atZone(zone).format(STAMP)
            listOf<Any>(c.id, c.tripId, stamp, field, c.displayValue(c.before), c.displayValue(c.after), c.reason).forEachIndexed { j, v ->
                if (v is Number) ws.value(i + 1, j, v) else ws.value(i + 1, j, v.toString())
                ws.style(i + 1, j).borderStyle("thin").borderColor("808080").set()
            }
        }
        if (ledger.corrections.isEmpty()) ws.value(1, 0, "정정 이력 없음")
        ws.value(ledger.corrections.size + 2, 0, "확정된 운행의 원본 값은 수정되지 않으며, 변경은 이 시트에 이력으로만 추가됩니다.")
    }

    /** 칸 하나를 쓴다. 병합 범위 전체에 같은 스타일(테두리 포함)을 줘서 병합 셀 테두리가 끊기지 않게 한다. */
    private fun cell(ws: Worksheet, range: String, value: Any?, style: Style, fmt: Fmt?, align: String?, bold: Boolean) {
        val (r0, c0, r1, c1) = parse(range)
        when (value) {
            is Number -> ws.value(r0, c0, value)
            is String -> ws.value(r0, c0, value)
        }
        if (style == Style.SECTION || style == Style.NOTE) {
            val s = ws.style(r0, c0).fontSize(if (style == Style.SECTION) 11 else 10)
            if (style == Style.SECTION) s.bold().set() else s.fontColor("444444").set()
            return
        }
        val rng = ws.range(r0, c0, r1, c1)
        if (r0 != r1 || c0 != c1) rng.merge()
        if (style == Style.TITLE) {
            rng.style().bold().fontSize(16).horizontalAlignment("center").verticalAlignment("center").set()
            return
        }
        var s = rng.style().borderStyle("thin").borderColor("808080")
            .horizontalAlignment(align ?: "center").verticalAlignment("center").wrapText(align == null)
        if (style == Style.HEAD || style == Style.BLANK) s = s.bold().fontSize(10).fillColor(if (style == Style.BLANK) "D9D9D9" else "EFEFEF")
        if (bold) s = s.bold()
        when (fmt) {
            Fmt.KM -> s = s.format(KM)
            Fmt.PCT -> s = s.format("0.0%")
            null -> Unit
        }
        s.set()
    }

    /** "A1" 또는 "A1:C2" → 0부터 시작하는 (r0, c0, r1, c1) */
    private fun parse(range: String): List<Int> {
        val parts = range.split(":")
        fun ref(s: String) = Pair(s.drop(1).toInt() - 1, s[0] - 'A')
        val (r0, c0) = ref(parts[0])
        val (r1, c1) = ref(parts.getOrElse(1) { parts[0] })
        return listOf(r0, c0, r1, c1)
    }

    /** 파일 식별값: 머리 정보 + 원본 운행 + 정정 이력의 SHA-256 앞 64비트. 파일 구분용이며 위변조 증명 수단이 아님 */
    fun fileId(ledger: Ledger): String {
        val p = ledger.profile
        val payload = buildString {
            append(listOf(p.company, p.bizNo, p.carModel, p.plate, ledger.period.formText).joinToString("|")).append('\n')
            ledger.rows.forEach { row ->
                val t = row.record.trip
                append(listOf(t.id, t.startedAt, t.endedAt, t.startOdometerKm, t.endOdometerKm, t.mode, t.purpose, t.visitPlace, t.confirmedAt, row.dept, row.userName).joinToString("|"))
                if (t.recordGap) append("|gap")
                append('\n')
            }
            ledger.corrections.forEach { c ->
                append(listOf(c.id, c.tripId, c.field, c.before, c.after, c.reason, c.createdAt).joinToString("|")).append('\n')
            }
        }
        val hex = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02X".format(it) }
        return "BDL-${hex.substring(0, 4)}-${hex.substring(4, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}"
    }
}

private fun round1(km: Double) = Math.round(km * 10) / 10.0
