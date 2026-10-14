package ai.hans.standard.settings.fastphone

import ai.hans.standard.fastphone.DecisionsAccess
import ai.hans.standard.voice.tts.android.AndroidKeystoreSpeechCredentialStore
import ai.hans.standard.voice.tts.android.SpeechCredentialStatus
import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The paid Decisions opt-in and encrypted credential are local to this phone. The credential
 * namespace is deliberately separate from legacy Speech API access and portable Hans settings.
 * No network is contacted while reading or changing this store.
 */
class FastPhoneUseSettingsStore internal constructor(
    context: Context,
    private val preferencesName: String,
    private val credentialPreferencesName: String,
    keyAlias: String,
) : DecisionsAccess, AutoCloseable {
    constructor(context: Context) : this(context, PREFERENCES_NAME, CREDENTIAL_PREFERENCES_NAME, KEY_ALIAS)

    private val preferenceStore = FastPhoneUsePreferenceStore(context, preferencesName)
    private val credentialPreferences = context.applicationContext.getSharedPreferences(
        credentialPreferencesName, Context.MODE_PRIVATE,
    )
    private val credentialStore = AndroidKeystoreSpeechCredentialStore(context, keyAlias, credentialPreferencesName)
    // Shared across instances: provisioning and Launcher writes must not race Keystore creation.
    private val lock = processStateLock
    private val listeners = CopyOnWriteArraySet<(FastPhoneUseSettingsSnapshot) -> Unit>()
    private var generation = 0L
    private var requestedEnabled = runCatching { preferenceStore.read() }.getOrDefault(false)
    private var serviceStatus = FastPhoneServiceStatus.NOT_VERIFIED
    private var credentialStatus = readCredentialStatus()
    // Ciphertext-only identity prevents duplicate preference callbacks cancelling a new task.
    private var credentialEnvelopeMarker = readCredentialEnvelopeMarker()
    private var closed = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val changed = synchronized(lock) {
            if (closed) null else {
                val marker = readCredentialEnvelopeMarker()
                if (marker == credentialEnvelopeMarker) return@OnSharedPreferenceChangeListener
                credentialEnvelopeMarker = marker
                generation++
                serviceStatus = FastPhoneServiceStatus.NOT_VERIFIED
                credentialStatus = readCredentialStatus()
                snapshotLocked()
            }
        }
        changed?.let(::publish)
    }

    init {
        credentialPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        instances.add(this)
    }

    override fun isEnabled(): Boolean = synchronized(lock) { !closed && snapshotLocked().requestsAllowed }

    override fun revision(): Long = synchronized(lock) { generation }

    /** The caller owns and wipes this mutable copy after constructing its one HTTP request. */
    override fun readApiKey(): CharArray? {
        var changed: FastPhoneUseSettingsSnapshot? = null
        val result = synchronized(lock) {
            if (closed || !snapshotLocked().requestsAllowed) return@synchronized null
            val token = credentialStore.loadBearerToken()
            if (token == null) {
                credentialStatus = readCredentialStatus()
                generation++
                changed = snapshotLocked()
                null
            } else {
                token.toCharArray()
            }
        }
        changed?.let(::publish)
        return result
    }

    /** No optimistic enabled state: publish only after the private preference commit succeeds. */
    fun setEnabled(enabled: Boolean): Boolean {
        val changed = synchronized(lock) {
            if (closed) return false
            if (runCatching { preferenceStore.save(enabled) }.isFailure) return false
            requestedEnabled = enabled
            generation++
            serviceStatus = FastPhoneServiceStatus.NOT_VERIFIED
            credentialStatus = readCredentialStatus()
            snapshotLocked()
        }
        publish(changed)
        notifyPeers()
        return true
    }

    /** Input remains caller-owned; neither this store nor its snapshot retains plaintext. */
    fun saveApiKey(key: CharArray): Boolean {
        var saved = true
        val changed = synchronized(lock) {
            if (closed) return false
            if (runCatching { credentialStore.saveBearerToken(key) }.isFailure) {
                saved = false
                val current = readCredentialStatus()
                if (current == credentialStatus) return false
                credentialStatus = current
                generation++
                return@synchronized snapshotLocked()
            }
            credentialStatus = readCredentialStatus()
            if (credentialStatus != FastPhoneCredentialStatus.AVAILABLE) return false
            credentialEnvelopeMarker = readCredentialEnvelopeMarker()
            generation++
            serviceStatus = FastPhoneServiceStatus.NOT_VERIFIED
            snapshotLocked()
        }
        publish(changed)
        notifyPeers()
        return saved
    }

    fun removeApiKey(): Boolean {
        var removed = true
        val changed = synchronized(lock) {
            if (closed) return false
            if (runCatching { credentialStore.clear() }.isFailure) removed = false
            credentialStatus = readCredentialStatus()
            if (credentialStatus != FastPhoneCredentialStatus.MISSING) removed = false
            credentialEnvelopeMarker = readCredentialEnvelopeMarker()
            generation++
            serviceStatus = FastPhoneServiceStatus.NOT_VERIFIED
            snapshotLocked()
        }
        publish(changed)
        notifyPeers()
        return removed
    }

    override fun recordFailure(code: String, expectedRevision: Long) =
        setServiceStatus(fastPhoneServiceFailure(code), expectedRevision)

    override fun recordSuccess(expectedRevision: Long) =
        setServiceStatus(FastPhoneServiceStatus.VERIFIED, expectedRevision)

    fun snapshot(): FastPhoneUseSettingsSnapshot {
        val result = synchronized(lock) {
            val current = readCredentialStatus()
            if (current != credentialStatus) {
                credentialStatus = current
                generation++
                serviceStatus = FastPhoneServiceStatus.NOT_VERIFIED
            }
            snapshotLocked()
        }
        return result
    }

    fun subscribe(listener: (FastPhoneUseSettingsSnapshot) -> Unit): AutoCloseable {
        listeners.add(listener)
        listener(snapshot())
        return AutoCloseable { listeners.remove(listener) }
    }

    override fun close() {
        synchronized(lock) { closed = true; generation++ }
        credentialPreferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        instances.remove(this)
        listeners.clear()
    }

    private fun setServiceStatus(status: FastPhoneServiceStatus, expectedRevision: Long) {
        val changed = synchronized(lock) {
            if (closed || generation != expectedRevision) return
            serviceStatus = status
            snapshotLocked()
        }
        publish(changed)
    }

    private fun snapshotLocked() = FastPhoneUseSettingsSnapshot(
        requestedEnabled = requestedEnabled,
        credentialStatus = credentialStatus,
        serviceStatus = serviceStatus,
        revision = generation,
    )

    private fun readCredentialStatus(): FastPhoneCredentialStatus = when (credentialStore.credentialStatus()) {
        SpeechCredentialStatus.MISSING -> FastPhoneCredentialStatus.MISSING
        SpeechCredentialStatus.AVAILABLE -> FastPhoneCredentialStatus.AVAILABLE
        SpeechCredentialStatus.TEMPORARILY_UNAVAILABLE -> FastPhoneCredentialStatus.TEMPORARILY_UNAVAILABLE
    }

    private fun publish(value: FastPhoneUseSettingsSnapshot) {
        listeners.forEach { listener -> runCatching { listener(value) } }
    }

    private fun refreshExternalPreference() {
        val changed = synchronized(lock) {
            if (closed) return
            requestedEnabled = runCatching { preferenceStore.read() }.getOrDefault(false)
            credentialStatus = readCredentialStatus()
            credentialEnvelopeMarker = readCredentialEnvelopeMarker()
            generation++
            serviceStatus = FastPhoneServiceStatus.NOT_VERIFIED
            snapshotLocked()
        }
        publish(changed)
    }

    private fun notifyPeers() {
        instances.filter { it !== this && (it.preferencesName == preferencesName ||
            it.credentialPreferencesName == credentialPreferencesName) }
            .forEach { it.refreshExternalPreference() }
    }

    private fun readCredentialEnvelopeMarker(): List<Any?> = listOf(
        credentialPreferences.getInt("version", 0),
        credentialPreferences.getString("iv", null),
        credentialPreferences.getString("ciphertext", null),
    )

    companion object {
        const val PREFERENCES_NAME = "hans_fast_phone_use_v1"
        const val CREDENTIAL_PREFERENCES_NAME = "hans_fast_phone_credential_v1"
        private const val KEY_ALIAS = "ai.hans.standard.decisions-api.v1"
        private val processStateLock = Any()
        private val instances = CopyOnWriteArraySet<FastPhoneUseSettingsStore>()
    }
}
