package ai.hans.standard.voice.realtime

import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Privacy-safe state receipts for real-device Live Voice acceptance traces. */
internal object LiveVoiceDiagnostics {
    private const val TAG = "HansLiveVoice"

    fun event(name: String, details: String = "") {
        val message = if (details.isBlank()) name else "$name $details"
        try {
            Log.i(TAG, message.take(320))
        } catch (_: RuntimeException) {
            // Local JVM tests use an Android stub. Diagnostics must never affect behavior.
        }
    }

    /** Stable receipt that cannot reveal the opaque server id itself. */
    fun safeId(value: String?): String = value
        ?.takeIf(String::isNotBlank)
        ?.let {
            MessageDigest.getInstance("SHA-256")
                .digest(it.toByteArray(StandardCharsets.UTF_8))
                .take(5)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
        ?: "none"
}
