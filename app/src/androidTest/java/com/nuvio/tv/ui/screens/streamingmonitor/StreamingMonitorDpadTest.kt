package com.nuvio.tv.ui.screens.streamingmonitor

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.key.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.core.torrent.TorrentState
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Rule
import org.junit.Test

class StreamingMonitorDpadTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `tabs use dpad focus and ok to change selection`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val overview = context.getString(R.string.streaming_monitor_tab_overview)
        val history = context.getString(R.string.streaming_monitor_tab_history)
        val connection = context.getString(R.string.streaming_monitor_tab_connection)

        composeRule.setContent {
            NuvioTheme { StreamingMonitorScreenContent() }
        }
        composeRule.waitForIdle()
        val overviewTab = hasText(overview) and hasClickAction()
        val historyTab = hasText(history) and hasClickAction()
        val connectionTab = hasText(connection) and hasClickAction()

        composeRule.onNode(overviewTab).assertIsSelected().assertIsFocused()

        composeRule.onNode(overviewTab).performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNode(historyTab).assertIsFocused()
        composeRule.onNode(overviewTab).assertIsSelected()

        composeRule.onNode(historyTab).performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNode(historyTab).assertIsSelected()
        composeRule.onNode(historyTab).performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNode(connectionTab).assertIsFocused()
    }

    @Test
    fun `overview shows genuine empty state and allows dpad entry`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val overview = context.getString(R.string.streaming_monitor_tab_overview)
        val idle = context.getString(R.string.sm_status_idle)

        composeRule.setContent {
            NuvioTheme {
                StreamingMonitorScreenContent(
                    overviewState = monitorUiState(
                        TorrentState.Idle, null, emptyList(), emptyList(),
                        recordingEnabled = false, p2pEnabled = true
                    )
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNode(hasText(idle)).assertExists()

        val overviewTab = hasText(overview) and hasClickAction()
        composeRule.onNode(overviewTab).assertIsFocused()
        composeRule.onNode(overviewTab).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onNode(overviewTab).assertIsNotFocused()
    }
}
