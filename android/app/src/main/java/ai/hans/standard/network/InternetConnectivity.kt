package ai.hans.standard.network

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

    val notice: String
        get() = when (this) {
            UNKNOWN -> "Internetverbindung noch nicht geprüft."
            OFFLINE -> "Keine Internetverbindung. Prüfe WLAN oder mobile Daten; im Ausland auch Datenroaming."
            LIMITED -> "Netz verbunden, Internetzugang nicht bestätigt. Prüfe WLAN, mobile Daten oder VPN. Ein Sendeversuch ist möglich."
            CAPTIVE_PORTAL -> "Dieses WLAN benötigt eine Anmeldung. Öffne die Interneteinstellungen."
            BLOCKED -> "Android blockiert den Internetzugriff für Hans. Prüfe Datensparen und die Datennutzung der App."
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
internal fun internetWorkNotice(status: InternetStatus, hasActiveWork: Boolean): String =
    status.notice + if (hasActiveWork && status != InternetStatus.ONLINE) {
        " Die laufende Antwort kann unterbrochen sein. Bereits gesendete Aufträge werden nicht erneut gesendet."
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
