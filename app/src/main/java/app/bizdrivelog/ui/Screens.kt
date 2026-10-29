package app.bizdrivelog.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bizdrivelog.data.Trip
import app.bizdrivelog.drive.DriveStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DrivingGreen = Color(0xFF4CD787)

@Composable
fun BizDriveLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
    }
}

/** 주행 중 최소 화면: 운행 중 표시와 이번 운행 주행거리만. */
@Composable
fun DrivingScreen(status: DriveStatus) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("운행 중", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = DrivingGreen)
        Spacer(Modifier.height(24.dp))
        Text(
            status.tripDistanceKm?.let { formatKm(it) } ?: "-",
            fontSize = 96.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
fun HomeScreen(status: DriveStatus, trips: List<Trip>) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
        StatusBar(status)
        Spacer(Modifier.height(28.dp))
        TripHeader()
        HorizontalDivider()
        if (trips.isEmpty()) {
            Text("기록된 운행이 없습니다", fontSize = 26.sp, modifier = Modifier.padding(top = 24.dp), color = Muted)
        }
        LazyColumn {
            items(trips, key = { it.id }) { trip ->
                TripRow(trip)
                HorizontalDivider()
            }
        }
    }
}

private val Muted = Color(0xFFA0A4AB)

@Composable
private fun StatusBar(status: DriveStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (status.isDriving) "운행 중" else "주차",
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
            color = if (status.isDriving) DrivingGreen else MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        Text("계기판 누적거리  ", fontSize = 26.sp, color = Muted)
        Text(status.odometerKm?.let { formatKm(it) } ?: "-", fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
}

private val columnWeights = listOf(1.3f, 1f, 1f, 1.5f, 1.5f, 1.2f)

@Composable
private fun TripHeader() {
    TableRow(listOf("날짜", "출발", "도착", "주행 전 계기판", "주행 후 계기판", "주행거리"), header = true)
}

@Composable
private fun TripRow(trip: Trip) {
    TableRow(
        listOf(
            DATE.format(Date(trip.startedAt)),
            TIME.format(Date(trip.startedAt)),
            trip.endedAt?.let { TIME.format(Date(it)) } ?: "운행 중",
            formatKm(trip.startOdometerKm),
            trip.endOdometerKm?.let { formatKm(it) } ?: "-",
            trip.distanceKm?.let { formatKm(it) } ?: "-",
        ),
        header = false,
    )
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = if (header) 10.dp else 16.dp)) {
        cells.forEachIndexed { i, text ->
            Text(
                text,
                modifier = Modifier.weight(columnWeights[i]),
                fontSize = if (header) 20.sp else 26.sp,
                color = if (header) Muted else MaterialTheme.colorScheme.onBackground,
                fontWeight = if (i == cells.lastIndex && !header) FontWeight.Bold else FontWeight.Normal,
                textAlign = if (i >= 3) TextAlign.End else TextAlign.Start,
            )
        }
    }
}

private fun formatKm(km: Double) = String.format(Locale.KOREA, "%,.1f km", km)

private val DATE = SimpleDateFormat("yyyy-MM-dd (E)", Locale.KOREA)
private val TIME = SimpleDateFormat("HH:mm", Locale.KOREA)
