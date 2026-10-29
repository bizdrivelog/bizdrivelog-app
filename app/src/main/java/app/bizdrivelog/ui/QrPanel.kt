package app.bizdrivelog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bizdrivelog.export.QrState
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.delay
import java.io.File

/** 휴대폰으로 받기: QR / 핫스팟 연결 안내 / 완료·만료 */
@Composable
fun QrPanel(state: QrState, file: File?, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("휴대폰으로 받기", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        when (state) {
            is QrState.Waiting -> Waiting(state, file)
            QrState.NoWifi -> Guide(
                "휴대폰 핫스팟에 차량을 연결해 주세요",
                listOf(
                    "1. 휴대폰에서 개인용 핫스팟(테더링)을 켭니다",
                    "2. 차량 설정 > Wi-Fi에서 휴대폰 핫스팟에 연결합니다",
                    "3. 다시 '휴대폰으로 받기'를 누르면 QR이 나타납니다",
                ),
            )
            QrState.Downloaded -> Guide("휴대폰으로 보냈습니다", listOf("링크는 한 번만 쓸 수 있어 서버를 바로 종료했습니다.", "휴대폰에서 메일·메신저 등 회사 방식으로 전달해 주세요."))
            QrState.Expired -> Guide("5분이 지나 링크가 만료되었습니다", listOf("서버를 종료했습니다. 다시 받으려면 QR을 새로 만드세요."))
            is QrState.Failed -> Guide(state.message, emptyList())
            QrState.Idle -> Unit
        }
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state != QrState.Downloaded && state !is QrState.Waiting) {
                Button(onClick = onRetry, modifier = Modifier.height(64.dp)) { Text("다시 시도", fontSize = 24.sp) }
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.height(64.dp)) { Text("닫기", fontSize = 24.sp) }
        }
    }
}

@Composable
private fun Waiting(state: QrState.Waiting, file: File?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.expiresAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val left = ((state.expiresAt - now) / 1000).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        QrImage(state.url, Modifier.size(420.dp))
        Spacer(Modifier.width(48.dp))
        Column {
            Text("휴대폰 카메라로 QR을 찍으면\n엑셀 파일이 저장됩니다", fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            file?.let { Text(it.name, fontSize = 22.sp) }
            Text("휴대폰과 차량이 같은 Wi-Fi(휴대폰 핫스팟)에 있어야 합니다", fontSize = 20.sp, color = Muted)
            Spacer(Modifier.height(16.dp))
            Text("남은 시간 %d:%02d".format(left / 60, left % 60), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Accent)
            Text("한 번 받으면 또는 5분이 지나면 자동으로 닫힙니다", fontSize = 20.sp, color = Muted)
            Spacer(Modifier.height(12.dp))
            Text(state.address + if (state.debugOverride) "  (디버그: 주소 덮어쓰기)" else "", fontSize = 18.sp, color = Muted)
        }
    }
}

@Composable
private fun Guide(title: String, lines: List<String>) {
    Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Warn)
    Spacer(Modifier.height(16.dp))
    lines.forEach { Text(it, fontSize = 22.sp, modifier = Modifier.padding(vertical = 4.dp)) }
}

@Composable
private fun QrImage(text: String, modifier: Modifier) {
    val qr = remember(text) { QrCode.encodeText(text, QrCode.Ecc.MEDIUM) }
    Canvas(modifier) {
        val border = 4
        val cells = qr.size + border * 2
        val cell = size.minDimension / cells
        drawRect(Color.White, size = Size(cell * cells, cell * cells))
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr.getModule(x, y)) drawRect(Color.Black, Offset((x + border) * cell, (y + border) * cell), Size(cell, cell))
        }
    }
}
