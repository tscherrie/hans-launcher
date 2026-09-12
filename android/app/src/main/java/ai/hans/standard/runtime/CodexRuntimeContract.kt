package ai.hans.standard.runtime

import ai.hans.standard.codex.CodexProtocolContract
import ai.hans.standard.runtime.network.RuntimeNetworkEnvironment
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class CodexRuntimeDirectories(
    val workingDirectory: File,
    val homeDirectory: File,
    val codexHomeDirectory: File,
    val temporaryDirectory: File,
)

data class CodexInitializeResult(
    val responseJson: String,
    val userAgent: String,
    val codexHome: String,
    val platformFamily: String,
    val platformOs: String,
)

object CodexRuntimeContract {
    const val EXECUTABLE_NAME = "libcodex_app_server.so"
    const val INITIALIZE_REQUEST_ID = 1L
    // Results cross Binder, whose transaction budget is shared by all fields.
    // Keep each stream far below that limit instead of merely limiting RAM.
    const val MAX_STDOUT_BYTES = 131_072
    const val MAX_STDERR_BYTES = 131_072
    const val MAX_JSON_LINE_BYTES = 65_536
    const val VERSION_TIMEOUT_SECONDS = 10L
    const val INITIALIZE_TIMEOUT_SECONDS = 20L
    const val SHUTDOWN_TIMEOUT_SECONDS = 5L
    const val READINESS_GATE_GRACEFUL_EXIT_TIMEOUT_SECONDS = 10L

    /**
     * The readiness probe deliberately stops the long-lived App Server after
     * a valid initialize response. This sentinel is not a child exit status;
     * `cleanupSucceeded` remains the authoritative cleanup evidence.
     */
    const val READINESS_GATE_CONTROLLED_STOP = -2

    private val versionPattern = Regex("^codex-app-server ([0-9]+(?:\\.[0-9]+){2}(?:[-+][A-Za-z0-9.-]+)?)$")

    fun executableFile(nativeLibraryDir: String): File {
        val directory = File(nativeLibraryDir)
        require(directory.isAbsolute) { "nativeLibraryDir must be absolute" }
        return File(directory, EXECUTABLE_NAME)
    }

    fun directories(
        noBackupFilesDirectory: File,
        cacheDirectory: File,
    ): CodexRuntimeDirectories {
        require(noBackupFilesDirectory.isAbsolute) { "noBackupFilesDir must be absolute" }
        require(cacheDirectory.isAbsolute) { "cacheDir must be absolute" }
        val runtimeRoot = File(noBackupFilesDirectory, "codex")
        return CodexRuntimeDirectories(
            workingDirectory = File(runtimeRoot, "runtime"),
            homeDirectory = File(runtimeRoot, "home"),
            codexHomeDirectory = File(runtimeRoot, "home"),
            temporaryDirectory = File(cacheDirectory, "codex-tmp"),
        )
    }

    fun controlledEnvironment(
        directories: CodexRuntimeDirectories,
        androidRoot: String,
        androidData: String,
        network: RuntimeNetworkEnvironment,
    ): Map<String, String> = mapOf(
        "ALL_PROXY" to network.proxyUrl,
        "ANDROID_DATA" to androidData,
        "ANDROID_ROOT" to androidRoot,
        "CODEX_HOME" to directories.codexHomeDirectory.absolutePath,
        "HOME" to directories.homeDirectory.absolutePath,
        "HTTPS_PROXY" to network.proxyUrl,
        "HTTP_PROXY" to network.proxyUrl,
        "LANG" to "C.UTF-8",
        "NO_PROXY" to network.noProxy,
        "PATH" to "/system/bin",
        "SHELL" to "/system/bin/sh",
        "SSL_CERT_FILE" to network.caBundleFile.absolutePath,
        "TMPDIR" to directories.temporaryDirectory.absolutePath,
    )

    fun initializeRequest(clientVersion: String): String {
        return initializeRequest(clientVersion, INITIALIZE_REQUEST_ID)
    }

    fun initializeRequest(clientVersion: String, requestId: Any): String {
        require(clientVersion.isNotBlank()) { "clientVersion must not be blank" }
        require(requestId is String || requestId is Long || requestId is Int) {
            "initialize request id must be a string or integer"
        }
        return JSONObject()
            .put("id", requestId)
            .put("method", "initialize")
            .put(
                "params",
                JSONObject()
                    .put(
                        "clientInfo",
                        JSONObject()
                            .put("name", "hans_android_runtime")
                            .put("title", "Hans Android Runtime")
                            .put("version", clientVersion),
                    )
                    // Hans registers Android dynamic tools and consumes the
                    // App Server plugin APIs. Codex 0.149 rejects those
                    // requests unless the connection explicitly negotiates
                    // the experimental API during initialize.
                    .put(
                        "capabilities",
                        JSONObject()
                            .put("experimentalApi", true)
                            // app/list already returns the authoritative,
                            // paginated result. Suppress Codex's redundant
                            // full-directory notification on this connection.
                            .put(
                                "optOutNotificationMethods",
                                JSONArray().put(
                                    CodexProtocolContract.APP_LIST_UPDATED_NOTIFICATION,
                                ),
                            ),
                    ),
            )
            .toString()
    }

    fun parseVersion(stdout: String): String? {
        val match = versionPattern.matchEntire(stdout.trim()) ?: return null
        return match.groupValues[1]
    }

    fun parseInitializeResponse(
        responseJson: String,
        expectedCodexHome: String? = null,
    ): CodexInitializeResult {
        return parseInitializeResponse(
            responseJson = responseJson,
            expectedRequestId = INITIALIZE_REQUEST_ID,
            expectedCodexHome = expectedCodexHome,
        )
    }

    fun parseInitializeResponse(
        responseJson: String,
        expectedRequestId: Any,
        expectedCodexHome: String? = null,
    ): CodexInitializeResult {
        val response = JSONObject(responseJson)
        require(requestIdsMatch(response.opt("id"), expectedRequestId)) {
            "initialize response id does not match"
        }
        require(!response.has("error")) {
            "initialize failed: ${response.get("error")}"
        }
        val result = response.getJSONObject("result")
        val parsed = CodexInitializeResult(
            responseJson = responseJson,
            userAgent = result.getString("userAgent"),
            codexHome = result.getString("codexHome"),
            platformFamily = result.getString("platformFamily"),
            platformOs = result.getString("platformOs"),
        )
        require(parsed.userAgent.isNotBlank()) { "initialize userAgent is blank" }
        require(parsed.platformFamily == "unix") {
            "unexpected platform family: ${parsed.platformFamily}"
        }
        require(parsed.platformOs == "linux") {
            "unexpected platform OS: ${parsed.platformOs}"
        }
        if (expectedCodexHome != null) {
            require(File(parsed.codexHome).canonicalPath == File(expectedCodexHome).canonicalPath) {
                "initialize returned an unexpected CODEX_HOME"
            }
        }
        return parsed
    }

    private fun requestIdsMatch(actual: Any?, expected: Any): Boolean {
        return when {
            actual is Number && expected is Number -> actual.toLong() == expected.toLong()
            actual is String && expected is String -> actual == expected
            else -> false
        }
    }
}

internal object BoundedStreams {
    fun readToEnd(input: InputStream, maximumBytes: Int): String {
        require(maximumBytes > 0)
        val output = ByteArrayOutputStream(minOf(maximumBytes, 16_384))
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= maximumBytes) { "process output exceeds $maximumBytes bytes" }
            output.write(buffer, 0, count)
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    fun readJsonRpcResponse(
        input: InputStream,
        requestId: Long,
        maximumTotalBytes: Int,
        maximumLineBytes: Int,
    ): String {
        require(maximumTotalBytes > 0)
        require(maximumLineBytes > 0)
        val line = ByteArrayOutputStream(minOf(maximumLineBytes, 8_192))
        var total = 0
        while (true) {
            val next = input.read()
            if (next < 0) {
                if (line.size() > 0) {
                    matchingResponse(line.toByteArray(), requestId)?.let { return it }
                }
                throw EOFException("App Server closed stdout before initialize response")
            }
            total += 1
            check(total <= maximumTotalBytes) {
                "App Server stdout exceeds $maximumTotalBytes bytes before initialize"
            }
            if (next == '\n'.code) {
                matchingResponse(line.toByteArray(), requestId)?.let { return it }
                line.reset()
            } else {
                check(line.size() < maximumLineBytes) {
                    "App Server JSON line exceeds $maximumLineBytes bytes"
                }
                line.write(next)
            }
        }
    }

    private fun matchingResponse(bytes: ByteArray, requestId: Long): String? {
        if (bytes.isEmpty()) return null
        val value = bytes.toString(StandardCharsets.UTF_8).trimEnd('\r')
        val json = try {
            JSONObject(value)
        } catch (_: Exception) {
            return null
        }
        return if (json.optLong("id", Long.MIN_VALUE) == requestId) value else null
    }
}
