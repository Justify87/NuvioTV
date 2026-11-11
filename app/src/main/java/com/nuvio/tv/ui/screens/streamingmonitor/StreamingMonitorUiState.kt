package com.nuvio.tv.ui.screens.streamingmonitor

import com.nuvio.tv.core.torrent.TorrentRateSample
import com.nuvio.tv.core.torrent.TorrentSessionStats
import com.nuvio.tv.core.torrent.TorrentState
import java.util.Locale

/** Session health is based only on correlated player events, never filename hints. */
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
 * Denominator: persisted v2 sessions with a verified first frame and complete
 * post-frame rebuffer count/duration. Failed finishes remain in the denominator.
 * Numerator: those finished normally with no observed interruptions.
 * Legacy sessions, startup failures and unknown timing cannot raise the KPI.
 */
internal fun recordedStability(history: List<TorrentSessionStats>): RecordedStability {
    val eligible = history.filter {
        it.telemetryVersion >= 2 && it.firstFrameMs != null &&
            it.rebufferCount != null && it.rebufferTotalMs != null &&
            !it.rebufferIncomplete
    }
    val clean = eligible.count {
        it.outcome == com.nuvio.tv.core.torrent.TorrentSessionOutcome.FINISHED &&
            it.rebufferCount == 0
    }
    return RecordedStability(eligible.size, clean)
}

/**
 * Success is counted only for persisted v2 sessions with a known terminal outcome.
 * The denominator includes failed starts and pre-frame cancellations; an unverified
 * successful finish is not treated as proven playback. Unknown outcomes and legacy
 * entries are excluded rather than guessed.
 */
internal data class RecordedSuccess(
    val sessionsWithKnownOutcome: Int = 0,
    val finishedWithFirstFrame: Int = 0
) {
    val percentage: Int?
        get() = if (sessionsWithKnownOutcome == 0) null
            else (finishedWithFirstFrame * 100) / sessionsWithKnownOutcome
}

internal fun recordedSuccess(history: List<TorrentSessionStats>): RecordedSuccess {
    val known = history.filter {
        it.telemetryVersion >= 2 &&
            it.outcome != com.nuvio.tv.core.torrent.TorrentSessionOutcome.UNKNOWN
    }
    val finished = known.count {
        it.outcome == com.nuvio.tv.core.torrent.TorrentSessionOutcome.FINISHED &&
            it.firstFrameMs != null
    }
    return RecordedSuccess(known.size, finished)
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
    val savedSessions: List<TorrentSessionStats> = emptyList(),
    val summary: RecordedStability = RecordedStability(),
    val success: RecordedSuccess = RecordedSuccess(),
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
    // The initial engine state may contain defaults. A session sample confirms observation.
    val measured = phase == TorrentPhase.STREAMING && (live?.samples ?: 0) > 0
    val rates = if (measured) torrentRates(torrent) else null
    val stream = (torrent as? TorrentState.Streaming).takeIf { measured }
    return StreamingMonitorUiState(
        activeSession = live.takeIf { active },
        torrentPhase = phase,
        liveRates = rates,
        samplePoints = if (measured && live != null) points else emptyList(),
        connectedPeers = stream?.peers?.takeIf { it >= 0 },
        connectedSeeds = stream?.seeds?.takeIf { it >= 0 },
        recordedBufferInterruptions = live?.rebufferCount?.takeIf { active && it >= 0 },
        recentCount = history.size,
        savedSessions = history,
        summary = recordedStability(history),
        success = recordedSuccess(history),
        historyRecordingEnabled = recordingEnabled,
        p2pEnabled = p2pEnabled,
        isLoading = false,
        hasEngineMeasurement = rates?.downloadBytesPerSecond != null ||
            rates?.uploadBytesPerSecond != null,
        hasRecordedMeasurements = history.any { it.samples > 0 && it.durationMs > 0L }
    )
}
