package com.nuvio.tv.ui.screens.streamingmonitor

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.key.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
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
}
