package app.bizdrivelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.bizdrivelog.data.Correction
import app.bizdrivelog.data.CorrectionField
import app.bizdrivelog.data.FieldView
import app.bizdrivelog.data.TripActions
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.RECORD_GAP_LABEL
import app.bizdrivelog.data.TripRecord
import app.bizdrivelog.data.displayValue
import app.bizdrivelog.voice.VoicePhase
import java.util.Date

private val PURPOSE_PRESETS = listOf("거래처 방문", "거래처 미팅", "회의", "납품·배송", "출장", "교육·행사")
private val REASON_PRESETS = listOf("비고 누락 보완", "운행 모드 오분류", "목적 입력 오류")

/**
 * 분류 카드: 분류 → (업무면) 목적 → 방문처 → 확정. 위에서 아래로 한 흐름.
 * 음성 응답이 들어오면 modeBy가 바뀌므로 그 값을 키로 다시 채운다 (확정은 사용자가 누른다).
 * voice == null 이면 음성 꺼짐.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(record: TripRecord, position: Int, count: Int, voice: VoicePhase?, actions: HomeActions, onBack: () -> Unit) {
    val trip = record.trip
    var mode by remember(trip.id, trip.modeBy) { mutableStateOf(trip.mode) }
    var modeBy by remember(trip.id, trip.modeBy) { mutableStateOf(trip.modeBy) }
    var purpose by remember(trip.id, trip.modeBy) { mutableStateOf(trip.purpose) }
    var visit by remember(trip.id, trip.modeBy) { mutableStateOf(trip.visitPlace) }
    fun save() = mode?.let { actions.classify(trip, it, modeBy, purpose, visit) }

    Column(
        Modifier.fillMaxSize().padding(horizontal = Dim.Gutter, vertical = 28.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ScreenHeader("운행 확인", onBack) {
            if (count > 1) Text("$position / $count", style = Type.BodyL, color = Palette.TextSub)
        }
        Text(
            "${DATE_SHORT.format(Date(trip.startedAt))} ${timeRange(trip)}  ·  ${routeText(trip)}  ·  ${distanceText(trip) ?: "-"}",
            style = Type.TitleS,
        )
        if (trip.recordGap) {
            Text("$RECORD_GAP_LABEL: 앱이 멈춘 동안 계기판이 늘어난 구간입니다. 시각은 정확하지 않습니다. 분류를 확인해 주세요", style = Type.BodyM, color = Palette.Pending)
        }
        if (voice != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    voiceText(voice) ?: "음성으로 답하면 아래가 채워집니다 (예: 거래처 미팅, 개인 용무, 퇴근)",
                    style = Type.BodyM,
                    color = if (voice == VoicePhase.Idle) Palette.TextSub else Palette.Text,
                    modifier = Modifier.weight(1f),
                )
                if (voice == VoicePhase.Idle || voice is VoicePhase.Heard) BigButton("음성으로 답하기", { actions.askVoice(trip) })
            }
        }

        Step("분류")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TripMode.entries.forEach { m ->
                BigChip(m.label, m == mode, m.color, onClick = {
                    if (m != mode) {
                        mode = m
                        modeBy = "정차 중 화면에서 선택"
                        purpose = ""
                        visit = ""
                        save()
                    }
                }, modifier = Modifier.width(200.dp))
            }
        }
        Text(
            "분류 근거: ${modeBy.ifEmpty { "없음 — 분류를 선택해 주세요" }}" +
                if (mode == TripMode.PERSONAL) "  ·  업무사용비율 제외 · 위치 미저장 (거리만 기록)" else "",
            style = Type.BodyS,
            color = Palette.TextSub,
        )

        if (mode == TripMode.BUSINESS) {
            Step("목적")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PURPOSE_PRESETS.forEach { preset ->
                    BigChip(preset, purpose == preset, Palette.Business, onClick = { purpose = preset; save() })
                }
            }
            BigField(purpose, { purpose = it; save() }, "목적 (위에서 고르거나 직접 입력)", Modifier.fillMaxWidth())
            Step("방문처")
            BigField(visit, { visit = it; save() }, "방문처 (거래처·장소 이름)", Modifier.fillMaxWidth())
        }

        val canConfirm = TripActions.canConfirm(mode, purpose)
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigButton(
                "운행 확정",
                { save(); actions.confirm(trip) },
                color = mode?.color,
                enabled = canConfirm,
                height = Dim.ButtonL,
                modifier = Modifier.width(420.dp),
            )
            Spacer(Modifier.width(28.dp))
            Text(
                when {
                    mode == null -> "분류를 고르면 확정할 수 있습니다"
                    !canConfirm -> "업무 운행은 목적을 입력해야 확정할 수 있습니다"
                    else -> "확정하면 원본은 잠기고, 이후 변경은 정정 이력으로만 남습니다"
                },
                style = Type.BodyS,
                color = if (canConfirm) Palette.TextSub else Palette.Pending,
            )
        }
    }
}

@Composable
private fun Step(title: String) = Text(title, style = Type.Label, color = Palette.TextSub)

/** 운행 상세: 계기판 원본 값, 분류 근거, 정정 이력. 확정 운행은 여기서 정정 요청 */
@Composable
fun DetailScreen(record: TripRecord, onBack: () -> Unit, onCorrect: (TripRecord, TripMode, String, String, String) -> Unit) {
    val trip = record.trip
    var correcting by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(horizontal = Dim.Gutter, vertical = 28.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ScreenHeader("운행 #${trip.id}", onBack) {
            ConfirmState(trip.isConfirmed)
            if (trip.isConfirmed) BigButton("정정 요청", { correcting = true })
        }
        Panel {
            if (trip.recordGap) Text("$RECORD_GAP_LABEL (서비스 중단): 시각은 정확하지 않고 계기판 값은 원본입니다", style = Type.BodyM, color = Palette.Pending)
            InfoRow("날짜" to DATE.format(Date(trip.startedAt)), "시각" to timeRange(trip))
            InfoRow("구간" to routeText(trip), "주행거리" to (distanceText(trip) ?: "-"))
            InfoRow(
                "주행 전 계기판" to formatKm(trip.startOdometerKm),
                "주행 후 계기판" to (trip.endOdometerKm?.let { formatKm(it) } ?: "-"),
            )
        }
        Panel {
            // 구분·목적·방문처: 현재 값(정정 반영)을 크게, 그 항목이 정정됐으면 "정정됨"과 원본을 작게 아래에
            record.fieldViews.forEach { view -> CorrectableRow(view) }
            InfoRow("분류 근거" to trip.modeBy.ifBlank { "-" }, "확정" to (trip.confirmedAt?.let { DATE_TIME.format(Date(it)) } ?: "미확정"))
            InfoRow("사용자" to listOfNotNull(trip.userDept, trip.userName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "-" }, "" to "")
        }
        Text("정정 이력 ${record.corrections.size}건", style = Type.TitleS)
        Text("확정된 원본 값은 바뀌지 않고, 정정은 이력으로만 추가됩니다. 엑셀·집계에는 정정 반영값을 씁니다.", style = Type.BodyS, color = Palette.TextSub)
        if (record.corrections.isEmpty()) Text("정정 이력 없음", style = Type.BodyM, color = Palette.TextSub)
        record.corrections.forEach { CorrectionItem(it) }
    }
    if (correcting) {
        CorrectionDialog(
            record = record,
            onDismiss = { correcting = false },
            onSave = { mode, purpose, visit, reason ->
                onCorrect(record, mode, purpose, visit, reason)
                correcting = false
            },
        )
    }
}

@Composable
private fun CorrectableRow(view: FieldView) {
    val mode = view.current.takeIf { view.field == CorrectionField.MODE && it.isNotEmpty() }?.let { TripMode.valueOf(it) }
    fun shown(value: String) = when {
        view.field == CorrectionField.MODE -> value.takeIf { it.isNotEmpty() }?.let { TripMode.valueOf(it).label } ?: "미분류"
        else -> value.ifBlank { "-" }
    }
    Row {
        Text(view.field.label, style = Type.Label, color = Palette.TextSub, modifier = Modifier.width(240.dp).padding(top = 10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shown(view.current),
                    style = if (view.field == CorrectionField.MODE) Type.TitleM else Type.TitleS,
                    color = when {
                        view.field != CorrectionField.MODE -> Palette.Text
                        else -> mode?.color ?: Palette.Pending
                    },
                )
                if (view.corrected) {
                    Spacer(Modifier.width(20.dp))
                    Text("정정됨", style = Type.Label, color = Palette.Pending)
                }
            }
            view.original?.let { Text("원본: ${shown(it)}", style = Type.BodyS, color = Palette.TextSub) }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.RadiusL)).background(Palette.Surface).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) { content() }
}

@Composable
private fun InfoRow(left: Pair<String, String>, right: Pair<String, String>) {
    Row {
        listOf(left, right).forEach { (label, value) ->
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = Type.Label, color = Palette.TextSub, modifier = Modifier.width(240.dp))
                Text(value, style = Type.BodyL, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CorrectionItem(c: Correction) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.RadiusM)).background(Palette.Surface).padding(horizontal = 32.dp, vertical = 20.dp)) {
        Text("#${c.id} · ${DATE_TIME.format(Date(c.createdAt))} · ${c.field.label}", style = Type.Label, color = Palette.TextSub)
        Text("“${c.displayValue(c.before)}” → “${c.displayValue(c.after)}”", style = Type.BodyL)
        Text("사유: ${c.reason}", style = Type.BodyS, color = Palette.TextSub)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CorrectionDialog(
    record: TripRecord,
    onDismiss: () -> Unit,
    onSave: (mode: TripMode, purpose: String, visitPlace: String, reason: String) -> Unit,
) {
    var mode by remember { mutableStateOf(record.effectiveMode ?: TripMode.BUSINESS) }
    var purpose by remember { mutableStateOf(record.effectivePurpose) }
    var visit by remember { mutableStateOf(record.effectiveVisitPlace) }
    var reason by remember { mutableStateOf("") }
    val changed = mode != record.effectiveMode || purpose.trim() != record.effectivePurpose || visit.trim() != record.effectiveVisitPlace

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(1200.dp)
                .clip(RoundedCornerShape(Dim.RadiusL))
                .background(Palette.Surface)
                .padding(40.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("운행 #${record.trip.id} 정정 요청", style = Type.TitleM)
            Text("원본 값은 바뀌지 않고 정정 이력이 추가됩니다. 계기판 거리는 정정할 수 없습니다.", style = Type.BodyS, color = Palette.TextSub)
            Step("분류")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TripMode.entries.forEach { m -> BigChip(m.label, m == mode, m.color, { mode = m }, Modifier.width(200.dp)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BigField(purpose, { purpose = it }, "목적", Modifier.weight(1f))
                BigField(visit, { visit = it }, "방문처", Modifier.weight(1f))
            }
            Step("정정 사유 (필수)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                REASON_PRESETS.forEach { preset -> BigChip(preset, reason == preset, Palette.Text, { reason = preset }) }
            }
            BigField(reason, { reason = it }, "사유 직접 입력", Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                BigButton("취소", onDismiss)
                BigButton("정정 이력 추가", { onSave(mode, purpose, visit, reason) }, color = Palette.Text, enabled = changed && reason.isNotBlank())
                if (reason.isBlank()) Text("사유를 입력해야 정정할 수 있습니다", style = Type.BodyS, color = Palette.Pending)
            }
        }
    }
}
