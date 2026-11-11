package com.nuvio.tv.ui.screens.streamingmonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun StreamingMonitorOverview(
    state: StreamingMonitorUiState,
    firstContentRequester: FocusRequester,
    returnToTabsRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onContentFocusChanged: (Boolean) -> Unit
) {
    var selectedKpi by rememberSaveable { mutableIntStateOf(-1) }
    val selectedDetailRes = when (selectedKpi) {
        0 -> R.string.sm_detail_download
        1 -> R.string.sm_detail_upload
        2 -> R.string.sm_detail_peers
        3 -> R.string.sm_detail_seeds
        4 -> R.string.sm_detail_buffer
        else -> null
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth()
            .onFocusChanged { onContentFocusChanged(it.hasFocus) }
            .focusGroup(),
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        item(key = "status") {
            MonitorStatusCard(
                state = state,
                modifier = Modifier.fillMaxWidth()
                    .focusRequester(firstContentRequester)
                    .focusProperties { up = returnToTabsRequester }
            )
        }

        item(key = "rates") {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                MonitorKpiCard(
                    stringResource(R.string.sm_download),
                    formatTorrentRate(state.liveRates?.downloadBytesPerSecond),
                    stringResource(R.string.sm_source_torrent),
                    modifier = Modifier.weight(1f),
                    onClick = { selectedKpi = 0 }
                )
                MonitorKpiCard(
                    stringResource(R.string.sm_upload),
                    formatTorrentRate(state.liveRates?.uploadBytesPerSecond),
                    stringResource(R.string.sm_source_torrent),
                    modifier = Modifier.weight(1f),
                    onClick = { selectedKpi = 1 }
                )
            }
        }
        item(key = "connections") {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                MonitorKpiCard(
                    stringResource(R.string.sm_peers),
                    state.connectedPeers?.toString() ?: "—",
                    stringResource(R.string.sm_connected_only),
                    modifier = Modifier.weight(1f),
                    onClick = { selectedKpi = 2 }
                )
                MonitorKpiCard(
                    stringResource(R.string.sm_seeds),
                    state.connectedSeeds?.toString() ?: "—",
                    stringResource(R.string.sm_connected_only),
                    modifier = Modifier.weight(1f),
                    onClick = { selectedKpi = 3 }
                )
                MonitorKpiCard(
                    stringResource(R.string.sm_buffer_events),
                    state.recordedBufferInterruptions?.toString() ?: "—",
                    stringResource(R.string.sm_buffer_note),
                    modifier = Modifier.weight(1f),
                    onClick = { selectedKpi = 4 }
                )
            }
        }
        item(key = "playback-quality") {
            val current = state.activeSession
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                MonitorFocusableCard(Modifier.weight(1f).heightIn(min = 120.dp)) {
                    Text(stringResource(R.string.sm_quality_start),
                        style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary)
                    Text(current?.firstFrameMs?.let {
                        String.format(java.util.Locale.getDefault(), "%.2f s", it / 1000.0)
                    } ?: stringResource(R.string.sm_quality_unavailable),
                        style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary)
                }
                MonitorFocusableCard(Modifier.weight(1f).heightIn(min = 120.dp)) {
                    Text(stringResource(R.string.sm_quality_buffer_duration),
                        style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary)
                    Text(current?.rebufferTotalMs?.let {
                        String.format(java.util.Locale.getDefault(), "%.2f s", it / 1000.0)
                    } ?: stringResource(R.string.sm_quality_unavailable),
                        style = MaterialTheme.typography.headlineMedium, color = NuvioTheme.colors.TextPrimary)
                }
            }
        }
        if (selectedDetailRes != null) {
            item(key = "selected-kpi-details") {
                MonitorFocusableCard(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sm_kpi_detail_heading),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary)
                    Text(stringResource(selectedDetailRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary)
                }
            }
        }
        item(key = "chart") {
            MonitorFocusableCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sm_chart_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary)
                StreamingMonitorRateChart(state.samplePoints)
                Text(stringResource(R.string.sm_chart_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary)
            }
        }
        item(key = "history") {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                MonitorFocusableCard(Modifier.weight(1f).heightIn(min = 140.dp)) {
                    Text(stringResource(R.string.sm_recorded_sessions),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextSecondary)
                    Text(state.recentCount.toString(), style = MaterialTheme.typography.headlineLarge,
                        color = NuvioTheme.colors.TextPrimary)
                    Text(
                        stringResource(
                            if (state.historyRecordingEnabled) R.string.sm_recording_on
                            else R.string.sm_recording_off
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
                MonitorFocusableCard(Modifier.weight(1f).heightIn(min = 140.dp)) {
                    Text(stringResource(R.string.sm_stability_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextSecondary)
                    Text(
                        state.summary.percentage?.let { "$it %" }
                            ?: stringResource(R.string.sm_no_data),
                        style = MaterialTheme.typography.headlineLarge,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        stringResource(
                            R.string.sm_kpi_basis_v2,
                            state.summary.withoutRecordedInterruption,
                            state.summary.sessionsWithMeasurements
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }
        item(key = "success") {
            MonitorFocusableCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sm_success_rate_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextSecondary)
                Text(state.success.percentage?.let { "$it %" }
                    ?: stringResource(R.string.sm_no_data),
                    style = MaterialTheme.typography.headlineLarge,
                    color = NuvioTheme.colors.TextPrimary)
                Text(stringResource(R.string.sm_success_rate_basis,
                    state.success.finishedWithFirstFrame,
                    state.success.sessionsWithKnownOutcome),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary)
            }
        }
        item(key = "privacy") {
            MonitorFocusableCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sm_privacy_heading),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary)
                Text(
                    stringResource(
                        if (state.historyRecordingEnabled) R.string.sm_privacy_on
                        else R.string.sm_privacy_off
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary
                )
                Text(stringResource(R.string.sm_stability_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary)
            }
        }
    }
}

@Composable
private fun MonitorStatusCard(state: StreamingMonitorUiState, modifier: Modifier = Modifier) {
    val status = when {
        state.isLoading -> R.string.sm_status_loading
        state.torrentPhase == TorrentPhase.DISABLED -> R.string.sm_status_disabled
        state.torrentPhase == TorrentPhase.IDLE -> R.string.sm_status_idle
        state.torrentPhase == TorrentPhase.CONNECTING -> R.string.sm_status_connecting
        state.torrentPhase == TorrentPhase.STREAMING -> R.string.sm_status_streaming
        else -> R.string.sm_status_error
    }
    MonitorFocusableCard(modifier) {
        Text(stringResource(status),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary)
        state.activeSession?.let { session ->
            Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioTheme.colors.TextSecondary)
        }
        if (state.torrentPhase == TorrentPhase.CONNECTING) {
            Text(stringResource(R.string.sm_waiting_for_rates),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary)
        } else if (state.torrentPhase == TorrentPhase.STREAMING && state.activeSession == null) {
            Text(stringResource(R.string.sm_filename_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary)
        }
    }
}

@Composable
private fun MonitorKpiCard(
    label: String,
    value: String,
    note: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    MonitorFocusableCard(modifier.heightIn(min = 120.dp), onClick = onClick) {
        Text(label, style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        Text(value, style = MaterialTheme.typography.headlineMedium,
            color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(note, style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary, maxLines = 2)
        Text(stringResource(R.string.sm_press_ok_for_details),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary, maxLines = 1)
    }
}

@Composable
internal fun MonitorFocusableCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(NuvioTheme.spacing.lg)
    var focused by remember { mutableStateOf(false) }
    val focusModifier = if (onClick == null) Modifier.focusable()
        else Modifier.clickable(onClick = onClick)
    Column(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .then(focusModifier)
            .background(NuvioTheme.colors.BackgroundCard, shape)
            .border(2.dp, if (focused) NuvioTheme.colors.FocusRing else Color.Transparent, shape)
            .padding(NuvioTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        content = content
    )
}
