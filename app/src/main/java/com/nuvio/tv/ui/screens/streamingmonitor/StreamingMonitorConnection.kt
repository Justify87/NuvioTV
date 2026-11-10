package com.nuvio.tv.ui.screens.streamingmonitor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.torrent.TorrentCacheState
import com.nuvio.tv.core.torrent.TorrentSettingsData
import com.nuvio.tv.core.vpn.TorrentVpnSafety
import com.nuvio.tv.core.vpn.VpnHealth
import com.nuvio.tv.core.vpn.VpnTrafficSample
import com.nuvio.tv.core.vpn.WireGuardInfo
import com.nuvio.tv.core.vpn.canRefreshVerification
import com.nuvio.tv.core.vpn.health
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal enum class VerificationFeedback { SUCCESS, FAILED }

/** VPN statistics are app-scoped WireGuard data; they are never torrent-engine rates. */
internal data class StreamingMonitorConnectionState(
    val health: VpnHealth = VpnHealth.DISCONNECTED,
    val profile: String? = null,
    val verifiedLocation: String? = null,
    val lastVerifiedAt: Long = 0L,
    val lastHandshake: Long = 0L,
    val downloadBytesPerSecond: Long? = null,
    val uploadBytesPerSecond: Long? = null,
    val receivedBytes: Long? = null,
    val sentBytes: Long? = null,
    val samples: List<VpnTrafficSample> = emptyList(),
    val cache: TorrentCacheState = TorrentCacheState(),
    val cacheLimitBytes: Long? = null,
    val p2pEnabled: Boolean? = null,
    val canRefresh: Boolean = false,
    val refreshing: Boolean = false,
    val refreshFeedback: VerificationFeedback? = null
)

/** Bounded, timestamped data from the existing WireGuard monitor; no synthetic points. */
internal fun recentVpnSamples(
    samples: List<VpnTrafficSample>,
    now: Long = System.currentTimeMillis()
): List<VpnTrafficSample> {
    val valid = samples.filter {
        it.timestampMs > 0L && it.timestampMs <= now &&
            now - it.timestampMs <= 60_000L &&
            it.downloadBytesPerSecond >= 0L && it.uploadBytesPerSecond >= 0L
    }.sortedBy { it.timestampMs }
    return valid.takeLast(12)
}

/** Reuse the existing verification rules; suppress any stale or unconfirmed exit location. */
internal fun connectionUiState(
    info: WireGuardInfo,
    traffic: List<VpnTrafficSample>,
    cache: TorrentCacheState,
    settings: TorrentSettingsData,
    now: Long = System.currentTimeMillis()
): StreamingMonitorConnectionState {
    val health = info.health(now)
    val verified = info.status == TorrentVpnSafety.Status.VERIFIED
    val samples = if (verified) recentVpnSamples(traffic, now) else emptyList()
    val measured = samples.isNotEmpty()
    val exit = if (health == VpnHealth.CONNECTED) {
        listOfNotNull(info.exitCity?.takeIf { it.isNotBlank() },
            info.exitCountry?.takeIf { it.isNotBlank() }).joinToString(", ").ifBlank { null }
    } else null
    return StreamingMonitorConnectionState(
        health = health,
        profile = info.profile?.takeIf { it.isNotBlank() },
        verifiedLocation = exit,
        lastVerifiedAt = info.lastVerifiedAt,
        lastHandshake = info.lastHandshake,
        downloadBytesPerSecond = if (measured) info.downloadBytesPerSecond.takeIf { it >= 0L } else null,
        uploadBytesPerSecond = if (measured) info.uploadBytesPerSecond.takeIf { it >= 0L } else null,
        receivedBytes = if (measured) info.receivedBytes.takeIf { it >= 0L } else null,
        sentBytes = if (measured) info.sentBytes.takeIf { it >= 0L } else null,
        samples = samples,
        cache = cache,
        cacheLimitBytes = settings.cacheSize.bytes,
        p2pEnabled = settings.p2pEnabled,
        canRefresh = info.canRefreshVerification()
    )
}

/** Binary units, matching the torrent settings' usage presentation. */
internal fun formatVpnBytes(bytes: Long?, locale: Locale = Locale.getDefault()): String {
    if (bytes == null || bytes < 0L) return "—"
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(locale, "%.1f %s", value, units[index])
}

internal fun formatVpnDate(timestampMs: Long): String = if (timestampMs <= 0L) "—"
    else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(timestampMs))

private val VpnStatusGood = Color(0xFF63D99B)
private val VpnStatusChecking = Color(0xFFF5C56B)
private val VpnStatusError = Color(0xFFFF7777)

@Composable
internal fun StreamingMonitorConnection(
    state: StreamingMonitorConnectionState,
    firstContentRequester: FocusRequester,
    returnToTabsRequester: FocusRequester,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onContentFocusChanged: (Boolean) -> Unit
) {
    val statusLabel = when (state.health) {
        VpnHealth.CONNECTED -> R.string.sm_connection_verified
        VpnHealth.CHECKING -> R.string.sm_connection_checking
        VpnHealth.ERROR -> R.string.sm_connection_error
        VpnHealth.DISCONNECTED -> R.string.sm_connection_disconnected
    }
    val statusColor = when (state.health) {
        VpnHealth.CONNECTED -> VpnStatusGood
        VpnHealth.CHECKING -> VpnStatusChecking
        VpnHealth.ERROR -> VpnStatusError
        VpnHealth.DISCONNECTED -> NuvioTheme.colors.TextSecondary
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth().focusGroup()
            .onFocusChanged { onContentFocusChanged(it.hasFocus) },
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        item(key = "vpn-health") {
            MonitorFocusableCard(
                modifier = Modifier.fillMaxWidth().focusRequester(firstContentRequester)
                    .focusProperties { up = returnToTabsRequester }
            ) {
                Text(stringResource(R.string.sm_connection_health),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextSecondary)
                Text(stringResource(statusLabel),
                    style = MaterialTheme.typography.headlineMedium, color = statusColor)
                Text(stringResource(R.string.sm_connection_scope),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary)
                if (state.p2pEnabled == false) {
                    Text(stringResource(R.string.sm_connection_p2p_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary)
                }
            }
        }
        item(key = "vpn-recheck") {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                Button(onClick = onRefresh, enabled = state.canRefresh && !state.refreshing) {
                    Text(stringResource(if (state.refreshing) R.string.sm_connection_refreshing
                        else R.string.sm_connection_recheck))
                }
                when {
                    state.refreshing -> Text(
                        stringResource(R.string.sm_connection_refreshing_hint),
                        color = NuvioTheme.colors.TextSecondary)
                    state.refreshFeedback == VerificationFeedback.SUCCESS -> Text(
                        stringResource(R.string.sm_connection_recheck_success),
                        color = VpnStatusGood)
                    state.refreshFeedback == VerificationFeedback.FAILED -> Text(
                        stringResource(R.string.sm_connection_recheck_failed),
                        color = VpnStatusError)
                    !state.canRefresh -> Text(
                        stringResource(R.string.sm_connection_recheck_unavailable),
                        color = NuvioTheme.colors.TextSecondary)
                }
            }
        }
        item(key = "vpn-identity") {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                MonitorFocusableCard(Modifier.weight(1f)) {
                    ConnectionField(R.string.sm_connection_profile,
                        state.profile ?: stringResource(R.string.sm_connection_unknown))
                }
                MonitorFocusableCard(Modifier.weight(1f)) {
                    ConnectionField(R.string.sm_connection_exit_location,
                        state.verifiedLocation ?: stringResource(R.string.sm_connection_not_verified))
                }
            }
        }
        item(key = "vpn-timing") {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                MonitorFocusableCard(Modifier.weight(1f)) {
                    ConnectionField(R.string.sm_connection_last_verified,
                        formatVpnDate(state.lastVerifiedAt))
                }
                MonitorFocusableCard(Modifier.weight(1f)) {
                    ConnectionField(R.string.sm_connection_last_handshake,
                        formatVpnDate(state.lastHandshake))
                }
            }
        }
        item(key = "vpn-rates") {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                MonitorFocusableCard(Modifier.weight(1f)) {
                    ConnectionField(R.string.sm_connection_vpn_download,
                        formatTorrentRate(state.downloadBytesPerSecond))
                }
                MonitorFocusableCard(Modifier.weight(1f)) {
                    ConnectionField(R.string.sm_connection_vpn_upload,
                        formatTorrentRate(state.uploadBytesPerSecond))
                }
            }
        }
        item(key = "vpn-totals") {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                MonitorFocusableCard(Modifier.weight(1f)) {
                    ConnectionField(R.string.sm_connection_received,
                        formatVpnBytes(state.receivedBytes))
                }
                MonitorFocusableCard(Modifier.weight(1f)) {
                    ConnectionField(R.string.sm_connection_sent,
                        formatVpnBytes(state.sentBytes))
                }
            }
        }
        item(key = "vpn-chart") {
            MonitorFocusableCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sm_connection_chart_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary)
                VpnSampleChart(state.samples)
                Text(stringResource(R.string.sm_connection_traffic_source),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary)
            }
        }
        item(key = "torrent-cache") {
            MonitorFocusableCard(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sm_connection_cache_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary)
                if (!state.cache.hasMeasurement) {
                    Text(stringResource(R.string.sm_connection_cache_unknown),
                        color = NuvioTheme.colors.TextSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)) {
                    Column(Modifier.weight(1f)) {
                        ConnectionField(R.string.sm_connection_cache_used,
                            formatVpnBytes(state.cache.usedBytes.takeIf { state.cache.hasMeasurement }))
                    }
                    Column(Modifier.weight(1f)) {
                        ConnectionField(R.string.sm_connection_cache_protected,
                            formatVpnBytes(state.cache.protectedBytes.takeIf { state.cache.hasMeasurement }))
                    }
                    Column(Modifier.weight(1f)) {
                        ConnectionField(R.string.sm_connection_cache_limit,
                            state.cacheLimitBytes?.let { if (it == 0L)
                                stringResource(R.string.sm_connection_cache_disabled)
                                else formatVpnBytes(it)
                            } ?: stringResource(R.string.sm_connection_unknown))
                    }
                }
                if (state.cache.isClearing) {
                    Text(stringResource(R.string.sm_connection_cache_clearing),
                        color = VpnStatusChecking)
                }
                Text(stringResource(R.string.sm_connection_cache_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary)
            }
        }
    }
}

@Composable
private fun ConnectionField(label: Int, value: String) {
    Text(stringResource(label), color = NuvioTheme.colors.TextSecondary,
        style = MaterialTheme.typography.titleMedium, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
    Text(value, color = NuvioTheme.colors.TextPrimary,
        style = MaterialTheme.typography.titleLarge, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
}

@Composable
private fun VpnSampleChart(samples: List<VpnTrafficSample>) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)) {
            Text(stringResource(R.string.sm_connection_vpn_download),
                color = MonitorDownloadColor, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.sm_connection_vpn_upload),
                color = MonitorUploadColor, style = MaterialTheme.typography.bodySmall)
        }
        if (samples.isEmpty()) {
            Text(stringResource(R.string.sm_connection_chart_empty),
                color = NuvioTheme.colors.TextSecondary)
            return@Column
        }
        val peak = samples.fold(1L) { max, sample ->
            maxOf(max, sample.downloadBytesPerSecond, sample.uploadBytesPerSecond)
        }
        val spanSeconds = if (samples.size < 2) 0L
            else (samples.last().timestampMs - samples.first().timestampMs) / 1000L
        Text(stringResource(R.string.sm_connection_chart_evidence, samples.size,
            spanSeconds, formatTorrentRate(peak)),
            color = NuvioTheme.colors.TextSecondary,
            style = MaterialTheme.typography.bodySmall)
        Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
            val top = 2.dp.toPx()
            val bottom = size.height - top
            val height = (bottom - top).coerceAtLeast(1f)
            for (step in 0..2) {
                val y = top + height * step / 2f
                drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, y),
                    Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val first = samples.first().timestampMs
            val span = (samples.last().timestampMs - first).coerceAtLeast(1L)
            fun position(sample: VpnTrafficSample, rate: Long): Offset {
                val x = if (samples.size == 1) size.width
                    else ((sample.timestampMs - first).toDouble() / span).toFloat() * size.width
                val proportion = (rate.toDouble() / peak).toFloat().coerceIn(0f, 1f)
                return Offset(x, bottom - proportion * height)
            }
            fun plot(color: Color, read: (VpnTrafficSample) -> Long) {
                if (samples.size == 1) {
                    drawCircle(color, 3.dp.toPx(), position(samples[0], read(samples[0])))
                } else {
                    samples.zipWithNext().forEach { (start, end) ->
                        drawLine(color, position(start, read(start)), position(end, read(end)),
                            strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
            }
            plot(MonitorDownloadColor) { it.downloadBytesPerSecond }
            plot(MonitorUploadColor) { it.uploadBytesPerSecond }
        }
    }
}
