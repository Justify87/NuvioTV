package com.nuvio.tv.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val info = WireGuardInfo(status = TorrentVpnSafety.Status.VERIFIED,
            lastVerifiedAt = now - 91_000L)
        assertEquals(VpnHealth.CHECKING, info.health(now))
        assertTrue(info.canRefreshVerification())
    }
    @Test fun refreshUnavailableUnlessUnderlyingStatusIsVerified() {
        assertTrue(WireGuardInfo(status = TorrentVpnSafety.Status.VERIFIED).canRefreshVerification())
        listOf(
            TorrentVpnSafety.Status.CONNECTING,
            TorrentVpnSafety.Status.TUNNEL_UP,
            TorrentVpnSafety.Status.DISCONNECTED,
            TorrentVpnSafety.Status.ERROR
        ).forEach { state ->
            assertFalse("Refresh must be disabled in $state",
                WireGuardInfo(status = state, lastVerifiedAt = now).canRefreshVerification())
        }
    }
    @Test fun errorIsRedEvenWithRecentPreviousCheck() {
        assertEquals(VpnHealth.ERROR,
            WireGuardInfo(status = TorrentVpnSafety.Status.ERROR,
                lastVerifiedAt = now - 1_000L).health(now))
    }
}
