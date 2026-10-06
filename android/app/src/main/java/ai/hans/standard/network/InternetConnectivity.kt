package ai.hans.standard.network

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver

/** The Android default data network, not mobile signal strength or Codex process readiness. */
enum class InternetStatus {
    UNKNOWN,
    OFFLINE,
    LIMITED,
    CAPTIVE_PORTAL,
    BLOCKED,
    ONLINE;

    /** Validation may be unavailable behind a VPN. Explicit attempts remain possible there. */
    val permitsExplicitRequest: Boolean
        get() = this != OFFLINE && this != BLOCKED

    fun notice(text: HansTextResolver): String = when (this) {
            UNKNOWN -> text.text(R.string.integration_network_unknown)
            OFFLINE -> text.text(R.string.integration_network_offline)
            LIMITED -> text.text(R.string.integration_network_limited)
            CAPTIVE_PORTAL -> text.text(R.string.integration_network_portal)
            BLOCKED -> text.text(R.string.integration_network_blocked)
            ONLINE -> ""
        }
}

data class InternetSnapshot(
    val status: InternetStatus = InternetStatus.UNKNOWN,
    val revision: Long = 0,
)

/** Pure callback reducer: a late onLost for Wi-Fi must not erase the new mobile network. */
internal class DefaultInternetNetworkState {
    private var currentNetwork: Long? = null
    private var callbackObserved = false
    private var capabilitiesStatus = InternetStatus.UNKNOWN
    private var blocked = false
    var snapshot = InternetSnapshot()
        private set

    fun seed(network: Long?, status: InternetStatus): Boolean {
        // Registration happens first. A callback winning the bootstrap race is authoritative,
        // including onLost for a network that disappeared before its onAvailable was observed.
        if (callbackObserved) return false
        currentNetwork = network
        capabilitiesStatus = status
        blocked = false
        return update(status)
    }

    fun available(network: Long): Boolean {
        callbackObserved = true
        if (currentNetwork == network) return false
        currentNetwork = network
        capabilitiesStatus = InternetStatus.UNKNOWN
        blocked = false
        return update(InternetStatus.UNKNOWN)
    }

    fun capabilities(
        network: Long,
        internet: Boolean,
        validated: Boolean,
        captivePortal: Boolean,
    ): Boolean {
        callbackObserved = true
        if (currentNetwork != network) return false
        capabilitiesStatus = when {
            captivePortal -> InternetStatus.CAPTIVE_PORTAL
            internet && validated -> InternetStatus.ONLINE
            else -> InternetStatus.LIMITED
        }
        return update(if (blocked) InternetStatus.BLOCKED else capabilitiesStatus)
    }

    fun blocked(network: Long, value: Boolean): Boolean {
        callbackObserved = true
        if (currentNetwork != network) return false
        blocked = value
        return update(if (blocked) InternetStatus.BLOCKED else capabilitiesStatus)
    }

    fun lost(network: Long): Boolean {
        callbackObserved = true
        if (currentNetwork != network) return false
        currentNetwork = null
        capabilitiesStatus = InternetStatus.OFFLINE
        blocked = false
        return update(InternetStatus.OFFLINE)
    }

    private fun update(status: InternetStatus): Boolean {
        if (snapshot.status == status) return false
        snapshot = InternetSnapshot(status, snapshot.revision + 1)
        return true
    }
}

/** Network recovery never authorizes re-executing a potentially accepted user instruction. */
internal fun internetWorkNotice(status: InternetStatus, hasActiveWork: Boolean, text: HansTextResolver): String =
    status.notice(text) + if (hasActiveWork && status != InternetStatus.ONLINE) {
        text.text(R.string.integration_network_work)
    } else {
        ""
    }

/** Fences callback/initial-snapshot races without polling or holding the monitor lock in clients. */
internal class InternetSnapshotObserver(private val observer: (InternetSnapshot) -> Unit) {
    private var active = true
    private var lastRevision = -1L

    @Synchronized
    fun deliver(snapshot: InternetSnapshot) {
        if (!active || snapshot.revision <= lastRevision) return
        lastRevision = snapshot.revision
        runCatching { observer(snapshot) }
    }

    @Synchronized
    fun close() {
        active = false
    }
}
