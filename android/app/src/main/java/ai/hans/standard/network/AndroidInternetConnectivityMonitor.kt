package ai.hans.standard.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

/** One passive process-local callback. No HTTP probes, polling, settings writes or wake locks. */
class AndroidInternetConnectivityMonitor(context: Context) : Closeable {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private val state = DefaultInternetNetworkState()
    private val observers = CopyOnWriteArraySet<InternetSnapshotObserver>()
    private var closed = false
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = mutate { available(network.networkHandle) }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            mutate {
                this.capabilities(
                    network.networkHandle,
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                )
            }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) =
            mutate { this.blocked(network.networkHandle, blocked) }

        override fun onLost(network: Network) = mutate { lost(network.networkHandle) }
    }

    init {
        // Register before the one initial read, or a network lost in that gap may never produce
        // a callback. A callback that wins the read/apply race prevents a stale bootstrap seed.
        // Callback parameters stay authoritative; no synchronous getters run inside callbacks.
        runCatching {
            val manager = connectivity ?: return@runCatching
            manager.registerDefaultNetworkCallback(callback)
            registered = true
            val network = manager.activeNetwork
            val capabilities = network?.let(manager::getNetworkCapabilities)
            synchronized(lock) {
                state.seed(
                    network?.networkHandle,
                    when {
                        network == null -> InternetStatus.OFFLINE
                        capabilities == null -> InternetStatus.UNKNOWN
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) ->
                            InternetStatus.CAPTIVE_PORTAL
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                            InternetStatus.ONLINE
                        else -> InternetStatus.LIMITED
                    },
                )
            }
        }.onFailure {
            // Missing service/permission/registration is unknown, never evidence of no Internet.
            synchronized(lock) { state.seed(null, InternetStatus.UNKNOWN) }
        }
    }

    fun snapshot(): InternetSnapshot = synchronized(lock) { state.snapshot }

    fun observe(observer: (InternetSnapshot) -> Unit): Closeable {
        val subscription = InternetSnapshotObserver(observer)
        synchronized(lock) {
            if (closed) return Closeable {}
            observers += subscription
        }
        subscription.deliver(snapshot())
        return Closeable {
            observers -= subscription
            subscription.close()
        }
    }

    private fun mutate(change: DefaultInternetNetworkState.() -> Boolean) {
        val next = synchronized(lock) {
            if (closed || !state.change()) return
            state.snapshot
        }
        observers.forEach { it.deliver(next) }
    }

    override fun close() {
        val (unregister, subscriptions) = synchronized(lock) {
            if (closed) return
            closed = true
            registered to observers.toList().also { observers.clear() }
        }
        subscriptions.forEach { it.close() }
        if (unregister) runCatching { connectivity?.unregisterNetworkCallback(callback) }
    }
}
