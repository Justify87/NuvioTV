package com.nuvio.tv.core.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentVpnSafetyTest {
    @Test fun onlyVerifiedStatusAllowsTorrents() {
        TorrentVpnSafety.Status.entries.forEach { state ->
            val expected = state == TorrentVpnSafety.Status.VERIFIED
            if (expected) assertTrue(TorrentVpnSafety.isTorrentAllowed(state))
            else assertFalse(TorrentVpnSafety.isTorrentAllowed(state))
        }
    }

    @Test(expected = IllegalStateException::class)
    fun disconnectedVpnFailsClosed() {
        TorrentVpnSafety().requireReady()
    }

    @Test fun verifiedVpnAllowsEngineCheck() {
        val safety = TorrentVpnSafety()
        safety.update(TorrentVpnSafety.Status.VERIFIED)
        safety.requireReady()
        assertTrue(safety.isReady())
        safety.update(TorrentVpnSafety.Status.DISCONNECTED)
        assertFalse(safety.isReady())
    }
}
