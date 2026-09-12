package ai.hans.standard.runtime.network

import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

data class ConnectTarget(
    val host: String,
    val port: Int,
)

sealed interface ConnectParseResult {
    data class Accepted(val target: ConnectTarget) : ConnectParseResult

    data class Rejected(
        val statusCode: Int,
        val responseReason: String,
    ) : ConnectParseResult
}

/**
 * Parser for the deliberately tiny HTTP surface exposed by the runtime proxy.
 * It accepts one authenticated HTTP/1.1 CONNECT request and no request body.
 */
class ConnectRequestParser(
    private val expectedAuthorization: ByteArray,
) {
    init {
        require(expectedAuthorization.isNotEmpty())
        require(expectedAuthorization.size <= MAX_HEADER_VALUE_BYTES)
    }

    fun parse(headerBlock: ByteArray): ConnectParseResult {
        if (headerBlock.isEmpty() || headerBlock.size > MAX_HEADER_BYTES) return badRequest()
        if (!headerBlock.endsWith(HEADER_TERMINATOR)) return badRequest()
        if (headerBlock.any { byte -> byte == 0.toByte() || byte == '\u007f'.code.toByte() }) {
            return badRequest()
        }
        val text = runCatching {
            StandardCharsets.US_ASCII.newDecoder().decode(java.nio.ByteBuffer.wrap(headerBlock)).toString()
        }.getOrElse { return badRequest() }
        if (text.any { character -> character.code > 0x7f }) return badRequest()
        val lines = text.removeSuffix("\r\n\r\n").split("\r\n")
        if (lines.isEmpty() || lines.size > MAX_HEADER_LINES) return badRequest()
        if (lines.any { it.isEmpty() || it.length > MAX_LINE_LENGTH }) return badRequest()

        val requestParts = lines.first().split(' ')
        if (requestParts.size != 3 || requestParts[0] != "CONNECT" || requestParts[2] != "HTTP/1.1") {
            return ConnectParseResult.Rejected(405, "Method Not Allowed")
        }
        val target = parseAuthority(requestParts[1]) ?: return badRequest()

        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf(':')
            if (separator <= 0) return badRequest()
            val name = line.substring(0, separator)
            if (!HEADER_NAME.matches(name)) return badRequest()
            val normalizedName = name.lowercase()
            if (headers.containsKey(normalizedName)) return badRequest()
            val value = line.substring(separator + 1).trimAsciiWhitespace()
            if (value.length > MAX_HEADER_VALUE_BYTES || value.any(::isInvalidHeaderCharacter)) {
                return badRequest()
            }
            headers[normalizedName] = value
        }

        val authorization = headers["proxy-authorization"]
            ?: return ConnectParseResult.Rejected(407, "Proxy Authentication Required")
        if (!MessageDigest.isEqual(authorization.toByteArray(StandardCharsets.US_ASCII), expectedAuthorization)) {
            return ConnectParseResult.Rejected(407, "Proxy Authentication Required")
        }
        val hostHeader = headers["host"] ?: return badRequest()
        if (parseAuthority(hostHeader) != target) return badRequest()
        if (headers.containsKey("transfer-encoding")) return badRequest()
        headers["content-length"]?.let { value ->
            if (value != "0") return badRequest()
        }
        return ConnectParseResult.Accepted(target)
    }

    private fun parseAuthority(value: String): ConnectTarget? {
        if (value.isBlank() || value.length > MAX_AUTHORITY_LENGTH) return null
        val host: String
        val portText: String
        if (value.startsWith('[')) {
            val closing = value.indexOf(']')
            if (closing <= 1 || closing + 2 > value.length || value.getOrNull(closing + 1) != ':') return null
            val literal = value.substring(1, closing)
            if (literal.contains('%')) return null
            val parsed = runCatching { InetAddress.getByName(literal) }.getOrNull()
            if (parsed !is Inet6Address) return null
            host = (parsed.hostAddress ?: return null).substringBefore('%').lowercase()
            portText = value.substring(closing + 2)
        } else {
            val separator = value.lastIndexOf(':')
            if (separator <= 0 || value.indexOf(':') != separator) return null
            val rawHost = value.substring(0, separator)
            if (rawHost.endsWith('.') || rawHost.any { it == '/' || it == '\\' || it.isWhitespace() }) return null
            host = runCatching { IDN.toASCII(rawHost, IDN.USE_STD3_ASCII_RULES).lowercase() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_HOST_LENGTH }
                ?: return null
            portText = value.substring(separator + 1)
        }
        if (portText.isEmpty() || portText.length > 5 || portText.any { !it.isDigit() }) return null
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return ConnectTarget(host, port)
    }

    private fun String.trimAsciiWhitespace(): String = trim { it == ' ' || it == '\t' }

    private fun isInvalidHeaderCharacter(character: Char): Boolean =
        character == '\r' || character == '\n' || character.code < 0x20 && character != '\t'

    private fun badRequest() = ConnectParseResult.Rejected(400, "Bad Request")

    companion object {
        const val MAX_HEADER_BYTES = 8 * 1024
        const val MAX_HEADER_LINES = 32
        const val MAX_LINE_LENGTH = 1_024
        private const val MAX_HEADER_VALUE_BYTES = 1_024
        private const val MAX_AUTHORITY_LENGTH = 320
        private const val MAX_HOST_LENGTH = 253
        private val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
        private val HEADER_TERMINATOR = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

        fun basicAuthorization(username: String, password: String): ByteArray {
            require(username.isNotEmpty() && !username.contains(':'))
            require(password.isNotEmpty())
            val token = Base64.getEncoder().encodeToString(
                "$username:$password".toByteArray(StandardCharsets.UTF_8),
            )
            return "Basic $token".toByteArray(StandardCharsets.US_ASCII)
        }
    }
}

private fun ByteArray.endsWith(suffix: ByteArray): Boolean {
    if (size < suffix.size) return false
    return suffix.indices.all { index -> this[size - suffix.size + index] == suffix[index] }
}
