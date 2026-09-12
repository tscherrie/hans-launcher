package ai.hans.standard.phone.keys

import android.view.InputDevice
import android.view.KeyEvent
import java.security.MessageDigest

/**
 * Stable, non-secret identity for one physical input device. Android's numeric
 * device id is deliberately retained only as observation evidence: it may
 * change after a reboot. Persisted mappings match vendor, product and the
 * SHA-256 digest of Android's descriptor instead.
 */
data class PhysicalKeyDeviceObservation(
    val runtimeDeviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val descriptorSha256: String,
) {
    init {
        require(runtimeDeviceId >= 0) { "Physical device id must be non-negative" }
        require(vendorId >= 0) { "Vendor id must be non-negative" }
        require(productId >= 0) { "Product id must be non-negative" }
        require(DESCRIPTOR_DIGEST.matches(descriptorSha256)) {
            "Descriptor digest must be lowercase SHA-256"
        }
    }

    fun selector(): PhysicalKeyDeviceSelector = PhysicalKeyDeviceSelector(
        vendorId = vendorId,
        productId = productId,
        descriptorSha256 = descriptorSha256,
    )

    private companion object {
        val DESCRIPTOR_DIGEST = Regex("[0-9a-f]{64}")
    }
}

/** Stable part of a captured device identity, suitable for persisted mapping. */
data class PhysicalKeyDeviceSelector(
    val vendorId: Int,
    val productId: Int,
    val descriptorSha256: String,
) {
    init {
        require(vendorId >= 0)
        require(productId >= 0)
        require(Regex("[0-9a-f]{64}").matches(descriptorSha256))
    }

    fun matches(observation: PhysicalKeyDeviceObservation?): Boolean =
        observation != null &&
            vendorId == observation.vendorId &&
            productId == observation.productId &&
            descriptorSha256 == observation.descriptorSha256
}

enum class ObservableKeyPhase {
    DOWN,
    UP,
}

/**
 * The complete bounded key evidence Hans is able to observe through public
 * Android APIs. No field implies that Android will deliver the same key while
 * another app is foreground, while the screen is locked, or after an OEM has
 * intercepted it.
 */
data class ObservableAndroidKeyEvent(
    val phase: ObservableKeyPhase,
    val eventTimeMillis: Long,
    val downTimeMillis: Long,
    val repeatCount: Int,
    val isLongPress: Boolean,
    val scanCode: Int,
    val keyCode: Int,
    val metaState: Int,
    val unicodeChar: Int,
    val source: Int,
    val physicalDevice: PhysicalKeyDeviceObservation?,
) {
    init {
        require(eventTimeMillis >= 0)
        require(downTimeMillis >= 0)
        require(eventTimeMillis >= downTimeMillis)
        require(repeatCount >= 0)
        require(scanCode >= 0)
        require(keyCode >= 0)
    }

    val heldMillis: Long
        get() = eventTimeMillis - downTimeMillis

    fun hasMappablePhysicalIdentity(): Boolean =
        physicalDevice != null && (scanCode != 0 || keyCode != KeyEvent.KEYCODE_UNKNOWN)
}

/** Public-API adapter. It performs no interception and requests no privilege. */
object AndroidKeyEventObserver {
    fun observe(event: KeyEvent): ObservableAndroidKeyEvent? {
        val inputDevice = event.device
        return ObservableAndroidKeyEvent(
            phase = when (event.action) {
                KeyEvent.ACTION_DOWN -> ObservableKeyPhase.DOWN
                KeyEvent.ACTION_UP -> ObservableKeyPhase.UP
                else -> return null
            },
            eventTimeMillis = event.eventTime,
            downTimeMillis = event.downTime,
            repeatCount = event.repeatCount,
            isLongPress = event.isLongPress,
            scanCode = event.scanCode,
            keyCode = event.keyCode,
            metaState = event.metaState,
            // Android already applies the current meta state here. Hans must
            // preserve this result rather than inventing an Alt key table.
            unicodeChar = event.unicodeChar,
            source = event.source,
            physicalDevice = inputDevice?.toPhysicalObservation(event.deviceId),
        )
    }

    private fun InputDevice.toPhysicalObservation(
        runtimeDeviceId: Int,
    ): PhysicalKeyDeviceObservation? {
        val stableDescriptor = descriptor?.takeIf(String::isNotBlank) ?: return null
        if (isVirtual || runtimeDeviceId < 0) return null
        return PhysicalKeyDeviceObservation(
            runtimeDeviceId = runtimeDeviceId,
            vendorId = vendorId.coerceAtLeast(0),
            productId = productId.coerceAtLeast(0),
            descriptorSha256 = MessageDigest.getInstance("SHA-256")
                .digest(stableDescriptor.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                },
        )
    }
}
