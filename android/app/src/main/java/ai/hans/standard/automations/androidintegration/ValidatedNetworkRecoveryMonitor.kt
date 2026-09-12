package ai.hans.standard.automations.androidintegration

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable

/** Emits only offline-to-validated-online transitions; it never polls or wakes Codex itself. */
class ValidatedNetworkRecoveryMonitor(
    context: Context,
    private val onRestored: () -> Unit,
    private val onUnavailable: () -> Unit,
) : Closeable {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var closed = false
    private val transitions = ValidatedNetworkTransitionGate(currentValidated())
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            refresh()
    }

    init {
        if (!transitions.currentValidated) runCatching(onUnavailable)
        runCatching { connectivity?.registerDefaultNetworkCallback(callback) }
        // Close the small read/register race without polling.
        refresh()
    }

    override fun close() {
        val shouldUnregister = synchronized(lock) {
            if (closed) false else true.also { closed = true }
        }
        if (shouldUnregister) runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }

    private fun refresh() {
        val current = currentValidated()
        val transition = synchronized(lock) {
            if (closed) return
            transitions.update(current)
        }
        when (transition) {
            ValidatedNetworkTransition.RESTORED -> runCatching(onRestored)
            ValidatedNetworkTransition.UNAVAILABLE -> runCatching(onUnavailable)
            ValidatedNetworkTransition.UNCHANGED -> Unit
        }
    }

    private fun currentValidated(): Boolean = runCatching {
        val active = connectivity?.activeNetwork ?: return@runCatching false
        val capabilities = connectivity.getNetworkCapabilities(active) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}

internal enum class ValidatedNetworkTransition { UNCHANGED, RESTORED, UNAVAILABLE }

/** Pure transition state for deterministic offline/online regression coverage. */
internal class ValidatedNetworkTransitionGate(initialValidated: Boolean) {
    var currentValidated: Boolean = initialValidated
        private set

    fun update(validated: Boolean): ValidatedNetworkTransition {
        val previous = currentValidated
        currentValidated = validated
        return when {
            previous == validated -> ValidatedNetworkTransition.UNCHANGED
            validated -> ValidatedNetworkTransition.RESTORED
            else -> ValidatedNetworkTransition.UNAVAILABLE
        }
    }
}
