package com.nuvio.tv.ui.screens.streamingmonitor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.nuvio.tv.R
import com.nuvio.tv.core.torrent.TorrentRateSample
import com.nuvio.tv.core.torrent.TorrentSessionStats
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date

/** The permanent ID of new sessions is persisted. Legacy IDs are deterministic in-memory. */
internal data class StreamingHistoryEntry(
    val key: String,
    val session: TorrentSessionStats,
    val isLive: Boolean
)

/** A live session can never also be counted as a saved history row. */
internal fun streamingHistoryEntries(
    saved: List<TorrentSessionStats>,
    live: TorrentSessionStats?
): List<StreamingHistoryEntry> {
    val occurrences = mutableMapOf<String, Int>()
    val recorded = saved.sortedByDescending { it.startedAtMs }.take(40).mapNotNull { session ->
        if (live != null && (
                session.sessionId.isNotEmpty() && session.sessionId == live.sessionId ||
                session.startedAtMs == live.startedAtMs && session.title == live.title
            )
        ) return@mapNotNull null
        val identity = session.sessionId.takeIf { it.isNotBlank() }
            ?: "${session.startedAtMs}:${session.title.hashCode()}:${session.durationMs}"
        val occurrence = occurrences[identity] ?: 0
        occurrences[identity] = occurrence + 1
        StreamingHistoryEntry("saved:$identity:$occurrence", session, false)
    }
    return (live?.let {
        listOf(StreamingHistoryEntry("live:${it.sessionId.ifEmpty { it.startedAtMs.toString() }}", it, true))
    } ?: emptyList()) + recorded
}

/** Native root-tab list and in-route detail; the only popup confirms destructive clearing. */
@Composable
internal fun StreamingMonitorHistory(
    saved: List<TorrentSessionStats>,
    live: TorrentSessionStats?,
    liveSamples: List<TorrentRateSample>,
    recordingEnabled: Boolean,
    onClearHistory: () -> Unit,
    firstContentRequester: FocusRequester,
    returnToTabsRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onContentFocusChanged: (Boolean) -> Unit,
    onDetailVisibilityChanged: (Boolean) -> Unit = {}
) {
    val entries = remember(saved, live) { streamingHistoryEntries(saved, live) }
    val listState = rememberLazyListState()
    val returnRowRequester = remember { FocusRequester() }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var lastOpenedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreRow by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val selectedEntry = entries.firstOrNull { it.key == selectedKey }
    val hasSaved = saved.isNotEmpty()

    LaunchedEffect(selectedEntry) { onDetailVisibilityChanged(selectedEntry != null) }

    BackHandler(enabled = confirmClear || selectedKey != null) {
        if (confirmClear) {
            confirmClear = false
        } else {
            selectedKey = null
            restoreRow = true
        }
    }

    LaunchedEffect(entries, selectedKey) {
        if (selectedKey != null && selectedEntry == null) {
            selectedKey = null
            restoreRow = true
        }
    }
    LaunchedEffect(selectedKey, restoreRow, entries) {
        if (selectedKey == null && restoreRow) {
            val index = entries.indexOfFirst { it.key == lastOpenedKey }
            if (index >= 0) {
                listState.scrollToItem(index + 2)
            }
            withFrameNanos { }
            runCatching {
                if (index >= 0) returnRowRequester.requestFocus()
                else firstContentRequester.requestFocus()
            }
            restoreRow = false
        }
    }

    if (selectedEntry != null) {
        StreamingMonitorSessionDetail(
            entry = selectedEntry,
            liveSamples = if (selectedEntry.isLive) liveSamples else emptyList(),
            firstContentRequester = firstContentRequester,
            returnToTabsRequester = returnToTabsRequester,
            modifier = modifier,
            onContentFocusChanged = onContentFocusChanged,
            onBack = {
                selectedKey = null
                restoreRow = true
            }
        )
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxWidth().focusGroup()
                .onFocusChanged { onContentFocusChanged(it.hasFocus) },
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            item(key = "history-intro") {
                MonitorFocusableCard(
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(firstContentRequester)
                        .focusProperties { up = returnToTabsRequester }
                ) {
                    Text(
                        text = stringResource(R.string.sm_history_count, saved.size.coerceAtMost(40)),
                        style = MaterialTheme.typography.titleLarge,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        text = stringResource(
                            if (recordingEnabled) R.string.sm_history_recording_on
                            else R.string.sm_history_recording_off
                        ),
                        color = NuvioTheme.colors.TextSecondary
                    )
                    Text(
                        text = stringResource(R.string.sm_history_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
            item(key = "history-clear") {
                if (hasSaved) {
                    MonitorFocusableCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { confirmClear = true }
                    ) {
                        Text(
                            text = stringResource(R.string.sm_history_clear),
                            color = NuvioTheme.colors.TextPrimary
                        )
                        Text(
                            text = stringResource(R.string.sm_history_clear_caption),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                } else {
                    MonitorFocusableCard(Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.sm_history_empty),
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
            }
            items(entries.size, key = { entries[it].key }) { index ->
                val row = entries[index]
                val data = row.session
                val restoreModifier = if (row.key == lastOpenedKey) {
                    Modifier.focusRequester(returnRowRequester)
                } else Modifier
                MonitorFocusableCard(
                    modifier = Modifier.fillMaxWidth().then(restoreModifier),
                    onClick = {
                        lastOpenedKey = row.key
                        selectedKey = row.key
                    }
                ) {
                    Text(
                        text = data.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        text = (if (row.isLive) stringResource(R.string.sm_history_active) + " · " else "") +
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(data.startedAtMs)) + " · " +
                            if (row.isLive) stringResource(R.string.sm_history_active)
                            else if (data.failed) stringResource(R.string.sm_history_failed)
                            else stringResource(R.string.sm_history_finished),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    Text(
                        text = stringResource(R.string.sm_history_resolution_hint, data.resolutionHint) +
                            " · " + stringResource(R.string.sm_history_duration, formatTorrentDuration(data.durationMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    Text(
                        text = stringResource(
                            R.string.sm_history_rates,
                            if (data.samples > 0) formatTorrentRate(data.averageDownloadBps) else "—",
                            if (data.samples > 0) formatTorrentRate(data.peakDownloadBps) else "—"
                        ) + " · " + data.rating,
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    Text(
                        text = stringResource(
                            R.string.sm_history_row_network,
                            if (data.samples > 0) formatTorrentRate(data.averageUploadBps) else "—",
                            if (data.samples > 0) data.peakPeers.toString() else "—",
                            if (data.samples > 0) data.peakSeeds.toString() else "—"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    Text(
                        text = stringResource(
                            R.string.sm_history_row_volume,
                            if (data.samples > 0) formatTorrentBytes(data.downloadedBytes) else "—",
                            data.bufferEvents
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { androidx.compose.material3.Text(stringResource(R.string.sm_history_clear_confirm_title)) },
            text = { androidx.compose.material3.Text(stringResource(R.string.sm_history_clear_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearHistory()
                }) {
                    androidx.compose.material3.Text(stringResource(R.string.sm_history_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    androidx.compose.material3.Text(stringResource(R.string.sm_history_cancel))
                }
            }
        )
    }
}
