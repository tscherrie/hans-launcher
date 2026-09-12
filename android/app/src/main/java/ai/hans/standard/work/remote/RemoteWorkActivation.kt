package ai.hans.standard.work.remote

/**
 * Explicit activation boundary. It performs no background refresh or polling: callers invoke it
 * after a user enables the optional worker, then publish the resulting immutable contributor.
 */
internal class RemoteWorkerActivationProbe(
    private val transport: RemoteWorkerTransport,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val freshnessMillis: Long = DEFAULT_PROBE_FRESHNESS_MILLIS,
) {
    init {
        require(freshnessMillis in MIN_PROBE_FRESHNESS_MILLIS..MAX_PROBE_FRESHNESS_MILLIS)
    }

    /** Disabled is the secure default and performs no network access. */
    fun activate(configuration: RemoteWorkerConfiguration): RemoteWorkerActivation? {
        if (!configuration.enabled) return null
        require(configuration.approvedAdapters.isNotEmpty()) {
            "Remote worker has no approved adapters"
        }
        val before = nowEpochMillis().also { require(it >= 0L) }
        val probe = transport.probe(configuration.identity)
        val after = nowEpochMillis().also { require(it >= before) }
        require(probe.proves(configuration.identity)) { "Remote worker probe identity mismatch" }
        require(probe.observedAtEpochMillis in before..after) {
            "Remote worker probe is not fresh"
        }
        val discovered = probe.adapters.associateBy { it.id }
        val exactAdapters = configuration.approvedAdapters.map { approval ->
            val adapter = requireNotNull(discovered[approval.id]) {
                "Approved remote work adapter was not discovered"
            }
            require(adapter.version == approval.version) {
                "Approved remote work adapter version changed"
            }
            adapter
        }
        return RemoteWorkerActivation(
            configuration = configuration,
            probe = probe,
            adapters = exactAdapters,
            expiresAtEpochMillis = Math.addExact(probe.observedAtEpochMillis, freshnessMillis),
        ).also { it.requireFresh(after) }
    }

    private companion object {
        const val MIN_PROBE_FRESHNESS_MILLIS = 10_000L
        const val DEFAULT_PROBE_FRESHNESS_MILLIS = 5 * 60_000L
        const val MAX_PROBE_FRESHNESS_MILLIS = 60 * 60_000L
    }
}
