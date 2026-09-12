package ai.hans.standard.plugins

/**
 * Passive evidence for one execution runtime. Reading evidence must never start a process, open a
 * network connection, or mutate plugin state. The owner of the runtime publishes a new immutable
 * value after its real readiness gate completes.
 */
internal data class PluginRuntimeEvidence(
    val version: String?,
    val abi: PluginRuntimeAbi,
    val readiness: PluginRuntimeReadiness,
) {
    init {
        version?.let(PluginRuntimeVersion::parse)
        require(readiness != PluginRuntimeReadiness.READY || version != null) {
            "A ready runtime must report its effective version"
        }
    }
}

internal fun interface PluginRuntimeEvidenceSource {
    fun current(): PluginRuntimeEvidence
}

/** A named, location-aware source registered by a concrete Hans runtime owner. */
internal data class PluginRuntimeProbeRegistration(
    val kind: PluginRuntimeKind,
    val location: PluginRuntimeLocation,
    val source: PluginRuntimeEvidenceSource,
)

/**
 * Event-driven runtime inventory used by plugin compatibility checks.
 *
 * The registry performs no optimistic inference from files, saved settings, or another runtime.
 * In particular, V8 Code Mode never satisfies a Node.js requirement. Each source must project the
 * result of that runtime's own effective readiness gate.
 */
internal class PluginRuntimeProbeRegistry(
    registrations: List<PluginRuntimeProbeRegistration>,
) {
    private val registrations = registrations.toList()

    init {
        require(this.registrations.size <= 32) { "Too many runtime probe registrations" }
        val localKeys = this.registrations
            .filter { it.location == PluginRuntimeLocation.LOCAL }
            .map(PluginRuntimeProbeRegistration::kind)
        require(localKeys.distinct().size == localKeys.size) {
            "Local runtime probe registrations must be unique by kind"
        }
    }

    fun snapshot(): List<PluginRuntimeProbe> = registrations
        .map { registration ->
            val evidence = registration.source.current()
            PluginRuntimeProbe(
                kind = registration.kind,
                location = registration.location,
                version = evidence.version?.let(PluginRuntimeVersion::parse),
                abi = evidence.abi,
                readiness = evidence.readiness,
            )
        }
        .sortedWith(compareBy({ it.kind.wireName }, { it.location.wireName }))

    companion object {
        /** Explicit evidence used for host facilities Hans Standard intentionally does not ship. */
        fun unavailable(
            abi: PluginRuntimeAbi = PluginRuntimeAbi.PLATFORM_INDEPENDENT,
        ): PluginRuntimeEvidenceSource = PluginRuntimeEvidenceSource {
            PluginRuntimeEvidence(
                version = null,
                abi = abi,
                readiness = PluginRuntimeReadiness.UNAVAILABLE,
            )
        }
    }
}

/**
 * The minimum honest local Android inventory. Concrete owners replace only their own source.
 * Keeping unavailable entries explicit makes diagnostics stable and prevents accidental claims
 * that Android has a POSIX shell, Node.js, a desktop UI, or a generic local MCP process host.
 */
internal object StandardAndroidRuntimeProbeRegistrations {
    fun create(
        localAbi: PluginRuntimeAbi,
        codeMode: PluginRuntimeEvidenceSource,
        python: PluginRuntimeEvidenceSource,
        git: PluginRuntimeEvidenceSource,
        http: PluginRuntimeEvidenceSource,
        androidCapabilities: PluginRuntimeEvidenceSource,
        remoteHttpMcp: PluginRuntimeEvidenceSource? = null,
        additional: List<PluginRuntimeProbeRegistration> = emptyList(),
    ): List<PluginRuntimeProbeRegistration> = buildList {
        add(local(PluginRuntimeKind.INSTRUCTION_ONLY, readyPlatformContract()))
        add(local(PluginRuntimeKind.ANDROID_CAPABILITY, androidCapabilities))
        add(local(PluginRuntimeKind.CODE_MODE_JAVASCRIPT, codeMode))
        add(local(PluginRuntimeKind.NODE_JS, PluginRuntimeProbeRegistry.unavailable(localAbi)))
        add(local(PluginRuntimeKind.EMBEDDED_PYTHON, python))
        add(local(PluginRuntimeKind.GIT, git))
        add(local(PluginRuntimeKind.HTTP_CLIENT, http))
        add(local(PluginRuntimeKind.MCP_SERVER, PluginRuntimeProbeRegistry.unavailable(localAbi)))
        add(local(PluginRuntimeKind.MCP_LOCAL_PROCESS, PluginRuntimeProbeRegistry.unavailable(localAbi)))
        add(local(PluginRuntimeKind.POSIX_SHELL, PluginRuntimeProbeRegistry.unavailable(localAbi)))
        add(
            local(
                PluginRuntimeKind.SIGNED_NATIVE_EXECUTABLE,
                PluginRuntimeProbeRegistry.unavailable(localAbi),
            ),
        )
        add(local(PluginRuntimeKind.DESKTOP_UI, PluginRuntimeProbeRegistry.unavailable(localAbi)))
        remoteHttpMcp?.let {
            add(
                PluginRuntimeProbeRegistration(
                    PluginRuntimeKind.MCP_REMOTE_HTTP,
                    PluginRuntimeLocation.REMOTE,
                    it,
                ),
            )
        }
        addAll(additional)
    }

    private fun local(
        kind: PluginRuntimeKind,
        source: PluginRuntimeEvidenceSource,
    ) = PluginRuntimeProbeRegistration(kind, PluginRuntimeLocation.LOCAL, source)

    private fun readyPlatformContract() = PluginRuntimeEvidenceSource {
        PluginRuntimeEvidence(
            version = "1.0",
            abi = PluginRuntimeAbi.PLATFORM_INDEPENDENT,
            readiness = PluginRuntimeReadiness.READY,
        )
    }
}
