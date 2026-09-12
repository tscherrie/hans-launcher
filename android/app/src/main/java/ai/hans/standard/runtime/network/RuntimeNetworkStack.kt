package ai.hans.standard.runtime.network

import ai.hans.standard.runtime.CodexRuntimeDirectories
import java.io.Closeable
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

class RuntimeNetworkEnvironment(
    val caBundleFile: File,
    val proxyUrl: String,
    val noProxy: String = "localhost,127.0.0.1,::1",
) {
    init {
        require(caBundleFile.isAbsolute)
        require(proxyUrl.startsWith("http://") && proxyUrl.contains("@127.0.0.1:"))
        require(noProxy.isNotBlank())
    }

    override fun toString(): String =
        "RuntimeNetworkEnvironment(caBundleFile=${caBundleFile.absolutePath}, proxyUrl=<redacted>)"
}

class RuntimeNetworkStack(
    private val caMaterializer: AndroidCaBundleMaterializer = AndroidCaBundleMaterializer(),
    private val targetPolicyFactory: () -> ConnectTargetPolicy = { ConnectTargetPolicy() },
    private val secureRandom: SecureRandom = SecureRandom(),
) : Closeable {
    private var activeProxy: LoopbackConnectProxy? = null

    /** Starts a fresh proxy before retiring the preceding generation. */
    @Synchronized
    fun restart(directories: CodexRuntimeDirectories): RuntimeNetworkEnvironment {
        val caBundle = caMaterializer.materialize(
            File(directories.workingDirectory, "network/android-system-ca-bundle.pem"),
        )
        val credential = newCredential()
        val replacement = LoopbackConnectProxy(
            expectedAuthorization = ConnectRequestParser.basicAuthorization(USERNAME, credential),
            targetPolicy = targetPolicyFactory(),
        ).start()
        val proxyUrl = proxyUrl(credential, replacement.port)
        val previous = activeProxy
        activeProxy = replacement
        previous?.close()
        return RuntimeNetworkEnvironment(caBundle.file, proxyUrl)
    }

    @Synchronized
    override fun close() {
        activeProxy?.close()
        activeProxy = null
    }

    private fun newCredential(): String {
        val bytes = ByteArray(CREDENTIAL_BYTES)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun proxyUrl(credential: String, port: Int): String {
        val user = URLEncoder.encode(USERNAME, StandardCharsets.UTF_8.name())
        val password = URLEncoder.encode(credential, StandardCharsets.UTF_8.name())
        return "http://$user:$password@127.0.0.1:$port"
    }

    private companion object {
        const val USERNAME = "hans"
        const val CREDENTIAL_BYTES = 32
    }
}
