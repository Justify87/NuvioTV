package com.nuvio.tv.core.vpn

/** Health is deliberately not a claim of OS-enforced leak prevention. */
enum class VpnHealth { CONNECTED, CHECKING, DISCONNECTED, ERROR }

data class VpnTrafficSample(
    val timestampMs: Long,
    val downloadBytesPerSecond: Long,
    val uploadBytesPerSecond: Long
)

fun WireGuardInfo.health(now: Long = System.currentTimeMillis()): VpnHealth = when (status) {
    TorrentVpnSafety.Status.ERROR -> VpnHealth.ERROR
    TorrentVpnSafety.Status.DISCONNECTED -> VpnHealth.DISCONNECTED
    TorrentVpnSafety.Status.CONNECTING, TorrentVpnSafety.Status.TUNNEL_UP -> VpnHealth.CHECKING
    TorrentVpnSafety.Status.VERIFIED -> if (lastVerifiedAt > 0L && now >= lastVerifiedAt &&
        now - lastVerifiedAt <= 90_000L) VpnHealth.CONNECTED else VpnHealth.CHECKING
}

/** A manual recheck is possible even if the last VERIFIED result is too old to display green. */
internal fun WireGuardInfo.canRefreshVerification(): Boolean =
    status == TorrentVpnSafety.Status.VERIFIED
