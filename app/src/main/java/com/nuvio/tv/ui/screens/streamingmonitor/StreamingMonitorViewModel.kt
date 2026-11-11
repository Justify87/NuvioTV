package com.nuvio.tv.ui.screens.streamingmonitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.torrent.TorrentService
import com.nuvio.tv.core.torrent.TorrentSessionHistory
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.core.torrent.TorrentRateSample
import com.nuvio.tv.core.vpn.WireGuardManager
import com.nuvio.tv.core.vpn.canRefreshVerification
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    torrentSettings: TorrentSettings,
    private val wireGuard: WireGuardManager
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


    private data class RefreshAction(
        val running: Boolean = false,
        val feedback: VerificationFeedback? = null
    )

    private val refreshAction = MutableStateFlow(RefreshAction())

    // WireGuard already monitors the tunnel. Collect existing in-memory flows only;
    // opening this screen never creates a poller or starts P2P or the VPN.
    private val connectionSnapshot = combine(
        wireGuard.info, wireGuard.traffic, torrentService.cacheState, torrentSettings.settings
    ) { info, traffic, cache, settings ->
        connectionUiState(info, traffic, cache, settings)
    }

    internal val connectionState: StateFlow<StreamingMonitorConnectionState> = combine(
        connectionSnapshot, refreshAction
    ) { connection, refresh ->
        connection.copy(refreshing = refresh.running, refreshFeedback = refresh.feedback)
    }.catch {
        // A failed collection cannot reveal VPN config or engine details.
        emit(StreamingMonitorConnectionState())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = StreamingMonitorConnectionState()
    )

    /** The only write action on the Connection page: explicit user-requested egress recheck. */
    fun refreshVpnVerification() {
        if (refreshAction.value.running || !wireGuard.info.value.canRefreshVerification()) return
        refreshAction.value = RefreshAction(running = true)
        viewModelScope.launch {
            try {
                wireGuard.refreshVerification()
                refreshAction.value = RefreshAction(feedback = VerificationFeedback.SUCCESS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // No raw exceptions: they may carry private diagnostic details.
                refreshAction.value = RefreshAction(feedback = VerificationFeedback.FAILED)
            } finally {
                refreshAction.value = refreshAction.value.copy(running = false)
            }
        }
    }

    /** Clearing changes only local saved entries; active playback and opt-in are untouched. */
    fun clearSavedHistory() = sessionHistory.clear()

    private data class CoreSnapshot(
        val torrent: com.nuvio.tv.core.torrent.TorrentState,
        val live: com.nuvio.tv.core.torrent.TorrentSessionStats?,
        val points: List<TorrentRateSample>,
        val history: List<com.nuvio.tv.core.torrent.TorrentSessionStats>,
        val recordingEnabled: Boolean
    )
}
