package app.bizdrivelog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bizdrivelog.data.LedgerProfile
import app.bizdrivelog.data.Place
import app.bizdrivelog.data.PlaceKind
import app.bizdrivelog.data.Trip
import app.bizdrivelog.data.TripMode
import app.bizdrivelog.data.TripRecord
import app.bizdrivelog.drive.DriveStatus
import app.bizdrivelog.export.Ledger
import app.bizdrivelog.export.LedgerPeriod
import app.bizdrivelog.export.LedgerPeriodKind
import app.bizdrivelog.voice.VoicePhase
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.util.Date
import java.util.Locale

const val APP_NAME = "비즈드라이브로그"

class HomeActions(
    val classify: (trip: Trip, mode: TripMode, modeBy: String, purpose: String, visitPlace: String) -> Unit,
    val confirm: (Trip) -> Unit,
    val correct: (record: TripRecord, mode: TripMode, purpose: String, visitPlace: String, reason: String) -> Unit,
    val askVoice: (Trip) -> Unit,
    val openReview: (tripId: Long) -> Unit,
    val openDetail: (tripId: Long) -> Unit,
    val openSettings: () -> Unit,
    val openExport: () -> Unit,
)

/** 확인(분류·확정)할 운행: 끝났지만 확정 안 된 운행, 최근 순 */
fun List<TripRecord>.pending(): List<TripRecord> = filter { !it.trip.isConfirmed && it.trip.endedAt != null }

/** 주행 중 최소 화면: 운행 중 표시와 이번 운행 주행거리만. 입력 UI 없음. */
@Composable
fun DrivingScreen(status: DriveStatus) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("운행 중", style = Type.TitleL)
        Spacer(Modifier.height(24.dp))
        Text(status.tripDistanceKm?.let { formatKm(it) } ?: "-", fontSize = 120.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(24.dp))
        Text("주행 중에는 조작 없이 기록만 합니다 · 확인은 정차(P) 후", style = Type.BodyM, color = Palette.TextSub)
    }
}

@Composable
fun HomeScreen(status: DriveStatus, records: List<TripRecord>, voice: VoicePhase?, actions: HomeActions) {
    val pending = records.pending()
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = Dim.Gutter),
        contentPadding = PaddingValues(vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { TopBar(status, actions) }
        // 확인할 운행은 정차 중에만 (운행 중이면 입력 UI를 띄우지 않는다)
        if (pending.isNotEmpty() && !status.isDriving) {
            item(key = "pending") { PendingBanner(pending, voice) { actions.openReview(pending.first().trip.id) } }
        }
        item { MonthSummary(records) }
        item { Text("운행 기록", style = Type.TitleS, modifier = Modifier.padding(top = 12.dp)) }
        if (records.isEmpty()) {
            item { Text("운행 기록이 없습니다. 시동을 걸면(기어 D) 자동으로 기록이 시작됩니다.", style = Type.BodyM, color = Palette.TextSub) }
        }
        items(records, key = { it.trip.id }) { record ->
            TripRowCard(record) {
                when {
                    record.trip.endedAt == null -> Unit
                    record.trip.isConfirmed -> actions.openDetail(record.trip.id)
                    else -> actions.openReview(record.trip.id)
                }
            }
        }
    }
}

@Composable
private fun TopBar(status: DriveStatus, actions: HomeActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        StatusPill(status.isDriving)
        Column(Modifier.weight(1f)) {
            Text(APP_NAME, style = Type.TitleM)
            Text(
                "계기판 누적거리 " + (status.odometerKm?.let { formatKm(it) } ?: "-"),
                style = Type.BodyS,
                color = Palette.TextSub,
            )
        }
        BigButton("운행기록부 내보내기", actions.openExport)
        BigButton("설정", actions.openSettings)
    }
}

@Composable
private fun StatusPill(driving: Boolean) {
    Row(
        Modifier
            .height(Dim.ButtonM)
            .clip(RoundedCornerShape(Dim.RadiusM))
            .background(Palette.Surface)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(if (driving) Palette.Text else Palette.TextSub))
        Spacer(Modifier.width(16.dp))
        Text(if (driving) "운행 중" else "주차", style = Type.TitleS)
    }
}

/** 미확정 운행 알림: 맨 위에 크게, 누르면 분류 카드로 */
@Composable
private fun PendingBanner(pending: List<TripRecord>, voice: VoicePhase?, onOpen: () -> Unit) {
    val latest = pending.first().trip
    val shape = RoundedCornerShape(Dim.RadiusL)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Surface)
            .border(BorderStroke(3.dp, Palette.Pending), shape)
            .clickable(onClick = onOpen)
            .padding(horizontal = 36.dp, vertical = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("확인할 운행 ${pending.size}건", style = Type.TitleL, color = Palette.Pending)
            Spacer(Modifier.height(8.dp))
            Text(
                "${DATE_SHORT.format(Date(latest.startedAt))} ${timeRange(latest)}  ·  ${routeText(latest)}  ·  ${distanceText(latest) ?: "-"}",
                style = Type.BodyL,
            )
            voiceText(voice)?.let { Text(it, style = Type.BodyM, color = Palette.TextSub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(24.dp))
        BigButton("확인하기", onOpen, color = Palette.Pending, height = Dim.ButtonL)
    }
}

/** 음성 진행 상태 문구 (대기 중이면 null) */
fun voiceText(voice: VoicePhase?): String? = when (voice) {
    is VoicePhase.Speaking -> "음성 안내 중: ${voice.text}"
    is VoicePhase.Listening -> "듣는 중… ${voice.partial}"
    is VoicePhase.Heard -> "인식: “${voice.text}”"
    VoicePhase.Idle, null -> null
}

/** 이번 달 요약. 업무용 사용거리 = 출퇴근 + 업무 (법인세법 시행규칙 제27조의2 ⑦). 정정 반영값으로 집계. */
@Composable
private fun MonthSummary(records: List<TripRecord>) {
    // 엑셀 ⑪⑫⑬과 같은 계산을 쓴다
    val period = LedgerPeriod.of(LedgerPeriodKind.THIS_MONTH)
    val ledger = Ledger.build(records, LedgerProfile(), period)
    val totals = ledger.totals
    val month = YearMonth.from(period.start)
    val business = ledger.rows.sumOf { it.businessKm ?: 0.0 }
    val commute = ledger.rows.sumOf { it.commuteKm ?: 0.0 }
    val personal = (totals.totalKm - business - commute).coerceAtLeast(0.0)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.RadiusL)).background(Palette.Surface).padding(32.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("${month.year}년 ${month.monthValue}월 업무 사용 비율 (확정 운행)", style = Type.BodyM, color = Palette.TextSub)
                Text(String.format(Locale.KOREA, "%.1f%%", totals.ratioPercent), fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.ExtraBold)
            }
            SummaryValue("총 주행거리", formatKm(totals.totalKm))
            Spacer(Modifier.width(56.dp))
            SummaryValue("업무용 사용거리 (출퇴근+업무)", formatKm(totals.businessUseKm))
        }
        Spacer(Modifier.height(20.dp))
        // 막대: 업무 · 출퇴근 · 개인 비율
        Row(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh)) {
            listOf(TripMode.BUSINESS to business, TripMode.COMMUTE to commute, TripMode.PERSONAL to personal)
                .filter { it.second > 0 }
                .forEach { (mode, km) -> Box(Modifier.weight(km.toFloat()).fillMaxHeight().background(mode.color)) }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(TripMode.BUSINESS, business)
            Legend(TripMode.COMMUTE, commute)
            Legend(TripMode.PERSONAL, personal)
            Spacer(Modifier.weight(1f))
            // 집계는 확정 운행만. 미확정은 따로
            if (ledger.pending.isNotEmpty()) {
                Text("미확정 ${ledger.pending.size}건 · ${formatKm(ledger.pendingKm)} (집계 제외)", style = Type.BodyS, color = Palette.Pending)
            }
        }
    }
}

@Composable
private fun SummaryValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, style = Type.Label, color = Palette.TextSub)
        Text(value, style = Type.TitleM)
    }
}

@Composable
private fun Legend(mode: TripMode, km: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(mode.color))
        Spacer(Modifier.width(12.dp))
        Text("${mode.label} ${formatKm(km)}", style = Type.BodyS)
    }
}

/** 운행 한 줄 (큰 행). 계기판 전·후 값과 정정 이력은 상세 화면에서 */
@Composable
private fun TripRowCard(record: TripRecord, onClick: () -> Unit) {
    val trip = record.trip
    val mode = record.effectiveMode
    val shape = RoundedCornerShape(Dim.RadiusM)
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 120.dp)
            .clip(shape)
            .background(Palette.Surface)
            .then(if (!trip.isConfirmed && trip.endedAt != null) Modifier.border(2.dp, Palette.Pending, shape) else Modifier)
            .clickable(enabled = trip.endedAt != null, onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(220.dp)) {
            Text(DATE_SHORT.format(Date(trip.startedAt)), style = Type.BodyL.copy(fontWeight = FontWeight.Bold))
            Text(timeRange(trip), style = Type.BodyS, color = Palette.TextSub)
        }
        Column(Modifier.weight(1f).padding(end = 24.dp)) {
            Text(if (hasNoLocation(trip)) "구간 없음" else routeText(trip, record), style = Type.BodyL.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            subLine(record)?.let { Text(it, style = Type.BodyS, color = Palette.TextSub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(
            if (trip.endedAt == null) "운행 중" else distanceText(trip) ?: "-",
            style = Type.TitleS,
            color = if (trip.odometerAnomaly) Palette.Pending else Palette.Text,
            textAlign = TextAlign.End,
            modifier = Modifier.width(220.dp),
        )
        Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (trip.endedAt != null) ModeTag(mode)
            // 정정된 운행: 칩은 정정 값, 아래에 표시
            if (record.corrections.isNotEmpty()) Text("정정됨", style = Type.Label, color = Palette.TextSub)
        }
        Box(Modifier.width(130.dp), contentAlignment = Alignment.CenterEnd) { if (trip.endedAt != null) ConfirmState(trip.isConfirmed) }
    }
}

private fun subLine(record: TripRecord): String? = when {
    record.effectiveMode == TripMode.PERSONAL -> "개인 운행 · 위치 미저장"
    hasNoLocation(record.trip) -> "위치 미저장 (원본 개인 운행)"
    else -> record.effectivePurpose.takeIf { it.isNotBlank() }
}

@Composable
fun SettingsScreen(
    places: List<Place>,
    message: String?,
    profile: LedgerProfile,
    voiceEnabled: Boolean,
    onVoiceEnabled: (Boolean) -> Unit,
    showQrAddress: Boolean?,
    onShowQrAddress: (Boolean) -> Unit,
    onRegister: (PlaceKind) -> Unit,
    onSaveProfile: (LedgerProfile) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = Dim.Gutter, vertical = 28.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader("설정", onBack)
        message?.let { Text(it, style = Type.BodyM, color = Palette.Text) }
        ProfileForm(profile, onSaveProfile)
        ToggleRow(
            "음성 안내",
            "운행 시작 시 짧게 알리고, 정차(P) 후 분류·목적을 묻습니다. 답하지 않거나 인식하지 못하면 화면에서 입력합니다. 음성은 차 안에서만 처리합니다.",
            voiceEnabled,
            onVoiceEnabled,
        )
        SectionTitle("장소", "자택·회사를 등록하면 출퇴근을 자동 제안합니다. 개인 운행은 위치를 저장하지 않습니다.")
        PlaceKind.entries.forEach { kind ->
            val place = places.firstOrNull { it.kind == kind }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.RadiusM)).background(Palette.Surface).padding(horizontal = 32.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(kind.label, style = Type.TitleS, modifier = Modifier.width(140.dp))
                Text(
                    place?.let {
                        String.format(Locale.US, "%.5f, %.5f · 반경 %.0fm · 등록 %s", it.lat, it.lng, it.radiusMeters, DATE_TIME.format(Date(it.registeredAt)))
                    } ?: "미등록",
                    style = Type.BodyS,
                    color = if (place == null) Palette.Pending else Palette.TextSub,
                    modifier = Modifier.weight(1f),
                )
                BigButton("현재 위치로 등록", { onRegister(kind) })
            }
        }
        // 디버그 빌드에서만
        showQrAddress?.let { ToggleRow("QR 화면에 연결 주소 표시 (디버그)", "휴대폰으로 받기 화면 아래에 서버 주소를 보여 줍니다.", it, onShowQrAddress) }
    }
}

@Composable
private fun SectionTitle(title: String, description: String? = null) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(title, style = Type.TitleS)
        description?.let { Text(it, style = Type.BodyS, color = Palette.TextSub) }
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dim.RadiusM))
            .background(Palette.Surface)
            .clickable { onChange(!checked) }
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.TitleS)
            Text(description, style = Type.BodyS, color = Palette.TextSub)
        }
        Spacer(Modifier.width(32.dp))
        Text(if (checked) "켜짐" else "꺼짐", style = Type.BodyL, color = if (checked) Palette.Text else Palette.TextSub)
        Spacer(Modifier.width(32.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.scale(1.6f).padding(horizontal = 12.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Background,
                checkedTrackColor = Palette.Text,
                uncheckedThumbColor = Palette.TextSub,
                uncheckedTrackColor = Palette.SurfaceHigh,
                uncheckedBorderColor = Palette.Outline,
            ),
        )
    }
}

/** 운행기록부 머리 정보 (국세청 별지 서식 상단·①②·④) */
@Composable
private fun ProfileForm(saved: LedgerProfile, onSave: (LedgerProfile) -> Unit) {
    var p by remember(saved) { mutableStateOf(saved) }
    SectionTitle("운행기록부 머리 정보", "④ 사용자는 운전자가 아니라 차량 이용자(부서·성명)입니다. 차종은 차량 정보로 확인되지 않아 직접 입력합니다.")
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        BigField(p.company, { p = p.copy(company = it) }, "상호(법인명)", Modifier.weight(1f))
        BigField(p.bizNo, { p = p.copy(bizNo = it) }, "사업자등록번호", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        BigField(p.carModel, { p = p.copy(carModel = it) }, "①차종", Modifier.weight(1f))
        BigField(p.plate, { p = p.copy(plate = it) }, "②자동차등록번호", Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        BigField(p.dept, { p = p.copy(dept = it) }, "④사용자 부서", Modifier.weight(1f))
        BigField(p.userName, { p = p.copy(userName = it) }, "④사용자 성명", Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BigButton("머리 정보 저장", { onSave(p) }, enabled = p != saved)
        Spacer(Modifier.width(24.dp))
        if (saved.missing.isNotEmpty()) Text("미입력: ${saved.missing.joinToString(", ")}", style = Type.BodyS, color = Palette.Pending)
    }
}

/**
 * 구간 표시. 등록 장소(자택·회사)는 이름, 그 밖의 곳은 업무면 방문처 이름을 쓴다.
 * 도착지가 등록 장소가 아니면 도착지를, 아니면 출발지를 방문처로 본다.
 */
fun routeText(trip: Trip, record: TripRecord? = null): String {
    val mode = record?.effectiveMode ?: trip.mode
    val visit = (record?.effectiveVisitPlace ?: trip.visitPlace).takeIf { mode == TripMode.BUSINESS && it.isNotBlank() }
    // 개인 운행으로 끝난 운행은 위치가 없다 (나중에 정정으로 분류가 바뀌어도)
    if (hasNoLocation(trip)) return "위치 미저장"
    val from = trip.fromPlace?.label
    if (trip.endedAt == null) return "${from ?: "출발지"} →"
    val to = trip.toPlace?.label
    val toText = to ?: visit ?: OTHER_PLACE
    val fromText = from ?: visit?.takeIf { to != null } ?: OTHER_PLACE
    return "$fromText → $toText"
}

private const val OTHER_PLACE = "기타 장소"

/** 개인 운행으로 끝나 위치가 없는 운행 (나중에 정정으로 분류가 바뀌어도) */
internal fun hasNoLocation(trip: Trip) =
    trip.mode == TripMode.PERSONAL && trip.startLat == null && trip.fromPlace == null && trip.toPlace == null

internal fun timeRange(trip: Trip) = TIME.format(Date(trip.startedAt)) + (trip.endedAt?.let { "–" + TIME.format(Date(it)) } ?: "")

internal fun formatKm(km: Double) = String.format(Locale.KOREA, "%,.1f km", km)

/** 종료 누적거리 < 시작이면 거리 대신 "계기판 값 이상" (집계 제외) */
internal fun distanceText(trip: Trip): String? = if (trip.odometerAnomaly) ODOMETER_ANOMALY else trip.distanceKm?.let { formatKm(it) }

internal const val ODOMETER_ANOMALY = "계기판 값 이상"

internal val DATE = SimpleDateFormat("yyyy-MM-dd (E)", Locale.KOREA)
internal val DATE_SHORT = SimpleDateFormat("M/d (E)", Locale.KOREA)
internal val DATE_TIME = SimpleDateFormat("M/d HH:mm", Locale.KOREA)
internal val TIME = SimpleDateFormat("HH:mm", Locale.KOREA)
