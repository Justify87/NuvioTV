package com.nuvio.tv.core.vpn

import android.net.Network
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireGuardNetworkLossTest {
    private val networkA = mockk<Network>()
    private val networkB = mockk<Network>()

    @Test fun currentNetworkLossIsHandled() {
        assertTrue(shouldHandleNetworkLoss(networkA, networkA))
    }

    @Test fun delayedLossFromPreviousNetworkIsIgnored() {
        assertFalse(shouldHandleNetworkLoss(networkB, networkA))
    }

    @Test fun firstAvailableNetworkHasNoPreviousLoss() {
        assertFalse(shouldHandleNetworkLoss(null, networkA))
        assertTrue(shouldHandleNetworkLoss(networkA, networkA))
    }

    @Test fun rapidAtoBtoASwitchIgnoresObsoleteLosses() {
        var observed: Network? = networkA
        observed = networkB
        assertFalse(shouldHandleNetworkLoss(observed, networkA))

        observed = networkA
        assertFalse(shouldHandleNetworkLoss(observed, networkB))
        assertTrue(shouldHandleNetworkLoss(observed, networkA))
    }
}
