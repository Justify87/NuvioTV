package com.nuvio.tv.core.vpn

import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.Inet6Address

/** Pure, testable policy for full-tunnel VPN and app-only split routing. */
internal object VpnRoutePolicy {
    fun requireDualStackFullTunnel(config: Config) {
        require(config.peers.isNotEmpty()) { "WireGuard profile contains no peer" }
        val routes = config.peers.flatMap { it.allowedIps }
        val v4 = routes.any { it.mask == 0 && it.address is Inet4Address }
        val v6 = routes.any { it.mask == 0 && it.address is Inet6Address }
        require(v4 && v6) {
            "Full tunnel requires IPv4 and IPv6 default routes (0.0.0.0/0 and ::/0)"
        }
    }

    fun restrictToApplication(config: Config, applicationId: String): Config {
        require(applicationId.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+"))) {
            "Invalid Android package name"
        }
        val normalized = config.toWgQuickString().lineSequence()
            .filterNot { it.trimStart().startsWith("IncludedApplications", ignoreCase = true) ||
                it.trimStart().startsWith("ExcludedApplications", ignoreCase = true) }
            .joinToString("\n")
        val canonical = normalized.replaceFirst(
            "[Interface]",
            "[Interface]\nIncludedApplications = $applicationId"
        )
        val scoped = Config.parse(ByteArrayInputStream(canonical.toByteArray(Charsets.UTF_8)))
        check(scoped.getInterface().includedApplications == setOf(applicationId)) {
            "Unexpected application allowlist"
        }
        return scoped
    }
}
