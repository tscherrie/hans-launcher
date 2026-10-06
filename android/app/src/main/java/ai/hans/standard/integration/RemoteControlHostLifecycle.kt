package ai.hans.standard.integration

import ai.hans.standard.phone.lifecycle.HansActiveWorkForegroundEvent
import ai.hans.standard.phone.lifecycle.HansActiveWorkForegroundSnapshot
import ai.hans.standard.phone.lifecycle.HansActiveWorkReason
import ai.hans.standard.phone.lifecycle.HansActiveWorkUpdate
import ai.hans.standard.phone.lifecycle.HansActiveWorkUpdateStatus
import ai.hans.standard.remotecontrol.RemoteControlCapability
import ai.hans.standard.remotecontrol.RemoteControlSnapshot
import ai.hans.standard.remotecontrol.RemoteControlStatus

internal data class RemoteControlHostRuntime<T : Any>(val identity: T, val remote: RemoteControlSnapshot)

/**
 * Main-owner lifecycle gate shared by the Android adapter and deterministic tests. Dispatching an
 * FGS command is not promotion proof, and releasing foreground work is not native relay revocation.
 * No Activity, clock, timer thread, remote identity or pairing secret is retained here.
 */
internal class RemoteControlHostLifecycle<T : Any>(
    private val runtime: () -> RemoteControlHostRuntime<T>?,
    private val enable: (T) -> Boolean,
    private val disable: (T) -> Boolean,
    private val restart: (T) -> Unit,
    private val releaseForeground: () -> Unit,
    private val schedulePromotionDeadline: (Long, () -> Unit) -> (() -> Unit),
    private val desktopRemoteAccessEnabled: Boolean = ai.hans.standard.remotecontrol.DesktopRemoteAccessPolicy.enabled,
) {
    private var owner: T? = null
    private var generation: Long? = null
    private var promotionRevision: Long? = null
    private var enableIssued = false
    private var disableIssued = false
    private var stopSequence = 0L
    private var foregroundSequence = Long.MIN_VALUE
    private var cancelPromotionDeadline: (() -> Unit)? = null
    private var acquisitionSequence = 0L

    val ownsProtection: Boolean get() = owner != null

    fun seedStopSequence(sequence: Long) { stopSequence = sequence }

    /** [acquire] runs synchronously on the local visible Activity's consent stack. */
    fun requestEnable(acquire: () -> HansActiveWorkUpdate): Boolean {
        if (!desktopRemoteAccessEnabled) return false
        val current = runtime() ?: return false
        if (!current.remote.runtimeReady || current.remote.capability != RemoteControlCapability.SUPPORTED ||
            current.remote.generation == null) return false
        if (matches(current) && (promotionRevision != null || enableIssued)) return !disableIssued
        if (current.remote.pendingOperation != null) return false
        if (ownsProtection) release()
        val acquired = acquire()
        if (acquired.status == HansActiveWorkUpdateStatus.REJECTED ||
            HansActiveWorkReason.REMOTE_CONTROL !in acquired.activeReasons) return false
        owner = current.identity
        generation = current.remote.generation
        promotionRevision = acquired.revision
        val ticket = ++acquisitionSequence
        cancelPromotionDeadline = schedulePromotionDeadline(PROMOTION_TIMEOUT_MILLIS) {
            if (ticket == acquisitionSequence && promotionRevision != null) release()
        }
        return true
    }

    fun onForeground(state: HansActiveWorkForegroundSnapshot) {
        if (state.sequence < foregroundSequence) return
        foregroundSequence = state.sequence
        if (state.remoteStopRequestSequence > stopSequence) {
            stopSequence = state.remoteStopRequestSequence
            if (ownsProtection) requestDisable()
            return
        }
        val protected = HansActiveWorkReason.REMOTE_CONTROL in state.protectedReasons &&
            state.event == HansActiveWorkForegroundEvent.PROTECTED
        val revision = promotionRevision
        if (revision != null && protected && state.revision >= revision) {
            val current = runtime()
            if (current == null || !matches(current) || !current.remote.runtimeReady) { release(); return }
            promotionRevision = null
            cancelPromotion()
            enableIssued = true
            if (!enable(current.identity)) {
                // A partial/failed transport write may still have enabled the native relay.
                // Only a still-proven disabled result permits releasing without terminating it.
                val after = runtime()
                if (after != null && matches(after) && !after.remote.isDisabledConfirmed) {
                    terminateRuntime(current.identity)
                } else release()
            }
        } else if (enableIssued && !protected) {
            requestDisable()
        } else if (revision != null && state.revision >= revision && !protected) {
            release()
        }
    }

    fun requestDisable(): Boolean {
        val current = runtime()
        if (promotionRevision != null && !enableIssued) { release(); return true }
        if (disableIssued) return true // A repeated stop never extends the RPC deadline.
        if (current == null || (ownsProtection && !matches(current))) { release(); return false }
        if (!current.remote.runtimeReady) { release(); return false }
        if (!ownsProtection) {
            if (current.remote.isDisabledConfirmed) return true
            // Settings may discover native access with no surviving local lifetime owner.
            // Its explicit Stop must still retain correlation and terminate on uncertainty.
            owner = current.identity
            generation = current.remote.generation
            enableIssued = true
        }
        disableIssued = true
        val accepted = disable(current.identity)
        if (!accepted) {
            if (enableIssued && !runtime().let { it != null && matches(it) && it.remote.isDisabledConfirmed }) {
                terminateRuntime(current.identity)
            } else release()
        }
        return accepted
    }

    fun onRemoteChanged() {
        val current = runtime()
        if (current == null || !current.remote.runtimeReady || (ownsProtection && !matches(current))) {
            release()
            return
        }
        if (!enableIssued) return
        val remote = current.remote
        if (!remote.statusConfirmedForCurrentRuntime) {
            terminateRuntime(current.identity)
        } else if (remote.pendingOperation != null) {
            // The initial disabled status can precede enable ACK. Revoke deliberately removes
            // local tool authority while its ACK is pending. Neither is proof of termination.
            return
        } else if (remote.isDisabledConfirmed) {
            release()
        } else if (disableIssued) {
            // A disable response which still says connected/connecting is not a successful stop.
            terminateRuntime(current.identity)
        } else if (!remote.localConsentGranted || remote.status == RemoteControlStatus.ERRORED) {
            requestDisable()
        }
    }

    private fun matches(current: RemoteControlHostRuntime<T>): Boolean =
        owner === current.identity && generation == current.remote.generation

    private fun cancelPromotion() {
        cancelPromotionDeadline?.invoke()
        cancelPromotionDeadline = null
    }

    private fun clear() {
        cancelPromotion()
        acquisitionSequence += 1L
        promotionRevision = null
        owner = null
        generation = null
        enableIssued = false
        disableIssued = false
    }

    private fun release() {
        val owned = ownsProtection
        clear()
        if (owned) releaseForeground()
    }

    private fun terminateRuntime(identity: T) {
        val owned = ownsProtection
        clear()
        try { restart(identity) } finally { if (owned) releaseForeground() }
    }

    private companion object { const val PROMOTION_TIMEOUT_MILLIS = 5_000L }
}
