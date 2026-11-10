package com.nuvio.tv.ui.screens.streamingmonitor

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.torrent.TorrentRateSample
import com.nuvio.tv.core.torrent.TorrentSessionStats
import com.nuvio.tv.core.torrent.TorrentSessionOutcome
import com.nuvio.tv.core.torrent.assessStreamingSession
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal fun formatTorrentDuration(durationMs: Long): String {
    if (durationMs < 0L) return "—"
    val seconds = durationMs / 1_000L
    val hours = seconds / 3_600L
    val minutes = (seconds / 60L) % 60L
    val remainder = seconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, remainder)
    else "%d:%02d".format(Locale.ROOT, seconds / 60L, remainder)
}

internal fun formatTorrentBytes(bytes: Long): String {
    if (bytes < 0L) return "—"
    val amount = bytes.toDouble() / 1_048_576.0
    return String.format(Locale.getDefault(), "%.1f MiB", amount)
}

/** An in-route full-height detail, not an AlertDialog or a rebranded legacy popup. */
@Composable
internal fun StreamingMonitorSessionDetail(
    entry: StreamingHistoryEntry,
    liveSamples: List<TorrentRateSample>,
    firstContentRequester: FocusRequester,
    returnToTabsRequester: FocusRequester,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onContentFocusChanged: (Boolean) -> Unit
) {
    val session = entry.session
    val measured = session.samples > 0
    val unknown = "—"

    LaunchedEffect(entry.key) {
        withFrameNanos { }
        runCatching { firstContentRequester.requestFocus() }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth().focusGroup()
            .onFocusChanged { onContentFocusChanged(it.hasFocus) },
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        item(key = "detail-heading") {
            MonitorFocusableCard(
                modifier = Modifier.fillMaxWidth().testTag("streaming_history_detail_back")
                    .focusRequester(firstContentRequester)
                    .focusProperties { up = returnToTabsRequester },
                onClick = onBack
            ) {
                Text(
                    stringResource(R.string.sm_history_back),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.MEDIUM)
                        .format(Date(session.startedAtMs)),
                    color = NuvioTheme.colors.TextSecondary
                )
                Text(
                    sessionOutcomeText(session, entry.isLive),
                    color = NuvioTheme.colors.TextSecondary
                )
            }
        }
        item(key = "detail-metrics") {
            MonitorFocusableCard(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.sm_history_metrics),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                DetailMetric(R.string.sm_history_duration_label, formatTorrentDuration(session.durationMs))
                DetailMetric(R.string.sm_history_resolution_label, session.resolutionHint)
                DetailMetric(R.string.sm_history_download_avg, if (measured) formatTorrentRate(session.averageDownloadBps) else unknown)
                DetailMetric(R.string.sm_history_download_peak, if (measured) formatTorrentRate(session.peakDownloadBps) else unknown)
                DetailMetric(R.string.sm_history_upload_avg, if (measured) formatTorrentRate(session.averageUploadBps) else unknown)
                DetailMetric(R.string.sm_history_peers_peak, if (measured) session.peakPeers.toString() else unknown)
                DetailMetric(R.string.sm_history_seeds_peak, if (measured) session.peakSeeds.toString() else unknown)
                DetailMetric(R.string.sm_history_downloaded, if (measured) formatTorrentBytes(session.downloadedBytes) else unknown)
                if (session.telemetryVersion < 2) {
                    DetailMetric(R.string.sm_history_interruptions, session.bufferEvents.toString())
                    DetailMetric(R.string.sm_quality_legacy, session.rating)
                }
                DetailMetric(R.string.sm_history_samples, session.samples.toString())
            }
        }
        item(key = "detail-quality") {
            MonitorFocusableCard(Modifier.fillMaxWidth()) {
                val media = session.mediaProfile
                Text(
                    stringResource(R.string.sm_quality_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                val unavailable = stringResource(R.string.sm_quality_unavailable)
                DetailMetric(R.string.sm_quality_resolution, media?.resolution ?: unavailable)
                DetailMetric(R.string.sm_quality_video_codec, media?.videoCodec ?: unavailable)
                DetailMetric(R.string.sm_quality_hdr, media?.hdr?.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") ?: unavailable)
                DetailMetric(R.string.sm_quality_audio_codec, media?.audioCodec ?: unavailable)
                DetailMetric(R.string.sm_quality_audio_channels, media?.audioChannels?.toString() ?: unavailable)
                DetailMetric(R.string.sm_quality_video_bitrate,
                    media?.videoBitrateBitsPerSecond?.let { "$it bit/s" } ?: unavailable)
                Text(stringResource(R.string.sm_quality_media_source),
                    style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
            }
        }
        item(key = "detail-stability") {
            MonitorFocusableCard(Modifier.fillMaxWidth()) {
                val assessment = assessStreamingSession(session)
                val unavailable = stringResource(R.string.sm_quality_unavailable)
                Text(stringResource(R.string.sm_quality_stability),
                    style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextPrimary)
                Text(assessment.label,
                    style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary)
                Text(assessment.explanation,
                    style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
                DetailMetric(R.string.sm_quality_start,
                    session.firstFrameMs?.let { String.format(Locale.getDefault(), "%.2f s", it / 1000.0) }
                        ?: unavailable)
                DetailMetric(R.string.sm_quality_rebuffers, session.rebufferCount?.toString() ?: unavailable)
                DetailMetric(R.string.sm_quality_buffer_duration,
                    session.rebufferTotalMs?.let { String.format(Locale.getDefault(), "%.2f s", it / 1000.0) }
                        ?: unavailable)
                assessment.downloadToVideoBitrateRatio?.let { ratio ->
                    DetailMetric(R.string.sm_quality_ratio,
                        String.format(Locale.getDefault(), "%.2f×", ratio))
                    Text(stringResource(R.string.sm_quality_ratio_note),
                        style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary)
                }
            }
        }
        if (entry.isLive && liveSamples.isNotEmpty()) {
            item(key = "detail-active-chart") {
                MonitorFocusableCard(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.sm_chart_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    StreamingMonitorRateChart(recentTorrentSamples(liveSamples))
                    Text(
                        stringResource(R.string.sm_history_live_chart),
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }
        item(key = "detail-provenance") {
            MonitorFocusableCard(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.sm_history_sources_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Text(
                    stringResource(R.string.sm_history_sources_engine),
                    color = NuvioTheme.colors.TextSecondary
                )
                Text(
                    stringResource(R.string.sm_history_sources_resolution),
                    color = NuvioTheme.colors.TextSecondary
                )
                Text(
                    stringResource(
                        if (session.telemetryVersion >= 2) R.string.sm_quality_timing_source
                        else R.string.sm_history_sources_rating
                    ),
                    color = NuvioTheme.colors.TextSecondary
                )
                Text(
                    stringResource(
                        if (measured) R.string.sm_history_sources_recorded
                        else R.string.sm_history_sources_unmeasured
                    ),
                    color = NuvioTheme.colors.TextSecondary
                )
                if (!entry.isLive) {
                    Text(
                        stringResource(R.string.sm_history_no_archived_chart),
                        color = NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailMetric(label: Int, value: String) {
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextPrimary)
    }
}

/** Playback outcome is separate from the old torrent-service failed boolean. */
@Composable
internal fun sessionOutcomeText(session: TorrentSessionStats, live: Boolean): String =
    stringResource(when {
        live -> R.string.sm_history_active
        session.telemetryVersion < 2 && session.failed -> R.string.sm_outcome_failed
        session.telemetryVersion < 2 -> R.string.sm_outcome_legacy
        session.outcome == TorrentSessionOutcome.CANCELLED_BEFORE_PLAYBACK -> R.string.sm_outcome_cancelled
        session.outcome == TorrentSessionOutcome.FAILED -> R.string.sm_outcome_failed
        session.outcome == TorrentSessionOutcome.FINISHED -> R.string.sm_outcome_finished
        else -> R.string.sm_outcome_unknown
    })
