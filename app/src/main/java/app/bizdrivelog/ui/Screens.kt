package app.bizdrivelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.bizdrivelog.data.Correction
import app.bizdrivelog.data.Place
import app.bizdrivelog.data.PlaceKind
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import app.bizdrivelog.data.displayValue
import app.bizdrivelog.drive.DriveStatus
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private val DrivingGreen = Color(0xFF4CD787)
private val Muted = Color(0xFFA0A4AB)
private val Accent = Color(0xFF36D1F0)
private val Warn = Color(0xFFF6B93B)

private val PURPOSE_PRESETS = listOf("거래처 방문", "회의", "납품·배송", "출장", "교육·행사")
private val REASON_PRESETS = listOf("비고 누락 보완", "운행 모드 오분류", "목적 입력 오류")

class HomeActions(
    val classify: (trip: Trip, mode: TripMode, modeBy: String, purpose: String, visitPlace: String) -> Unit,
    val confirm: (Trip) -> Unit,
    val correct: (record: TripRecord, mode: TripMode, purpose: String, visitPlace: String, reason: String) -> Unit,
    val openSettings: () -> Unit,
)

@Composable
fun BizDriveLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
    }
}

/** 주행 중 최소 화면: 운행 중 표시와 이번 운행 주행거리만. 입력 UI 없음. */
@Composable
fun DrivingScreen(status: DriveStatus) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("운행 중", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = DrivingGreen)
        Spacer(Modifier.height(24.dp))
        Text(status.tripDistanceKm?.let { formatKm(it) } ?: "-", fontSize = 96.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        Text("주행 중에는 조작 없이 기록만 합니다 · 확인은 정차(P) 후", fontSize = 22.sp, color = Muted)
    }
}

@Composable
fun HomeScreen(status: DriveStatus, records: List<TripRecord>, actions: HomeActions) {
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var correcting by remember { mutableStateOf<TripRecord?>(null) }
    val pending = records.firstOrNull { it.trip.id == selectedId && !it.trip.isConfirmed && it.trip.endedAt != null }
        ?: records.firstOrNull { !it.trip.isConfirmed && it.trip.endedAt != null }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
        item { StatusBar(status, actions.openSettings) }
        item { MonthSummary(records) }
        // 분류 확인 카드는 정차 중에만 (운행 중이면 아직 종료 전이라 카드 대상이 없음)
        if (pending != null && !status.isDriving) {
            item(key = "card-${pending.trip.id}") { TripCard(pending.trip, actions) }
        }
        item { TripHeader() }
        if (records.isEmpty()) {
            item { Text("운행 기록이 없습니다. 시동을 걸면(기어 D) 자동으로 기록이 시작됩니다.", fontSize = 22.sp, color = Muted, modifier = Modifier.padding(top = 20.dp)) }
        }
        items(records, key = { it.trip.id }) { record ->
            TripRow(
                record,
                onCheck = { selectedId = record.trip.id },
                onCorrect = { correcting = record },
            )
            HorizontalDivider()
        }
    }
    correcting?.let { record ->
        CorrectionDialog(
            record = record,
            onDismiss = { correcting = null },
            onSave = { mode, purpose, visit, reason ->
                actions.correct(record, mode, purpose, visit, reason)
                correcting = null
            },
        )
    }
}

@Composable
private fun StatusBar(status: DriveStatus, onSettings: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (status.isDriving) "운행 중" else "주차",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = if (status.isDriving) DrivingGreen else MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(32.dp))
        Text("계기판 누적거리  ", fontSize = 22.sp, color = Muted)
        Text(status.odometerKm?.let { formatKm(it) } ?: "-", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onSettings) { Text("장소 설정", fontSize = 20.sp) }
    }
}

/** 이번 달 요약. 업무용 사용거리 = 출퇴근 + 업무 (법인세법 시행규칙 제27조의2 ⑦). 정정 반영값으로 집계. */
@Composable
private fun MonthSummary(records: List<TripRecord>) {
    val zone = ZoneId.systemDefault()
    val month = YearMonth.now(zone)
    val done = records.filter {
        it.trip.endedAt != null && YearMonth.from(Instant.ofEpochMilli(it.trip.startedAt).atZone(zone)) == month
    }
    val total = round1(done.sumOf { it.trip.distanceKm ?: 0.0 })
    val business = round1(done.sumOf { it.businessUseKm })
    val ratio = if (total > 0) Math.round(business / total * 1000) / 10.0 else 0.0
    Card(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${month.year}년 ${month.monthValue}월", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(40.dp))
            SummaryItem("총 주행거리", formatKm(total))
            SummaryItem("업무용 사용거리 (출퇴근+업무)", formatKm(business))
            SummaryItem("업무 사용 비율", String.format(Locale.KOREA, "%.1f%%", ratio), Accent)
        }
    }
}

@Composable
private fun SummaryItem(label: String, value: String, color: Color = Color.Unspecified) {
    Column(Modifier.padding(end = 48.dp)) {
        Text(label, fontSize = 18.sp, color = Muted)
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

/** 운행 종료 후 정차 상태에서 띄우는 분류 확인 카드 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TripCard(trip: Trip, actions: HomeActions) {
    var mode by remember(trip.id) { mutableStateOf(trip.mode) }
    var modeBy by remember(trip.id) { mutableStateOf(trip.modeBy) }
    var purpose by remember(trip.id) { mutableStateOf(trip.purpose) }
    var visit by remember(trip.id) { mutableStateOf(trip.visitPlace) }
    fun save() = mode?.let { actions.classify(trip, it, modeBy, purpose, visit) }

    Card(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(24.dp)) {
            Text(
                "방금 운행 #${trip.id}  ·  ${DATE_SHORT.format(Date(trip.startedAt))} ${timeRange(trip)}  ·  ${routeText(trip)}  ·  ${formatKm(trip.distanceKm ?: 0.0)}",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ModeButtons(mode) { selected ->
                        if (selected != mode) {
                            mode = selected
                            modeBy = "정차 중 화면에서 선택"
                            purpose = ""
                            visit = ""
                            save()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "분류 근거: ${modeBy.ifEmpty { "없음 — 모드를 선택해 주세요" }}" +
                            if (mode == TripMode.PERSONAL) "  · 업무사용비율 제외 · 위치 미저장 (거리만 기록)" else "",
                        fontSize = 20.sp,
                        color = Muted,
                    )
                }
                val canConfirm = mode != null && (mode != TripMode.BUSINESS || purpose.isNotBlank())
                Button(onClick = { save(); actions.confirm(trip) }, enabled = canConfirm, modifier = Modifier.height(64.dp)) {
                    Text("운행 확정", fontSize = 24.sp)
                }
            }
            if (mode == TripMode.BUSINESS) {
                Spacer(Modifier.height(16.dp))
                Text("운행 목적", fontSize = 20.sp, color = Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PURPOSE_PRESETS.forEach { preset ->
                        FilterChip(
                            selected = purpose == preset,
                            onClick = { purpose = preset; save() },
                            label = { Text(preset, fontSize = 20.sp, modifier = Modifier.padding(8.dp)) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = purpose,
                        onValueChange = { purpose = it; save() },
                        label = { Text("목적 직접 입력") },
                        textStyle = TextStyle(fontSize = 22.sp),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = visit,
                        onValueChange = { visit = it; save() },
                        label = { Text("방문처") },
                        textStyle = TextStyle(fontSize = 22.sp),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (purpose.isBlank()) Text("업무 운행은 목적을 입력해야 확정할 수 있습니다", fontSize = 18.sp, color = Warn)
            }
        }
    }
}

@Composable
private fun ModeButtons(selected: TripMode?, onSelect: (TripMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TripMode.entries.forEach { mode ->
            val modifier = Modifier.width(150.dp).height(60.dp)
            if (mode == selected) {
                Button(onClick = { onSelect(mode) }, modifier = modifier) { Text(mode.label, fontSize = 24.sp) }
            } else {
                OutlinedButton(onClick = { onSelect(mode) }, modifier = modifier) { Text(mode.label, fontSize = 24.sp) }
            }
        }
    }
}

private val columnWeights = listOf(0.5f, 1.3f, 1.2f, 1.2f, 1.3f, 1.3f, 0.9f, 0.8f, 2f, 0.8f)

@Composable
private fun TripHeader() {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        listOf("", "날짜", "시각", "구간", "주행 전 계기판", "주행 후 계기판", "주행거리", "구분", "목적 · 방문처", "").forEachIndexed { i, text ->
            Cell(text, i, color = Muted, fontSize = 17)
        }
    }
    HorizontalDivider()
}

/** 표의 원본 줄은 바꾸지 않고, 정정 내용은 아래에 "정정 이력" 줄로 붙인다. */
@Composable
private fun TripRow(record: TripRecord, onCheck: () -> Unit, onCorrect: () -> Unit) {
    val trip = record.trip
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(columnWeights[0])) {
                if (trip.isConfirmed) Icon(Icons.Filled.Lock, contentDescription = "확정", tint = DrivingGreen, modifier = Modifier.size(24.dp))
            }
            Cell(DATE.format(Date(trip.startedAt)), 1)
            Cell(timeRange(trip), 2)
            Cell(routeText(trip), 3)
            Cell(formatKm(trip.startOdometerKm), 4, align = TextAlign.End)
            Cell(trip.endOdometerKm?.let { formatKm(it) } ?: "주행 중", 5, align = TextAlign.End)
            Cell(trip.distanceKm?.let { formatKm(it) } ?: "-", 6, align = TextAlign.End, bold = true)
            Cell(trip.mode?.label ?: "-", 7, align = TextAlign.Center)
            Cell(listOf(trip.purpose, trip.visitPlace).filter { it.isNotBlank() }.joinToString(" · "), 8)
            Row(Modifier.weight(columnWeights[9]), horizontalArrangement = Arrangement.End) {
                when {
                    trip.isConfirmed -> TextButton(onClick = onCorrect) { Text("정정", fontSize = 20.sp) }
                    trip.endedAt != null -> TextButton(onClick = onCheck) { Text("확인", fontSize = 20.sp, color = Warn) }
                }
            }
        }
        record.corrections.forEach { CorrectionLine(it) }
    }
}

@Composable
private fun CorrectionLine(c: Correction) {
    Text(
        "↳ 정정 이력 #${c.id} · ${DATE_TIME.format(Date(c.createdAt))} · ${c.field.label}: \"${c.displayValue(c.before)}\" → \"${c.displayValue(c.after)}\" · 사유: ${c.reason} (원본 줄 유지)",
        fontSize = 19.sp,
        color = Warn,
        modifier = Modifier.padding(start = 40.dp, bottom = 10.dp),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(
    text: String,
    index: Int,
    align: TextAlign = TextAlign.Start,
    bold: Boolean = false,
    color: Color = Color.Unspecified,
    fontSize: Int = 20,
) {
    Text(
        text,
        modifier = Modifier.weight(columnWeights[index]).padding(horizontal = 6.dp),
        fontSize = fontSize.sp,
        textAlign = align,
        color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = 2,
    )
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
        Card(Modifier.width(1000.dp)) {
            Column(Modifier.padding(28.dp).verticalScroll(rememberScrollState())) {
                Text("운행 #${record.trip.id} 정정 요청", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "확정된 원본 값은 바뀌지 않습니다. 저장하면 표에 \"정정 이력\" 줄이 추가되고, 집계에 반영됩니다. 계기판 거리(주행 전·후)는 정정할 수 없습니다.",
                    fontSize = 19.sp,
                    color = Muted,
                )
                Spacer(Modifier.height(16.dp))
                ModeButtons(mode) { mode = it }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(purpose, { purpose = it }, label = { Text("목적") }, textStyle = TextStyle(fontSize = 22.sp), singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(visit, { visit = it }, label = { Text("방문처") }, textStyle = TextStyle(fontSize = 22.sp), singleLine = true, modifier = Modifier.weight(1f))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PURPOSE_PRESETS.forEach { preset ->
                        FilterChip(selected = purpose == preset, onClick = { purpose = preset }, label = { Text(preset, fontSize = 18.sp) })
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("정정 사유 (필수)", fontSize = 20.sp, color = Muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    REASON_PRESETS.forEach { preset ->
                        FilterChip(selected = reason == preset, onClick = { reason = preset }, label = { Text(preset, fontSize = 18.sp) })
                    }
                }
                OutlinedTextField(reason, { reason = it }, label = { Text("사유 직접 입력") }, textStyle = TextStyle(fontSize = 22.sp), singleLine = true, modifier = Modifier.fillMaxWidth())
                if (reason.isBlank()) Text("사유를 입력해야 정정할 수 있습니다", fontSize = 18.sp, color = Warn)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("취소", fontSize = 22.sp) }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { onSave(mode, purpose, visit, reason) }, enabled = changed && reason.isNotBlank()) {
                        Text("정정 이력 추가", fontSize = 22.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(places: List<Place>, message: String?, onRegister: (PlaceKind) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 운행기록", fontSize = 22.sp) }
            Spacer(Modifier.width(16.dp))
            Text("장소 설정", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            "자택·회사를 등록하면 영역 진입·이탈(FusedLocation 영역 알림)로 출퇴근을 자동 제안합니다. 개인 운행은 위치를 저장하지 않습니다.",
            fontSize = 20.sp,
            color = Muted,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        PlaceKind.entries.forEach { kind ->
            val place = places.firstOrNull { it.kind == kind }
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(kind.label, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(120.dp))
                Text(
                    place?.let {
                        String.format(Locale.US, "%.5f, %.5f · 반경 %.0fm · 등록 %s", it.lat, it.lng, it.radiusMeters, DATE_TIME.format(Date(it.registeredAt)))
                    } ?: "미등록",
                    fontSize = 22.sp,
                    color = if (place == null) Muted else Color.Unspecified,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { onRegister(kind) }, modifier = Modifier.height(60.dp)) { Text("현재 위치로 등록", fontSize = 22.sp) }
            }
            HorizontalDivider()
        }
        message?.let { Text(it, fontSize = 22.sp, color = Accent, modifier = Modifier.padding(top = 20.dp)) }
    }
}

private fun routeText(trip: Trip): String = when {
    trip.mode == TripMode.PERSONAL && trip.startLat == null -> "위치 미저장"
    trip.endedAt == null -> "${trip.fromPlace?.label ?: "기타 위치"} →"
    else -> "${trip.fromPlace?.label ?: "기타 위치"} → ${trip.toPlace?.label ?: "기타 위치"}"
}

private fun timeRange(trip: Trip) = TIME.format(Date(trip.startedAt)) + (trip.endedAt?.let { "–" + TIME.format(Date(it)) } ?: "")

private fun round1(km: Double) = Math.round(km * 10) / 10.0

private fun formatKm(km: Double) = String.format(Locale.KOREA, "%,.1f km", km)

private val DATE = SimpleDateFormat("yyyy-MM-dd (E)", Locale.KOREA)
private val DATE_SHORT = SimpleDateFormat("M/d (E)", Locale.KOREA)
private val DATE_TIME = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
private val TIME = SimpleDateFormat("HH:mm", Locale.KOREA)
