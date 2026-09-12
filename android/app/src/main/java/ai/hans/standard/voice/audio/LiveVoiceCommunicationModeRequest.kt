package ai.hans.standard.voice.audio

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns only Hans' API-31+ communication-mode request. Releasing it is unconditional: another
 * higher-priority owner such as Telephony may currently determine the effective global mode, but
 * that must not leave Hans' lower-priority request latent after the call ends.
 */
internal class LiveVoiceCommunicationModeRequest(
    private val previousMode: Int,
    private val releaseRequest: (Int) -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) releaseRequest(previousMode)
    }
}
