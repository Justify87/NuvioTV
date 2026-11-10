package com.nuvio.tv.ui.screens.streamingmonitor

import com.nuvio.tv.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamingMonitorTabTest {
    @Test
    fun `streaming monitor has its own root route`() {
        assertEquals("streaming_monitor", Screen.StreamingMonitor.route)
        assertFalse(
            listOf(Screen.Home, Screen.Search, Screen.Library, Screen.Settings)
                .any { it.route == Screen.StreamingMonitor.route }
        )
    }

    @Test
    fun `tab order stays stable for saved selected index`() {
        assertEquals(
            listOf(
                StreamingMonitorTab.Overview,
                StreamingMonitorTab.History,
                StreamingMonitorTab.Connection
            ),
            StreamingMonitorTab.entries
        )
        StreamingMonitorTab.entries.forEach { tab ->
            assertEquals(tab, streamingMonitorTabOrOverview(tab.ordinal))
        }
    }

    @Test
    fun `invalid saved tab index falls back to overview`() {
        assertEquals(StreamingMonitorTab.Overview, streamingMonitorTabOrOverview(-1))
        assertEquals(StreamingMonitorTab.Overview, streamingMonitorTabOrOverview(Int.MAX_VALUE))
    }
}
