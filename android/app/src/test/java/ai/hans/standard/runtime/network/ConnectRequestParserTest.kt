package ai.hans.standard.runtime.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectRequestParserTest {
    private val authorization = ConnectRequestParser.basicAuthorization("hans", "secret")
    private val parser = ConnectRequestParser(authorization)

    @Test
    fun acceptsOnlyAuthenticatedBodylessConnect() {
        val result = parser.parse(request())
        assertEquals(
            ConnectParseResult.Accepted(ConnectTarget("api.openai.com", 443)),
            result,
        )
    }

    @Test
    fun rejectsMissingOrWrongCredentialsWithoutEchoingThem() {
        val missing = parser.parse(request(includeAuthorization = false))
        val wrong = parser.parse(
            request(authorizationValue = "Basic this-is-not-the-secret"),
        )
        assertEquals(407, (missing as ConnectParseResult.Rejected).statusCode)
        assertEquals(407, (wrong as ConnectParseResult.Rejected).statusCode)
        assertTrue(wrong.responseReason.none { it.isDigit() })
    }

    @Test
    fun rejectsParserAbuseAndUnsupportedHttpSurface() {
        val cases = listOf(
            request(method = "GET"),
            request(extraHeaders = "Host: api.openai.com:443\r\n"),
            request(extraHeaders = "Transfer-Encoding: chunked\r\n"),
            request(extraHeaders = "Content-Length: 1\r\n"),
            request(hostHeader = "example.com:443"),
            request().toString(Charsets.US_ASCII).replace("\r\n", "\n").toByteArray(),
            ("CONNECT api.openai.com:443 HTTP/1.1\r\nX: " + "a".repeat(9_000) + "\r\n\r\n")
                .toByteArray(),
        )
        cases.forEach { bytes -> assertTrue(parser.parse(bytes) is ConnectParseResult.Rejected) }
    }

    @Test
    fun normalizesDnsAuthorityAndRequiresMatchingHost() {
        val result = parser.parse(
            request(authority = "API.OPENAI.COM:443", hostHeader = "api.openai.com:443"),
        )
        assertEquals(
            ConnectParseResult.Accepted(ConnectTarget("api.openai.com", 443)),
            result,
        )
    }

    private fun request(
        method: String = "CONNECT",
        authority: String = "api.openai.com:443",
        hostHeader: String = authority,
        includeAuthorization: Boolean = true,
        authorizationValue: String = authorization.toString(Charsets.US_ASCII),
        extraHeaders: String = "",
    ): ByteArray = buildString {
        append("$method $authority HTTP/1.1\r\n")
        append("Host: $hostHeader\r\n")
        if (includeAuthorization) append("Proxy-Authorization: $authorizationValue\r\n")
        append(extraHeaders)
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)
}
