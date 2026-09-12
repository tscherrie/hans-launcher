package ai.hans.standard.phone.display

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import ai.hans.standard.phone.keys.AndroidMp01VendorActionRemediation

/** Android implementation of the root-free, fail-closed MP01 display boundary. */
object AndroidMp01DisplayRuntime {
    fun create(context: Context): Mp01DisplayController {
        val appContext = context.applicationContext
        val remediation = AndroidMp01VendorActionRemediation(appContext)
        return Mp01DisplayController(
            evidenceSource = Mp01DisplayTrustEvidenceSource {
                val vendorEvidence = remediation.probe()
                Mp01DisplayTrustEvidence(
                    device = AndroidDeviceIdentity(
                        manufacturer = Build.MANUFACTURER.orEmpty(),
                        brand = Build.BRAND.orEmpty(),
                        model = Build.MODEL.orEmpty(),
                        device = Build.DEVICE.orEmpty(),
                    ),
                    trustedMinimalSystemPackage = vendorEvidence.trustedSystemPackage,
                )
            },
            transport = AndroidAbstractMp01EinkSocketTransport(),
        )
    }
}

internal class AndroidAbstractMp01EinkSocketTransport : Mp01EinkSocketTransport {
    override fun canConnect(): Boolean = runCatching {
        openSocket().use { }
        true
    }.getOrDefault(false)

    override fun send(command: Byte) {
        require(command in ALLOWED_COMMANDS) { "Unsupported MP01 E-Ink command" }
        openSocket().use { socket ->
            socket.outputStream.write(byteArrayOf(command))
            socket.outputStream.flush()
        }
    }

    private fun openSocket(): LocalSocket = LocalSocket().also { socket ->
        try {
            socket.connect(
                LocalSocketAddress(
                    SOCKET_NAME,
                    LocalSocketAddress.Namespace.ABSTRACT,
                ),
            )
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private companion object {
        const val SOCKET_NAME = "MP01_eink_socket"
        val ALLOWED_COMMANDS = setOf(
            'b'.code.toByte(),
            's'.code.toByte(),
            'p'.code.toByte(),
            'r'.code.toByte(),
        )
    }
}
