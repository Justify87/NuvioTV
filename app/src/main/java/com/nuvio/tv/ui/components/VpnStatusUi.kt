package com.nuvio.tv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import com.nuvio.tv.core.vpn.VpnHealth
import com.nuvio.tv.core.vpn.VpnIndicatorSize
import com.nuvio.tv.core.vpn.VpnTrafficSample
import com.nuvio.tv.core.vpn.WireGuardInfo
import com.nuvio.tv.core.vpn.canRefreshVerification
import com.nuvio.tv.core.vpn.health
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun VpnHealth.tint() = when (this) {
    VpnHealth.CONNECTED -> Color(0xFF63D99B)
    VpnHealth.CHECKING -> Color(0xFFF5C56B)
    VpnHealth.ERROR -> Color(0xFFFF7777)
    VpnHealth.DISCONNECTED -> Color(0xFFB8C0C9)
}
private fun VpnHealth.label() = when (this) {
    VpnHealth.CONNECTED -> "VPN geprüft"
    VpnHealth.CHECKING -> "VPN wird geprüft"
    VpnHealth.ERROR -> "VPN-Störung"
    VpnHealth.DISCONNECTED -> "VPN getrennt"
}
private fun rate(value: Long): String = String.format(Locale.GERMANY, "%.1f MB/s",
    value.coerceAtLeast(0L) / 1_048_576.0)

@Composable
fun VpnStatusButton(info: WireGuardInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = info.health()
    Button(onClick = onClick, modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Security, contentDescription = null, tint = status.tint(),
                modifier = Modifier.size(18.dp))
            androidx.tv.material3.Text("VPN", color = status.tint())
        }
    }
}

@Composable
fun VpnMiniIndicator(info: WireGuardInfo, size: VpnIndicatorSize, modifier: Modifier = Modifier) {
    val status = info.health()
    Row(modifier.background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(7.dp))
            .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Security, contentDescription = status.label(),
            tint = status.tint().copy(alpha = 0.7f), modifier = Modifier.size(when(size) {
                VpnIndicatorSize.MINI -> 12.dp
                VpnIndicatorSize.SMALL -> 16.dp
                VpnIndicatorSize.NORMAL -> 20.dp
            }))
        if (size == VpnIndicatorSize.NORMAL) Text("VPN",
            fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
fun VpnStatusDialog(
    info: WireGuardInfo,
    traffic: List<VpnTrafficSample>,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit
) {
    var showNetwork by remember { mutableStateOf(false) }
    val status = info.health()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (showNetwork) "VPN-Netzwerkstatistik" else "VPN-Status") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status.label(), color = status.tint())
                VpnRow("Profil", info.profile ?: "Nicht ausgewählt")
                VpnRow("Server-Endpunkt", info.endpoint ?: "–")
                VpnRow("Mullvad-Ausgangs-IP", info.exitIp ?: "Nicht bestätigt")
                val location = listOfNotNull(info.exitCity, info.exitCountry).joinToString(", ")
                if (location.isNotEmpty()) VpnRow("Ausgangsstandort", location)
                VpnRow("Letzter Handshake", timestamp(info.lastHandshake))
                VpnRow("Ausgang zuletzt geprüft", timestamp(info.lastVerifiedAt))
                if (info.error != null) Text(info.error, color = Color(0xFFFF8888))
                if (showNetwork) {
                    VpnRow("Empfangen", megabytes(info.receivedBytes))
                    VpnRow("Gesendet", megabytes(info.sentBytes))
                    VpnRow("Download", rate(info.downloadBytesPerSecond))
                    VpnRow("Upload", rate(info.uploadBytesPerSecond))
                    if (traffic.size >= 2) VpnTrafficGraph(traffic)
                }
                Text("App-seitige Prüfung, kein betriebssystemweiter Kill-Switch.",
                    color = Color.Gray, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { showNetwork = !showNetwork }) {
                Text(if (showNetwork) "Status" else "Netzwerkdaten")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRefresh, enabled = info.canRefreshVerification()) {
                    Text("Erneut prüfen")
                }
                TextButton(onClick = onDismiss) { Text("Schließen") }
            }
        }
    )
}

@Composable
private fun VpnRow(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, color = Color.LightGray, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp)
    }
}
private fun megabytes(value: Long): String = String.format(Locale.GERMANY, "%.1f MB",
    value.coerceAtLeast(0L) / 1_048_576.0)
private fun timestamp(value: Long): String = if (value <= 0L) "–"
    else DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(value))

@Composable
private fun VpnTrafficGraph(points: List<VpnTrafficSample>) {
    val download = points.map { it.downloadBytesPerSecond.toFloat() }
    val upload = points.map { it.uploadBytesPerSecond.toFloat() }
    val peak = (download + upload).maxOrNull()?.coerceAtLeast(1f) ?: 1f
    Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
        fun plot(values: List<Float>, color: Color) {
            val segments = (values.size - 1).coerceAtLeast(1)
            values.zipWithNext().forEachIndexed { index, pair ->
                drawLine(color,
                    start = Offset(size.width * index / segments, size.height * (1f - pair.first / peak)),
                    end = Offset(size.width * (index + 1) / segments, size.height * (1f - pair.second / peak)),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        plot(download, Color(0xFF43D8DF))
        plot(upload, Color(0xFF69DA86))
    }
}
