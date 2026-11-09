package com.nuvio.tv.core.vpn

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WireGuardProfileFileImportTest {

    @Test fun readsSmallConfigExactly() {
        val input = "[Interface]\nPrivateKey = test-placeholder\n".toByteArray()
        assertArrayEquals(input, readLimitedWireGuardProfile(ByteArrayInputStream(input)))
    }

    @Test fun acceptsMaximumBoundedSize() {
        val input = ByteArray(65_536) { 65 }
        assertEquals(input.size, readLimitedWireGuardProfile(ByteArrayInputStream(input)).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedFileBeforeImport() {
        readLimitedWireGuardProfile(ByteArrayInputStream(ByteArray(65_537)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyFile() {
        readLimitedWireGuardProfile(ByteArrayInputStream(byteArrayOf()))
    }

    @Test fun sanitizesProviderControlledFilename() {
        assertEquals("wg-client.conf", safeWireGuardFilename("../../wg-client.conf"))
        assertEquals("WireGuard.conf", safeWireGuardFilename("\n\t"))
        assertEquals("nuvio.conf", safeWireGuardFilename("nuvio\u0000.conf"))
    }
}
