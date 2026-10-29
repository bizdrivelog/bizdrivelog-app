package app.bizdrivelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
) {
    // 서버는 이 화면이 떠 있는 동안만 (화면을 벗어나거나 주행 화면으로 바뀌면 종료)
    DisposableEffect(Unit) { onDispose(onCloseQr) }
    var kind by remember { mutableStateOf(LedgerPeriodKind.THIS_MONTH) }
    val ledger = Ledger.build(records, profile, LedgerPeriod.of(kind))

    if (qrState != QrState.Idle) {
        QrPanel(qrState, state.file, onRetry = { state.file?.let(onStartQr) }, onClose = onCloseQr)
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 운행기록", fontSize = 22.sp) }
            Spacer(Modifier.width(16.dp))
            Text("운행기록부 내보내기", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
        Text("국세청 별지 서식(업무용승용차 운행기록부) 그대로 엑셀(.xlsx)로 만듭니다. 두 번째 시트는 정정 이력입니다.", fontSize = 20.sp, color = Muted, modifier = Modifier.padding(vertical = 12.dp))

        Text("기간", fontSize = 22.sp, color = Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            LedgerPeriodKind.entries.forEach { k ->
                val label = LedgerPeriod.of(k).label
                val modifier = Modifier.height(60.dp)
                if (k == kind) Button(onClick = { kind = k }, modifier = modifier) { Text(label, fontSize = 22.sp) }
                else OutlinedButton(onClick = { kind = k }, modifier = modifier) { Text(label, fontSize = 22.sp) }
            }
        }

        Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text("과세기간 칸: ${ledger.period.formText}", fontSize = 22.sp)
                Text(
                    "운행 ${ledger.rows.size}건" + (if (ledger.unconfirmed > 0) " (미확정 ${ledger.unconfirmed}건 포함)" else "") +
                        " · 정정 ${ledger.corrections.size}건",
                    fontSize = 22.sp,
                )
                if (ledger.anomalies.isNotEmpty()) {
                    Text("$ODOMETER_ANOMALY ${ledger.anomalies.size}건 제외 (종료 누적거리가 시작보다 작음)", fontSize = 19.sp, color = Warn)
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Total("⑪ 총주행거리", formatKm(ledger.totals.totalKm))
                    Total("⑫ 업무용 사용거리 (⑧+⑨)", formatKm(ledger.totals.businessUseKm))
                    Total("⑬ 업무사용비율 (⑫/⑪)", String.format(Locale.KOREA, "%.1f%%", ledger.totals.ratioPercent))
                }
                if (profile.missing.isNotEmpty()) {
                    Text("머리 정보 미입력: ${profile.missing.joinToString(", ")} (설정에서 입력)", fontSize = 19.sp, color = Warn, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onExport(ledger) }, enabled = ledger.rows.isNotEmpty(), modifier = Modifier.height(64.dp)) {
                Text("엑셀 파일 만들기", fontSize = 24.sp)
            }
            Spacer(Modifier.width(16.dp))
            state.file?.let { file ->
                Button(onClick = { onStartQr(file) }, modifier = Modifier.height(64.dp)) { Text("휴대폰으로 받기", fontSize = 24.sp) }
                // 차량에 공유를 받을 앱이 있을 때만
                if (canShare) {
                    Spacer(Modifier.width(16.dp))
                    OutlinedButton(onClick = { onShare(file) }, modifier = Modifier.height(64.dp)) { Text("보내기", fontSize = 24.sp) }
                }
            }
        }
        state.file?.let { Text("저장됨 (앱 전용 저장소): ${it.name}", fontSize = 20.sp, color = Accent, modifier = Modifier.padding(top = 16.dp)) }
        state.message?.let { Text(it, fontSize = 20.sp, color = Warn, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun Total(label: String, value: String) {
    Column(Modifier.padding(end = 48.dp)) {
        Text(label, fontSize = 18.sp, color = Muted)
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    }
}
