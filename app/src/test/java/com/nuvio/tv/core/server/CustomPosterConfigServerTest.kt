package com.nuvio.tv.core.server

import com.google.gson.JsonParser
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

class CustomPosterConfigServerTest {

    @Test
    fun savesUnicodeEscapedQuotesBackslashesAndNewlines() {
        var saved: String? = null
        val server = CustomPosterConfigServer(
            currentPatternProvider = { "" },
            onPatternChanged = { saved = it }
        )
        val body = """{"pattern":"  Grüße \"aus\" C:\\Bilder\nZeile zwei  "}"""

        val response = server.serve(FakeSession(NanoHTTPD.Method.POST, "/api/pattern", body))

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("Grüße \"aus\" C:\\Bilder\nZeile zwei", saved)
        assertEquals("""{"status":"saved"}""", response.bodyText())
    }

    @Test
    fun acceptsJsonWhitespaceAndTrimsSavedPattern() {
        var saved: String? = null
        val server = CustomPosterConfigServer({ "" }, { saved = it })

        val response = server.serve(
            FakeSession(NanoHTTPD.Method.POST, "/api/pattern", " \n { \n \"pattern\" : \"  hello world  \" \n } \t")
        )

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("hello world", saved)
    }

    @Test
    fun emptyAndWhitespaceOnlyPatternStringsAreStillAccepted() {
        var saved: String? = null
        val server = CustomPosterConfigServer({ "" }, { saved = it })

        for (body in listOf("""{"pattern":""}""", """{"pattern":"   "}""")) {
            saved = null
            val response = server.serve(FakeSession(NanoHTTPD.Method.POST, "/api/pattern", body))
            assertEquals(NanoHTTPD.Response.Status.OK, response.status)
            assertEquals("", saved)
        }
    }

    @Test
    fun missingNonStringAndMalformedJsonIsRejectedWithoutSaving() {
        val invalidBodies = listOf(
            "",
            "{}",
            """{"other":"value"}""",
            """{"pattern":null}""",
            """{"pattern":123}""",
            """{"pattern":true}""",
            """{"pattern":"unterminated}""",
            """{"pattern":"x",}""",
            """{"pattern":"x" broken}""",
            """["not an object"]""",
            """{"pattern":"x"} trailing"""
        )

        for (body in invalidBodies) {
            var called = false
            val server = CustomPosterConfigServer({ "" }, { called = true })

            val response = server.serve(FakeSession(NanoHTTPD.Method.POST, "/api/pattern", body))

            assertEquals("Body: $body", NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
            assertEquals("""{"error":"Missing pattern field"}""", response.bodyText())
            assertFalse("Invalid body must not be saved: $body", called)
        }
    }

    @Test
    fun currentPatternIsCorrectlySerializedAndRoundTrips() {
        val original = "Grüße \"aus\" C:\\Bilder\nneue Zeile\u0009"
        val server = CustomPosterConfigServer({ original }, {})

        val response = server.serve(FakeSession(NanoHTTPD.Method.GET, "/api/pattern"))
        val json = JsonParser.parseString(response.bodyText()).asJsonObject

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(original, json.get("pattern").asString)
    }

    @Test
    fun clearEndpointStillSavesAnEmptyPattern() {
        var saved: String? = null
        val server = CustomPosterConfigServer({ "previous" }, { saved = it })

        val response = server.serve(FakeSession(NanoHTTPD.Method.POST, "/api/clear"))

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("", saved)
        assertEquals("""{"status":"cleared"}""", response.bodyText())
    }

    private fun NanoHTTPD.Response.bodyText(): String =
        data.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    private class FakeSession(
        private val method: NanoHTTPD.Method,
        private val uri: String,
        body: String = ""
    ) : NanoHTTPD.IHTTPSession {
        private val bytes = body.toByteArray(StandardCharsets.UTF_8)

        override fun execute() = Unit
        override fun getCookies(): NanoHTTPD.CookieHandler? = null
        override fun getHeaders(): Map<String, String> = mapOf(
            "content-length" to bytes.size.toString(),
            "content-type" to "application/json; charset=utf-8"
        )
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getMethod(): NanoHTTPD.Method = method
        @Deprecated("Deprecated in NanoHTTPD")
        override fun getParms(): Map<String, String> = emptyMap()
        override fun getParameters(): Map<String, List<String>> = emptyMap()
        override fun getQueryParameterString(): String? = null
        override fun getUri(): String = uri
        @Deprecated("Deprecated in NanoHTTPD")
        override fun parseBody(files: MutableMap<String, String>) {
            error("parseBody should not be used")
        }
        override fun getRemoteIpAddress(): String = "127.0.0.1"
        override fun getRemoteHostName(): String = "localhost"
    }
}
