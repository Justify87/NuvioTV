package com.nuvio.tv.ui.screens.streamingmonitor

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.key.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import com.nuvio.tv.R
import com.nuvio.tv.core.vpn.VpnHealth
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StreamingMonitorConnectionDpadTest {
    @get:Rule val rule = createComposeRule()

    @Test fun connectionTabCanBeEnteredAndRefreshIsExplicit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val calls = AtomicInteger(0)
        rule.setContent {
            NuvioTheme {
                StreamingMonitorScreenContent(
                    connectionState = StreamingMonitorConnectionState(
                        health = VpnHealth.CONNECTED, canRefresh = true, p2pEnabled = false
                    ),
                    onRefreshVpn = { calls.incrementAndGet() }
                )
            }
        }
        val tab = hasText(context.getString(R.string.streaming_monitor_tab_connection)) and hasClickAction()
        rule.onNode(tab).performClick()
        rule.onNode(hasText(context.getString(R.string.sm_connection_verified))).assertExists()
        rule.onNode(hasText(context.getString(R.string.sm_connection_p2p_disabled))).assertExists()
        val refresh = hasText(context.getString(R.string.sm_connection_recheck)) and hasClickAction()
        rule.onNode(refresh).assertIsEnabled().performClick()
        assertEquals(1, calls.get())
        // Remote can enter content from the selected tab without a new route/dialog.
        rule.onNode(tab).performKeyInput { pressKey(Key.DirectionDown) }
    }

    @Test fun disconnectedRecheckIsDisabledWithoutCallingManager() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val calls = AtomicInteger()
        rule.setContent {
            NuvioTheme {
                StreamingMonitorScreenContent(
                    connectionState = StreamingMonitorConnectionState(
                        health = VpnHealth.DISCONNECTED, canRefresh = false
                    ),
                    onRefreshVpn = { calls.incrementAndGet() }
                )
            }
        }
        rule.onNode(hasText(context.getString(R.string.streaming_monitor_tab_connection)) and hasClickAction())
            .performClick()
        rule.onNode(hasText(context.getString(R.string.sm_connection_recheck)) and hasClickAction())
            .assertIsNotEnabled()
        rule.onNode(hasText(context.getString(R.string.sm_connection_recheck_unavailable))).assertExists()
        assertEquals(0, calls.get())
    }

    @Test fun refreshFailureDisplaysGenericFeedback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent {
            NuvioTheme {
                StreamingMonitorScreenContent(
                    connectionState = StreamingMonitorConnectionState(
                        health = VpnHealth.ERROR, refreshFeedback = VerificationFeedback.FAILED
                    )
                )
            }
        }
        rule.onNode(hasText(context.getString(R.string.streaming_monitor_tab_connection)) and hasClickAction())
            .performClick()
        rule.onNode(hasText(context.getString(R.string.sm_connection_recheck_failed))).assertExists()
    }
}
