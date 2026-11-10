package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.core.vpn.TorrentVpnSafety
import org.junit.Assert.assertEquals
import org.junit.Test

class WireGuardSettingsActionTest {
    @Test
    fun verifiedStatusRefreshesExistingVpnWithoutConnecting() {
        var refreshes = 0
        var connections = 0

        dispatchVpnSettingsAction(
            status = TorrentVpnSafety.Status.VERIFIED,
            onRefresh = { refreshes++ },
            onConnect = { connections++ }
        )

        assertEquals(1, refreshes)
        assertEquals(0, connections)
    }

    @Test
    fun allOtherStatusesRequestConnectionWithoutRefreshing() {
        TorrentVpnSafety.Status.entries
            .filterNot { it == TorrentVpnSafety.Status.VERIFIED }
            .forEach { status ->
                var refreshes = 0
                var connections = 0

                dispatchVpnSettingsAction(
                    status = status,
                    onRefresh = { refreshes++ },
                    onConnect = { connections++ }
                )

                assertEquals("Unexpected refresh for $status", 0, refreshes)
                assertEquals("Connection not requested for $status", 1, connections)
            }
    }
}
