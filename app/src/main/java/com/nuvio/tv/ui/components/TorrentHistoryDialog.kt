package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.tv.core.torrent.TorrentSessionStats
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Focusable, paged actions instead of an unscrollable D-Pad text dump. */
@Composable
fun TorrentHistoryDialog(
    live: TorrentSessionStats?,
    history: List<TorrentSessionStats>,
    onDismiss: () -> Unit,
    onClear: () -> Unit
) {
    var page by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<TorrentSessionStats?>(null) }
    val countPerPage = 5
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (selected == null) "Torrent-Statistiken" else "Sitzungsdetails") },
        text = {
            Column(modifier = Modifier.heightIn(max = 470.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                val detail = selected
                if (detail != null) {
                    Text(detail.title, color = Color(0xFF77DADD), fontSize = 14.sp)
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(detail.startedAtMs)) + " · " + detail.rating,
                        color = Color.White, fontSize = 13.sp)
                    TorrentSessionRows(detail)
                    Text("Die Auflösung ist ein Hinweis aus dem Dateinamen. Die Bewertung " +
                        "beruht nur auf beobachteten Sitzungsdaten.",
                        color = Color.Gray, fontSize = 11.sp)
                } else {
                    if (live != null) {
                        TextButton(onClick = { selected = live },
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Aktiver Torrent · " + speedText(live.averageDownloadBps) +
                                " · " + live.peakPeers + " Peers")
                        }
                    } else {
                        Text("Kein Torrent aktiv.", color = Color.LightGray, fontSize = 12.sp)
                    }
                    Text("Gespeicherter Verlauf: ${history.size} Sitzungen",
                        color = Color.LightGray, fontSize = 12.sp)
                    val visible = history.drop(page * countPerPage).take(countPerPage)
                    if (visible.isEmpty()) Text("Noch keine aufgezeichneten Sitzungen.")
                    visible.forEach { entry ->
                        TextButton(onClick = { selected = entry },
                            modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(entry.title, color = Color.White, fontSize = 13.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(entry.rating + " · " + entry.resolutionHint +
                                    " · Ø " + speedText(entry.averageDownloadBps),
                                    color = Color(0xFF82DEDA), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        },
        dismissButton = {
            Row {
                if (selected != null) {
                    TextButton(onClick = { selected = null }) { Text("Zurück zur Liste") }
                } else {
                    if (page > 0) TextButton(onClick = { page-- }) { Text("Zurück") }
                    if ((page + 1) * countPerPage < history.size) {
                        TextButton(onClick = { page++ }) { Text("Weiter") }
                    }
                    if (history.isNotEmpty()) {
                        TextButton(onClick = {
                            onClear()
                            page = 0
                            selected = null
                        }) { Text("Verlauf löschen") }
                    }
                }
            }
        }
    )
}

@Composable
private fun TorrentSessionRows(entry: TorrentSessionStats) {
    val values = listOf(
        "Auflösung (Dateiname)" to entry.resolutionHint,
        "Download Ø / Peak" to (speedText(entry.averageDownloadBps) +
            " / " + speedText(entry.peakDownloadBps)),
        "Upload Ø" to speedText(entry.averageUploadBps),
        "Peers / Seeder (Peak)" to "${entry.peakPeers} / ${entry.peakSeeds}",
        "Geladen" to String.format(Locale.GERMANY, "%.1f MB",
            entry.downloadedBytes / 1_048_576.0),
        "Puffer-Unterbrechungen" to entry.bufferEvents.toString(),
        "Dauer" to "${entry.durationMs / 1000} s"
    )
    values.forEach { (label, value) ->
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.LightGray, fontSize = 12.sp)
            Text(value, color = Color.White, fontSize = 12.sp)
        }
    }
}

private fun speedText(bytesPerSecond: Long): String =
    String.format(Locale.GERMANY, "%.1f MB/s", bytesPerSecond / 1_048_576.0)
