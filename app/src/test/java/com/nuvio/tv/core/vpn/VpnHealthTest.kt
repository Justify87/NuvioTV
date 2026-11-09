package com.nuvio.tv.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnHealthTest {
    private val now = 2_000_000L
    @Test fun disconnectedNeverDisplaysGreen() {
        assertEquals(VpnHealth.DISCONNECTED,
            WireGuardInfo(status = TorrentVpnSafety.Status.DISCONNECTED,
                lastVerifiedAt = now).health(now))
    }
    @Test fun backendUpWithoutEgressIsOnlyChecking() {
        assertEquals(VpnHealth.CHECKING,
            WireGuardInfo(status = TorrentVpnSafety.Status.TUNNEL_UP).health(now))
    }
    @Test fun freshExitVerificationIsGreen() {
        assertEquals(VpnHealth.CONNECTED,
            WireGuardInfo(status = TorrentVpnSafety.Status.VERIFIED,
                lastVerifiedAt = now - 30_000L).health(now))
    }
    @Test fun expiredVerificationDoesNotDisplayGreen() {
        assertEquals(VpnHealth.CHECKING,
            WireGuardInfo(status = TorrentVpnSafety.Status.VERIFIED,
                lastVerifiedAt = now - 91_000L).health(now))
    }
    @Test fun errorIsRedEvenWithRecentPreviousCheck() {
        assertEquals(VpnHealth.ERROR,
            WireGuardInfo(status = TorrentVpnSafety.Status.ERROR,
                lastVerifiedAt = now - 1_000L).health(now))
    }
}
