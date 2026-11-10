package com.nuvio.tv.ui.screens.streamingmonitor

import com.nuvio.tv.core.torrent.TorrentRateSample
import com.nuvio.tv.core.torrent.TorrentSessionStats
import com.nuvio.tv.core.torrent.TorrentState
import java.util.Locale

/** No player first-frame or post-first-frame buffering correlation exists until M04. */
internal enum class TorrentPhase { DISABLED, IDLE, CONNECTING, STREAMING, ERROR }

internal data class TorrentLiveRates(
    val downloadBytesPerSecond: Long?,
    val uploadBytesPerSecond: Long?
)

internal data class RecordedStability(
    val sessionsWithMeasurements: Int = 0,
    val withoutRecordedInterruption: Int = 0
) {
    val percentage: Int?
        get() = if (sessionsWithMeasurements == 0) null
            else (withoutRecordedInterruption * 100) / sessionsWithMeasurements
}

/**
 * Numerator: saved sessions with >0 samples, positive duration, no recorded buffering,
 * and no terminal failure. Denominator: ALL saved sessions with >0 samples and positive
 * duration, including sessions that later failed. Unmeasured starts are excluded, never
 * treated as clean playback. This is NOT a playback-success/first-frame rate.
 */
internal fun recordedStability(history: List<TorrentSessionStats>): RecordedStability {
    var eligible = 0
    var clean = 0
    for (session in history) {
        if (session.samples <= 0 || session.durationMs <= 0L) continue
        eligible++
        if (!session.failed && session.bufferEvents == 0) clean++
    }
    return RecordedStability(eligible, clean)
}

/** This is the torrent-engine byte rate, never the app VPN throughput. */
internal fun torrentRates(state: TorrentState): TorrentLiveRates? = when (state) {
    is TorrentState.Streaming -> TorrentLiveRates(
        state.downloadSpeed.takeIf { it >= 0L },
        state.uploadSpeed.takeIf { it >= 0L }
    )
    // Connecting's initial zero-valued state is not a measurement.
    else -> null
}

/** Never include negative/invalid samples; order by real timestamp and cap to 60 seconds. */
internal fun recentTorrentSamples(samples: List<TorrentRateSample>): List<TorrentRateSample> {
    if (samples.isEmpty()) return emptyList()
    val valid = samples.filter {
        it.timestampMs > 0L && it.downloadBytesPerSecond >= 0L &&
            it.uploadBytesPerSecond >= 0L && it.peers >= 0
    }.sortedBy { it.timestampMs }
    val latest = valid.lastOrNull()?.timestampMs ?: return emptyList()
    val windowStart = (latest - 60_000L).coerceAtLeast(0L)
    return valid.filter { it.timestampMs >= windowStart }.takeLast(60)
}

/** Binary units are explicit: 1 KiB=1024 bytes, 1 MiB=1024 KiB. */
internal fun formatTorrentRate(
    bytesPerSecond: Long?,
    locale: Locale = Locale.getDefault()
): String {
    if (bytesPerSecond == null || bytesPerSecond < 0L) return "—"
    if (bytesPerSecond < 1024L) return "$bytesPerSecond B/s"
    var scaled = bytesPerSecond.toDouble()
    var index = 0
    val units = arrayOf("B/s", "KiB/s", "MiB/s", "GiB/s", "TiB/s", "PiB/s", "EiB/s")
    while (scaled >= 1024.0 && index < units.lastIndex) {
        scaled /= 1024.0
        index++
    }
    return String.format(locale, "%.1f %s", scaled, units[index])
}

internal data class StreamingMonitorUiState(
    val activeSession: TorrentSessionStats? = null,
    val torrentPhase: TorrentPhase = TorrentPhase.IDLE,
    val liveRates: TorrentLiveRates? = null,
    val samplePoints: List<TorrentRateSample> = emptyList(),
    val connectedPeers: Int? = null,
    val connectedSeeds: Int? = null,
    val recordedBufferInterruptions: Int? = null,
    val recentCount: Int = 0,
    val summary: RecordedStability = RecordedStability(),
    val historyRecordingEnabled: Boolean = false,
    val p2pEnabled: Boolean? = null,
    val isLoading: Boolean = true,
    val hasEngineMeasurement: Boolean = false,
    val hasRecordedMeasurements: Boolean = false
)

/** Pure mapper for both the flow collector and state/empty-state unit tests. */
internal fun monitorUiState(
    torrent: TorrentState,
    live: TorrentSessionStats?,
    points: List<TorrentRateSample>,
    history: List<TorrentSessionStats>,
    recordingEnabled: Boolean,
    p2pEnabled: Boolean
): StreamingMonitorUiState {
    val phase = when (torrent) {
        TorrentState.Idle -> if (p2pEnabled) TorrentPhase.IDLE else TorrentPhase.DISABLED
        is TorrentState.Connecting -> TorrentPhase.CONNECTING
        is TorrentState.Streaming -> TorrentPhase.STREAMING
        is TorrentState.Error -> TorrentPhase.ERROR
    }
    val active = phase == TorrentPhase.CONNECTING || phase == TorrentPhase.STREAMING
    val measured = phase == TorrentPhase.STREAMING
    val rates = if (measured) torrentRates(torrent) else null
    val stream = torrent as? TorrentState.Streaming
    return StreamingMonitorUiState(
        activeSession = live.takeIf { active },
        torrentPhase = phase,
        liveRates = rates,
        samplePoints = if (measured && live != null) points else emptyList(),
        connectedPeers = stream?.peers?.takeIf { it >= 0 },
        connectedSeeds = stream?.seeds?.takeIf { it >= 0 },
        recordedBufferInterruptions = live?.bufferEvents?.takeIf { active && it >= 0 },
        recentCount = history.size,
        summary = recordedStability(history),
        historyRecordingEnabled = recordingEnabled,
        p2pEnabled = p2pEnabled,
        isLoading = false,
        hasEngineMeasurement = rates != null,
        hasRecordedMeasurements = history.any { it.samples > 0 && it.durationMs > 0L }
    )
}
