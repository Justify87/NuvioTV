@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.streamingmonitor

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme

/** Fixed tab order so saved tab indices remain stable across screen recreation. */
internal enum class StreamingMonitorTab(@StringRes val titleRes: Int) {
    Overview(R.string.streaming_monitor_tab_overview),
    History(R.string.streaming_monitor_tab_history),
    Connection(R.string.streaming_monitor_tab_connection)
}

internal fun streamingMonitorTabOrOverview(index: Int): StreamingMonitorTab =
    StreamingMonitorTab.entries.getOrNull(index) ?: StreamingMonitorTab.Overview

/**
 * NavHost entry. Lifecycle-aware subscription; no UI timer or direct TorrentService call.
 * The child is intentionally pure so Android-TV D-pad tests need no mocked Hilt graph.
 */
@Composable
fun StreamingMonitorScreen(
    showBuiltInHeader: Boolean = true,
    viewModel: StreamingMonitorViewModel = hiltViewModel()
) {
    val overviewState by viewModel.uiState.collectAsStateWithLifecycle()
    StreamingMonitorScreenContent(showBuiltInHeader, overviewState, onClearHistory = viewModel::clearSavedHistory)
}

/** M02 owns the Overview. History and Connection stay neutral until M03/M05. */
@Composable
internal fun StreamingMonitorScreenContent(
    showBuiltInHeader: Boolean = true,
    overviewState: StreamingMonitorUiState = StreamingMonitorUiState(isLoading = false),
    onClearHistory: () -> Unit = {}
) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(StreamingMonitorTab.Overview.ordinal) }
    var lastFocusedTabIndex by rememberSaveable { mutableIntStateOf(StreamingMonitorTab.Overview.ordinal) }
    var restoreContentFocus by rememberSaveable { mutableStateOf(false) }
    var contentFocused by remember { mutableStateOf(false) }
    var historyDetailOpen by remember { mutableStateOf(false) }

    val tabRequesters = remember { List(StreamingMonitorTab.entries.size) { FocusRequester() } }
    val contentRequester = remember { FocusRequester() }
    val shellFocusRequester = LocalContentFocusRequester.current
    val sidebarExpanded = LocalSidebarExpanded.current
    val focusedTabIndex = streamingMonitorTabOrOverview(lastFocusedTabIndex).ordinal
    val selectedTab = streamingMonitorTabOrOverview(selectedTabIndex)

    LaunchedEffect(sidebarExpanded) {
        if (!sidebarExpanded) {
            withFrameNanos { }
            val target = if (historyDetailOpen || restoreContentFocus) contentRequester else tabRequesters[focusedTabIndex]
            runCatching { target.requestFocus() }
        }
    }

    BackHandler(enabled = contentFocused && !sidebarExpanded && !historyDetailOpen) {
        restoreContentFocus = false
        contentFocused = false
        tabRequesters[focusedTabIndex].requestFocus()
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(
                start = NuvioTheme.spacing.screen.horizontal,
                end = NuvioTheme.spacing.screen.horizontal,
                top = if (showBuiltInHeader) NuvioTheme.spacing.screen.vertical else NuvioTheme.spacing.huge,
                bottom = NuvioTheme.spacing.screen.vertical
            ),
        verticalArrangement = Arrangement.Top
    ) {
        if (!historyDetailOpen) {
            Text(
                text = stringResource(R.string.nav_streaming_monitor),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary
            )
            Spacer(Modifier.height(NuvioTheme.spacing.xl))
            StreamingMonitorTabs(
                selectedTab = selectedTab,
                tabRequesters = tabRequesters,
                contentRequester = contentRequester,
                modifier = Modifier.focusRequester(shellFocusRequester).focusGroup(),
                onFocused = { index ->
                    lastFocusedTabIndex = index
                    restoreContentFocus = false
                },
                onSelect = { selectedTabIndex = it }
            )
            Spacer(Modifier.height(NuvioTheme.spacing.xxl))
        }
        if (selectedTab == StreamingMonitorTab.Overview) {
            StreamingMonitorOverview(
                state = overviewState,
                firstContentRequester = contentRequester,
                returnToTabsRequester = tabRequesters[focusedTabIndex],
                modifier = Modifier.weight(1f),
                onContentFocusChanged = { hasFocus ->
                    contentFocused = hasFocus
                    if (hasFocus) restoreContentFocus = true
                }
            )
        } else if (selectedTab == StreamingMonitorTab.History) {
            StreamingMonitorHistory(
                saved = overviewState.savedSessions,
                live = overviewState.activeSession,
                liveSamples = overviewState.samplePoints,
                recordingEnabled = overviewState.historyRecordingEnabled,
                onClearHistory = onClearHistory,
                onDetailVisibilityChanged = { historyDetailOpen = it },
                firstContentRequester = contentRequester,
                returnToTabsRequester = tabRequesters[focusedTabIndex],
                modifier = Modifier.weight(1f),
                onContentFocusChanged = { hasFocus ->
                    contentFocused = hasFocus
                    if (hasFocus) restoreContentFocus = true
                }
            )
        } else {
            StreamingMonitorEmptyPanel(
                tab = selectedTab,
                focused = contentFocused,
                modifier = Modifier.fillMaxWidth()
                    .heightIn(min = 208.dp)
                    .focusRequester(contentRequester)
                    .focusProperties { up = tabRequesters[focusedTabIndex] }
                    .onFocusChanged { state ->
                        contentFocused = state.isFocused
                        if (state.isFocused) restoreContentFocus = true
                    }
                    .focusable()
            )
        }
    }
}

@Composable
private fun StreamingMonitorTabs(
    selectedTab: StreamingMonitorTab,
    tabRequesters: List<FocusRequester>,
    contentRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onFocused: (Int) -> Unit,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        StreamingMonitorTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            Button(
                onClick = { onSelect(tab.ordinal) },
                modifier = Modifier.widthIn(min = 150.dp)
                    .heightIn(min = 52.dp)
                    .focusRequester(tabRequesters[tab.ordinal])
                    .focusProperties { down = contentRequester }
                    .onFocusChanged { if (it.isFocused) onFocused(tab.ordinal) }
                    .semantics {
                        role = Role.Tab
                        this.selected = selected
                    },
                colors = ButtonDefaults.colors(
                    containerColor = if (selected) NuvioTheme.colors.FocusBackground
                        else NuvioTheme.colors.BackgroundCard,
                    contentColor = NuvioTheme.colors.TextPrimary,
                    focusedContainerColor = NuvioTheme.colors.FocusBackground,
                    focusedContentColor = NuvioTheme.colors.TextPrimary
                )
            ) {
                Text(text = stringResource(tab.titleRes), maxLines = 1)
            }
        }
    }
}

@Composable
private fun StreamingMonitorEmptyPanel(
    tab: StreamingMonitorTab,
    focused: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.spacing.lg)
    val message = when (tab) {
        StreamingMonitorTab.History -> R.string.streaming_monitor_m01_history
        StreamingMonitorTab.Connection -> R.string.streaming_monitor_m01_connection
        StreamingMonitorTab.Overview -> error("Overview has its dedicated M02 screen")
    }
    Column(
        modifier = modifier
            .background(NuvioTheme.colors.BackgroundCard, shape)
            .border(2.dp, if (focused) NuvioTheme.colors.FocusRing else Color.Transparent, shape)
            .padding(NuvioTheme.spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Text(
            text = stringResource(tab.titleRes),
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary
        )
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyLarge,
            color = NuvioTheme.colors.TextSecondary
        )
    }
}
