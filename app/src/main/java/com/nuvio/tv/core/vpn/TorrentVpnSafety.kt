package com.nuvio.tv.core.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Conservative app-side gate, not an operating-system lockdown firewall. */
@Singleton
class TorrentVpnSafety @Inject constructor() {
    enum class Status { DISCONNECTED, CONNECTING, TUNNEL_UP, VERIFIED, ERROR }

    private val mutableStatus = MutableStateFlow(Status.DISCONNECTED)
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    @Synchronized
    fun update(next: Status) {
        mutableStatus.value = next
    }

    fun isReady(): Boolean = isTorrentAllowed(mutableStatus.value)

    fun requireReady() {
        check(isReady()) {
            "Torrent blocked: connect and verify the WireGuard VPN in Settings first."
        }
    }

    companion object {
        /** Never fall back to a direct torrent connection. */
        fun isTorrentAllowed(status: Status): Boolean = status == Status.VERIFIED
    }
}
