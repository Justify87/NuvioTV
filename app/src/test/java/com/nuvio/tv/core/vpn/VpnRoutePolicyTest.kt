package com.nuvio.tv.core.vpn

import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class VpnRoutePolicyTest {
    private val syntheticPrivate = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val syntheticPublic = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })

    private fun config(allowedIps: String, extra: String = ""): Config {
        val raw = """
            [Interface]
            PrivateKey = $syntheticPrivate
            Address = 10.0.0.2/32
            DNS = 10.0.0.1
            $extra

            [Peer]
            PublicKey = $syntheticPublic
            AllowedIPs = $allowedIps
            Endpoint = 192.0.2.1:51820
        """.trimIndent()
        return Config.parse(ByteArrayInputStream(raw.toByteArray()))
    }

    @Test fun appRestrictionOverridesImportedAllowlist() {
        val input = config("0.0.0.0/0, ::/0", "IncludedApplications = com.someone.else")
        val result = VpnRoutePolicy.restrictToApplication(input, "com.justify87.nuviotv.wireguard.debug")
        assertEquals(setOf("com.justify87.nuviotv.wireguard.debug"), result.getInterface().includedApplications)
        VpnRoutePolicy.requireDualStackFullTunnel(result)
    }

    @Test fun appRestrictionRemovesImportedExclusions() {
        val input = config("0.0.0.0/0, ::/0", "ExcludedApplications = com.other")
        val result = VpnRoutePolicy.restrictToApplication(input, "com.example.nuvio")
        assertEquals(emptySet<String>(), result.getInterface().excludedApplications)
        assertEquals(setOf("com.example.nuvio"), result.getInterface().includedApplications)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ipv4OnlyIsRejected() {
        VpnRoutePolicy.requireDualStackFullTunnel(config("0.0.0.0/0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun ipv6OnlyIsRejected() {
        VpnRoutePolicy.requireDualStackFullTunnel(config("::/0"))
    }
}
