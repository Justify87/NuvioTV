package com.nuvio.tv.ui.screens.streamingmonitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.core.torrent.TorrentSessionHistory
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.core.torrent.TorrentRateSample
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class StreamingMonitorViewModel @Inject constructor(
    torrentService: TorrentService,
    private val sessionHistory: TorrentSessionHistory,
    torrentSettings: TorrentSettings
) : ViewModel() {
    // Memoize the bounded chart sample series on sample changes, not on every 250ms
    // torrent-state update. No UI-owned polling, engine access, VPN action or persistent store.
    private val plottedSamples = sessionHistory.samples.map(::recentTorrentSamples)

    private val core = combine(
        torrentService.state,
        sessionHistory.live,
        plottedSamples,
        sessionHistory.history,
        sessionHistory.enabled
    ) { state, live, points, history, recordingEnabled ->
        CoreSnapshot(state, live, points, history, recordingEnabled)
    }

    internal val uiState: StateFlow<StreamingMonitorUiState> = combine(
        core,
        torrentSettings.settings
    ) { snapshot, settings ->
        monitorUiState(
            torrent = snapshot.torrent,
            live = snapshot.live,
            points = snapshot.points,
            history = snapshot.history,
            recordingEnabled = snapshot.recordingEnabled,
            p2pEnabled = settings.p2pEnabled
        )
    }.catch {
        // Never surface raw engine exceptions or URLs in the UI.
        emit(StreamingMonitorUiState(torrentPhase = TorrentPhase.ERROR, isLoading = false))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = StreamingMonitorUiState()
    )

    private data class CoreSnapshot(
        val torrent: com.nuvio.tv.core.torrent.TorrentState,
        val live: com.nuvio.tv.core.torrent.TorrentSessionStats?,
        val points: List<TorrentRateSample>,
        val history: List<com.nuvio.tv.core.torrent.TorrentSessionStats>,
        val recordingEnabled: Boolean
    )
}
