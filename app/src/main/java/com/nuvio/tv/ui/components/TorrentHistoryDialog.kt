package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.core.torrent.TorrentSessionStats
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TorrentHistoryDialog(
    live: TorrentSessionStats?,
    history: List<TorrentSessionStats>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Torrent-Statistiken") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 470.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (live != null) {
                    Text("Aktuelle Sitzung", color = Color(0xFF43D8DF))
                    TorrentSessionRows(live)
                } else {
                    Text("Zurzeit ist keine Torrent-Sitzung aktiv.", color = Color.LightGray)
                }
                Text("Letzte Sitzungen (nur bei aktivierter Aufzeichnung)",
                    color = Color.LightGray, fontSize = 12.sp)
                if (history.isEmpty()) Text("Noch keine gespeicherten Sitzungen.")
                history.take(40).forEach { session ->
                    Text(session.title, color = Color.White, fontSize = 13.sp)
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(session.startedAtMs)) + " · " + session.rating,
                        color = Color(0xFF82DEDA), fontSize = 12.sp)
                    TorrentSessionRows(session)
                }
                Text("Auflösung ist ein Dateinamen-Hinweis, keine verifizierte Videospur. " +
                     "Die Bewertung beruht nur auf beobachteten Sitzungsdaten.",
                    color = Color.Gray, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        },
        dismissButton = {
            if (history.isNotEmpty()) TextButton(onClick = onClear) {
                Text("Verlauf löschen")
            }
        }
    )
}

@Composable
private fun TorrentSessionRows(entry: TorrentSessionStats) {
    val values = listOf(
        "Qualitätshinweis" to entry.resolutionHint,
        "Download Ø / Peak" to (speedText(entry.averageDownloadBps) + " / " + speedText(entry.peakDownloadBps)),
        "Upload Ø" to speedText(entry.averageUploadBps),
        "Peers / Seeder (Peak)" to "${entry.peakPeers} / ${entry.peakSeeds}",
        "Geladen" to String.format(Locale.GERMANY, "%.1f MB", entry.downloadedBytes / 1_048_576.0),
        "Puffer-Ereignisse" to entry.bufferEvents.toString(),
        "Dauer" to "${entry.durationMs / 1000} s"
    )
    values.forEach { (label, value) ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.LightGray, fontSize = 12.sp)
            Text(value, color = Color.White, fontSize = 12.sp)
        }
    }
}
private fun speedText(bytesPerSecond: Long): String =
    String.format(Locale.GERMANY, "%.1f MB/s", bytesPerSecond / 1_048_576.0)
