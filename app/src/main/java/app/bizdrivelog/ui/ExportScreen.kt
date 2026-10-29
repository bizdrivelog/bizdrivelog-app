package app.bizdrivelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.bizdrivelog.data.LedgerProfile
import app.bizdrivelog.data.TripRecord
import app.bizdrivelog.export.Ledger
import app.bizdrivelog.export.LedgerPeriod
import app.bizdrivelog.export.LedgerPeriodKind
import app.bizdrivelog.export.QrState
import java.io.File
import java.util.Locale

/** 마지막으로 만든 파일과 보내기 결과 */
data class ExportState(val file: File? = null, val message: String? = null)

@Composable
fun ExportScreen(
    records: List<TripRecord>,
    profile: LedgerProfile,
    state: ExportState,
    qrState: QrState,
    canShare: Boolean,
    onExport: (Ledger) -> Unit,
    onShare: (File) -> Unit,
    onStartQr: (File) -> Unit,
    onCloseQr: () -> Unit,
    onBack: () -> Unit,
    showAddress: Boolean = false,
) {
    // 서버는 이 화면이 떠 있는 동안만 (화면을 벗어나거나 주행 화면으로 바뀌면 종료)
    DisposableEffect(Unit) { onDispose(onCloseQr) }
    var kind by remember { mutableStateOf(LedgerPeriodKind.THIS_MONTH) }
    val ledger = Ledger.build(records, profile, LedgerPeriod.of(kind))

    if (qrState != QrState.Idle) {
        QrPanel(qrState, state.file, showAddress, onRetry = { state.file?.let(onStartQr) }, onClose = onCloseQr)
        return
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = Dim.Gutter, vertical = 28.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ScreenHeader("운행기록부 내보내기", onBack)
        Text("국세청 별지 서식(업무용승용차 운행기록부) 그대로 엑셀(.xlsx)로 만듭니다. 두 번째 시트는 정정 이력입니다.", style = Type.BodyS, color = Palette.TextSub)

        Text("기간", style = Type.Label, color = Palette.TextSub)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LedgerPeriodKind.entries.forEach { k -> BigChip(LedgerPeriod.of(k).label, k == kind, Palette.Text, { kind = k }) }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.RadiusL)).background(Palette.Surface).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("과세기간 칸: ${ledger.period.formText}", style = Type.BodyM)
            Text(
                "운행 ${ledger.rows.size}건" + (if (ledger.unconfirmed > 0) " (미확정 ${ledger.unconfirmed}건 포함)" else "") +
                    " · 정정 ${ledger.corrections.size}건",
                style = Type.BodyM,
                color = if (ledger.unconfirmed > 0) Palette.Pending else Palette.Text,
            )
            if (ledger.anomalies.isNotEmpty()) {
                Text("$ODOMETER_ANOMALY ${ledger.anomalies.size}건 제외 (종료 누적거리가 시작보다 작음)", style = Type.BodyS, color = Palette.Pending)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                Total("⑪ 총주행거리", formatKm(ledger.totals.totalKm))
                Total("⑫ 업무용 사용거리 (⑧+⑨)", formatKm(ledger.totals.businessUseKm))
                Total("⑬ 업무사용비율 (⑫/⑪)", String.format(Locale.KOREA, "%.1f%%", ledger.totals.ratioPercent))
            }
            if (profile.missing.isNotEmpty()) {
                Text("머리 정보 미입력: ${profile.missing.joinToString(", ")} (설정에서 입력)", style = Type.BodyS, color = Palette.Pending)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigButton("엑셀 파일 만들기", { onExport(ledger) }, enabled = ledger.rows.isNotEmpty(), height = Dim.ButtonL)
            state.file?.let { file ->
                BigButton("휴대폰으로 받기", { onStartQr(file) }, color = Palette.Text, height = Dim.ButtonL)
                // 차량에 공유를 받을 앱이 있을 때만
                if (canShare) BigButton("보내기", { onShare(file) }, height = Dim.ButtonL)
            }
        }
        state.file?.let { Text("저장됨 (앱 전용 저장소): ${it.name}", style = Type.BodyS, color = Palette.TextSub) }
        state.message?.let { Text(it, style = Type.BodyM, color = Palette.Pending) }
    }
}

@Composable
private fun Total(label: String, value: String) {
    Column {
        Text(label, style = Type.Label, color = Palette.TextSub)
        Text(value, style = Type.TitleM)
    }
}
