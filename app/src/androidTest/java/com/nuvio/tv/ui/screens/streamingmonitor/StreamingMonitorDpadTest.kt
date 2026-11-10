package com.nuvio.tv.ui.screens.streamingmonitor

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
            NuvioTheme { StreamingMonitorScreen() }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(overview).assertIsSelected().assertIsFocused()

        composeRule.onNodeWithText(overview).performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithText(history).assertIsFocused()
        composeRule.onNodeWithText(overview).assertIsSelected()

        composeRule.onNodeWithText(history).performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithText(history).assertIsSelected()
        composeRule.onNodeWithText(history).performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithText(connection).assertIsFocused()
    }
}
