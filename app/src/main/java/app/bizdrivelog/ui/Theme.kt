package app.bizdrivelog.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bizdrivelog.data.TripMode

/*
 * Pleos Connect UI Design Guide 기준 (160dpi 기준 1sp = 1px, 에뮬레이터 Connect 화면도 160dpi)
 * - 글자: Headline 60/56/48 · Title 40/36/32 · Body 30/28/26 · Label 24/20 (본문 최소 26)
 * - 버튼 높이: Large 100 (모서리 32) · Medium 80 (24) · Small 64 (16) → 터치 대상은 Medium 이상
 * - 대비 4.5:1 이상, 다크 테마
 * 색은 분류 3색 + 미확정 강조 1색 + 무채색만 쓴다.
 */
object Palette {
    val Background = Color(0xFF111316)
    val Surface = Color(0xFF1C1F24)
    val SurfaceHigh = Color(0xFF262A31)
    val Outline = Color(0xFF3A3F47)
    val Text = Color(0xFFF2F4F6)
    /** 보조 글자 (배경 대비 약 8:1) */
    val TextSub = Color(0xFFADB3BC)

    val Business = Color(0xFF5AA2FF)
    val Commute = Color(0xFF45D19A)
    val Personal = Color(0xFFFF8A73)

    /** 미확정·확인 필요 */
    val Pending = Color(0xFFFFC94D)
}

val TripMode.color: Color
    get() = when (this) {
        TripMode.BUSINESS -> Palette.Business
        TripMode.COMMUTE -> Palette.Commute
        TripMode.PERSONAL -> Palette.Personal
    }

object Type {
    val Headline = TextStyle(fontSize = 48.sp, lineHeight = 58.sp, fontWeight = FontWeight.ExtraBold)
    val TitleL = TextStyle(fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold)
    val TitleM = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
    val TitleS = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
    val BodyL = TextStyle(fontSize = 30.sp, lineHeight = 40.sp)
    val BodyM = TextStyle(fontSize = 28.sp, lineHeight = 38.sp)
    val BodyS = TextStyle(fontSize = 26.sp, lineHeight = 36.sp)
    val Label = TextStyle(fontSize = 24.sp, lineHeight = 30.sp)
}

object Dim {
    val ButtonL = 100.dp
    val ButtonM = 80.dp
    val RadiusL = 32.dp
    val RadiusM = 24.dp
    val Gutter = 40.dp
}

@Composable
fun BizDriveLogTheme(content: @Composable () -> Unit) {
    // Material 기본 보라를 쓰지 않도록 강조색까지 무채색으로 덮는다
    val scheme = darkColorScheme(
        primary = Palette.Text,
        onPrimary = Palette.Background,
        primaryContainer = Palette.SurfaceHigh,
        onPrimaryContainer = Palette.Text,
        secondary = Palette.TextSub,
        onSecondary = Palette.Background,
        secondaryContainer = Palette.SurfaceHigh,
        onSecondaryContainer = Palette.Text,
        tertiary = Palette.TextSub,
        background = Palette.Background,
        onBackground = Palette.Text,
        surface = Palette.Surface,
        onSurface = Palette.Text,
        surfaceVariant = Palette.SurfaceHigh,
        onSurfaceVariant = Palette.TextSub,
        surfaceContainerHigh = Palette.Surface,
        surfaceContainerHighest = Palette.SurfaceHigh,
        outline = Palette.Outline,
        outlineVariant = Palette.Outline,
    )
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = Palette.Background, contentColor = Palette.Text) { content() }
    }
}

/** 큰 버튼. color = null 이면 무채색 */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color? = null,
    enabled: Boolean = true,
    height: Dp = Dim.ButtonM,
) {
    val radius = if (height >= Dim.ButtonL) Dim.RadiusL else Dim.RadiusM
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height).defaultMinSize(minWidth = 160.dp),
        shape = RoundedCornerShape(radius),
        colors = ButtonDefaults.buttonColors(
            containerColor = color ?: Palette.SurfaceHigh,
            contentColor = if (color != null) Palette.Background else Palette.Text,
            disabledContainerColor = Palette.Surface,
            disabledContentColor = Palette.TextSub,
        ),
        contentPadding = PaddingValues(horizontal = 36.dp),
    ) {
        Text(text, style = if (height >= Dim.ButtonL) Type.TitleS else Type.BodyL.copy(fontWeight = FontWeight.Bold))
    }
}

/** 선택 칩 (높이 80). 선택되면 color로 채운다 */
@Composable
fun BigChip(text: String, selected: Boolean, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Dim.RadiusM)
    Box(
        modifier
            .height(Dim.ButtonM)
            .defaultMinSize(minWidth = 160.dp)
            .clip(shape)
            .background(if (selected) color else Palette.Surface)
            .border(BorderStroke(2.dp, if (selected) color else Palette.Outline), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Type.BodyL.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
            color = if (selected) Palette.Background else Palette.Text,
        )
    }
}

/** 목록·상세의 분류 표시 (작은 칩, 눌리지 않음) */
@Composable
fun ModeTag(mode: TripMode?) {
    val color = mode?.color ?: Palette.Pending
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.18f))
            .border(2.dp, color, RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        Text(mode?.label ?: "미분류", style = Type.Label.copy(fontWeight = FontWeight.Bold), color = color)
    }
}

/** 확정 = 자물쇠, 미확정 = 강조색 "확인 필요" */
@Composable
fun ConfirmState(confirmed: Boolean) {
    if (confirmed) {
        Icon(Icons.Filled.Lock, contentDescription = "확정", tint = Palette.TextSub, modifier = Modifier.size(32.dp))
    } else {
        Text("확인 필요", style = Type.Label.copy(fontWeight = FontWeight.Bold), color = Palette.Pending)
    }
}

@Composable
fun BigField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = Type.Label) },
        textStyle = Type.BodyL,
        singleLine = true,
        shape = RoundedCornerShape(Dim.RadiusM),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Text,
            unfocusedBorderColor = Palette.Outline,
            focusedLabelColor = Palette.TextSub,
            unfocusedLabelColor = Palette.TextSub,
            cursorColor = Palette.Text,
        ),
        modifier = modifier.defaultMinSize(minHeight = Dim.ButtonM),
    )
}

/** 화면 머리: 뒤로 + 제목 */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        BigButton("← 뒤로", onBack)
        Text(title, style = Type.TitleL, modifier = Modifier.weight(1f))
        trailing()
    }
}
