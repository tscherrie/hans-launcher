package ai.hans.standard.work.remote

internal fun interface RemoteWorkerContributionActivator {
    fun activate(configuration: RemoteWorkerConfiguration): RemoteWorkDynamicToolContribution?
}

internal sealed interface RemoteWorkerPublicationDisposition {
    data object Applied : RemoteWorkerPublicationDisposition
    data object Unchanged : RemoteWorkerPublicationDisposition
    data object Queued : RemoteWorkerPublicationDisposition
    data object StaleDenied : RemoteWorkerPublicationDisposition
    data object ActivationFailed : RemoteWorkerPublicationDisposition
    data object MissingHost : RemoteWorkerPublicationDisposition
}

internal enum class RemoteWorkerRuntimeStatus {
    UNCONFIGURED,
    DISABLED,
    NEEDS_ACTIVATION,
    EFFECTIVE,
    STORAGE_CORRUPT,
}

/**
 * Network-free, secret-free and atomic projection for Settings and setup surfaces.
 *
 * [requestedEnabled] is only the persisted request. It intentionally differs from [effective],
 * which is true only after the exact dynamic-tool generation was applied by the App Server host.
 */
internal data class RemoteWorkerRuntimeProjection(
    val revision: Long,
    val configured: Boolean,
    val requestedEnabled: Boolean,
    val effective: Boolean,
    val status: RemoteWorkerRuntimeStatus,
    val workerId: String?,
    val approvedAdapters: List<String>,
    val configurationDigest: String?,
    val storageHealthy: Boolean,
    val activeConfigurationRevision: Long?,
    val activeConfigurationDigest: String?,
    val activeRevisionToken: String?,
)

/** Exact process-owned publication used when assembling the revisioned dynamic-tool snapshot. */
internal data class RemoteWorkerActivePublication(
    val configurationRevision: Long,
    val configurationDigest: String,
    val revisionToken: String,
    val contribution: RemoteWorkDynamicToolContribution,
) {
    init {
        require(configurationRevision > 0L)
        require(configurationDigest == contribution.activation.configuration.identity.configurationDigest)
        require(revisionToken == remoteWorkerRevisionToken(configurationRevision, configurationDigest))
    }

    val revision: Long get() = configurationRevision
}

internal sealed interface RemoteWorkerRuntimeUpdateResult {
    data class Saved(val state: RemoteWorkerRuntimeProjection) :
        RemoteWorkerRuntimeUpdateResult

    data object RevisionMismatch : RemoteWorkerRuntimeUpdateResult
    data object Corrupt : RemoteWorkerRuntimeUpdateResult
    data object PersistenceFailed : RemoteWorkerRuntimeUpdateResult
    data object ActivationFailed : RemoteWorkerRuntimeUpdateResult
    data object PublicationFailed : RemoteWorkerRuntimeUpdateResult
}

/**
 * Process owner for the optional remote compute route.
 *
 * Reading state is passive and network-free. Saving an enabled configuration does not contact the
 * server or publish tools; [activate] is the explicit user-driven network boundary. A route is
 * effective only after an exact probe and an Applied/Unchanged publication disposition.
 */
internal class RemoteWorkerRuntimeOwner(
    private val configurations: AppPrivateRemoteWorkerConfigurationStore,
    private val activator: RemoteWorkerContributionActivator,
    private val onPublicationChanged: () -> RemoteWorkerPublicationDisposition,
) {
    private val lock = Any()
    private var published: RemoteWorkerActivePublication? = null

    /** Performs no activation, host call or network I/O. */
    fun passiveState(): RemoteWorkerRuntimeProjection = synchronized(lock) {
        runtimeProjectionLocked()
    }

    /** Exact local form values; still performs no activation, host call or network I/O. */
    fun passiveEditableConfiguration(): RemoteWorkerEditableConfigurationState = synchronized(lock) {
        configurations.passiveEditableState()
    }

    fun save(
        expectedRevision: Long,
        replacement: RemoteWorkerConfiguration,
    ): RemoteWorkerRuntimeUpdateResult = synchronized(lock) {
        val previouslyPublished = validPublicationLocked(configurations.passiveState())
        if (previouslyPublished != null && !transitionPublicationLocked(null)) {
            return@synchronized RemoteWorkerRuntimeUpdateResult.PublicationFailed
        }
        val mutation = configurations.compareAndSet(expectedRevision, replacement)
        if (mutation != RemoteWorkerConfigurationMutationResult.APPLIED) {
            // PERSISTENCE_FAILED is returned only after the store re-read and proved that the
            // exact prior configuration was restored. A mismatch/corrupt store must never
            // republish a potentially stale executor.
            if (
                mutation == RemoteWorkerConfigurationMutationResult.PERSISTENCE_FAILED &&
                previouslyPublished != null &&
                !transitionPublicationLocked(previouslyPublished)
            ) {
                return@synchronized RemoteWorkerRuntimeUpdateResult.PublicationFailed
            }
            return@synchronized when (mutation) {
                RemoteWorkerConfigurationMutationResult.REVISION_MISMATCH ->
                    RemoteWorkerRuntimeUpdateResult.RevisionMismatch
                RemoteWorkerConfigurationMutationResult.CORRUPT ->
                    RemoteWorkerRuntimeUpdateResult.Corrupt
                RemoteWorkerConfigurationMutationResult.PERSISTENCE_FAILED ->
                    RemoteWorkerRuntimeUpdateResult.PersistenceFailed
                RemoteWorkerConfigurationMutationResult.APPLIED -> error("unreachable")
            }
        }
        val saved = try {
            configurations.readExact()
        } catch (_: Throwable) {
            return@synchronized RemoteWorkerRuntimeUpdateResult.Corrupt
        }
        if (saved.revision != expectedRevision + 1L || saved.configuration != replacement) {
            return@synchronized RemoteWorkerRuntimeUpdateResult.Corrupt
        }
        runCatching { runtimeProjectionLocked() }
            .fold(
                onSuccess = RemoteWorkerRuntimeUpdateResult::Saved,
                onFailure = { RemoteWorkerRuntimeUpdateResult.Corrupt },
            )
    }

    /**
     * Performs one exact, fresh activation probe. An already-effective exact revision is a no-op;
     * it cannot swap the executor below an Unchanged snapshot revision.
     */
    fun activate(expectedRevision: Long): RemoteWorkerRuntimeUpdateResult = synchronized(lock) {
        val saved = try {
            configurations.readExact()
        } catch (_: RemoteWorkerConfigurationCorruptException) {
            return@synchronized RemoteWorkerRuntimeUpdateResult.Corrupt
        }
        if (saved.revision != expectedRevision) {
            return@synchronized RemoteWorkerRuntimeUpdateResult.RevisionMismatch
        }
        val configuration = saved.configuration
            ?: return@synchronized RemoteWorkerRuntimeUpdateResult.RevisionMismatch
        val current = validPublicationLocked(configurations.passiveState())
        if (!configuration.enabled) {
            if (current != null && !transitionPublicationLocked(null)) {
                return@synchronized RemoteWorkerRuntimeUpdateResult.PublicationFailed
            }
            return@synchronized RemoteWorkerRuntimeUpdateResult.Saved(runtimeProjectionLocked())
        }
        if (current != null) {
            return@synchronized RemoteWorkerRuntimeUpdateResult.Saved(runtimeProjectionLocked())
        }
        val contribution = runCatching { activator.activate(configuration) }.getOrNull()
            ?: return@synchronized RemoteWorkerRuntimeUpdateResult.ActivationFailed
        if (
            contribution.activation.configuration != configuration ||
            contribution.activation.configuration.identity.configurationDigest !=
            configuration.identity.configurationDigest
        ) {
            return@synchronized RemoteWorkerRuntimeUpdateResult.ActivationFailed
        }
        val next = runCatching {
            RemoteWorkerActivePublication(
                configurationRevision = saved.revision,
                configurationDigest = configuration.identity.configurationDigest,
                revisionToken = remoteWorkerRevisionToken(
                    saved.revision,
                    configuration.identity.configurationDigest,
                ),
                contribution = contribution,
            )
        }.getOrElse {
            return@synchronized RemoteWorkerRuntimeUpdateResult.ActivationFailed
        }
        if (!transitionPublicationLocked(next)) {
            return@synchronized RemoteWorkerRuntimeUpdateResult.PublicationFailed
        }
        RemoteWorkerRuntimeUpdateResult.Saved(runtimeProjectionLocked())
    }

    /** Network-free exact projection consumed while rebuilding the revisioned tool contract. */
    fun activePublication(): RemoteWorkerActivePublication? = synchronized(lock) {
        validPublicationLocked(configurations.passiveState())
    }

    /** Compatibility helper for callers which only need the currently effective contribution. */
    fun activeContribution(): RemoteWorkDynamicToolContribution? =
        activePublication()?.contribution

    private fun runtimeProjectionLocked(): RemoteWorkerRuntimeProjection {
        val configuration = configurations.passiveState()
        val active = validPublicationLocked(configuration)
        val requestedEnabled = configuration.storageHealthy && configuration.enabled
        val status = when {
            !configuration.storageHealthy -> RemoteWorkerRuntimeStatus.STORAGE_CORRUPT
            !configuration.configured -> RemoteWorkerRuntimeStatus.UNCONFIGURED
            !requestedEnabled -> RemoteWorkerRuntimeStatus.DISABLED
            active != null -> RemoteWorkerRuntimeStatus.EFFECTIVE
            else -> RemoteWorkerRuntimeStatus.NEEDS_ACTIVATION
        }
        return RemoteWorkerRuntimeProjection(
            revision = configuration.revision,
            configured = configuration.configured,
            requestedEnabled = requestedEnabled,
            effective = active != null,
            status = status,
            workerId = configuration.workerId,
            approvedAdapters = configuration.approvedAdapters,
            configurationDigest = configuration.configurationDigest,
            storageHealthy = configuration.storageHealthy,
            activeConfigurationRevision = active?.configurationRevision,
            activeConfigurationDigest = active?.configurationDigest,
            activeRevisionToken = active?.revisionToken,
        )
    }

    private fun validPublicationLocked(
        configuration: RemoteWorkerPublicConfigurationState,
    ): RemoteWorkerActivePublication? {
        val active = published ?: return null
        if (
            !configuration.storageHealthy ||
            !configuration.enabled ||
            configuration.revision != active.configurationRevision ||
            configuration.configurationDigest != active.configurationDigest
        ) {
            published = null
            return null
        }
        return active
    }

    /**
     * Temporarily exposes [candidate] to the host callback. Non-effective dispositions restore and
     * re-project the exact old publication, so a queued candidate is never reported as active.
     */
    private fun transitionPublicationLocked(candidate: RemoteWorkerActivePublication?): Boolean {
        val previous = published
        published = candidate
        val disposition = publicationDispositionLocked()
        if (disposition.isEffective()) return true

        published = previous
        val restoration = publicationDispositionLocked()
        if (!restoration.isEffective()) {
            // The host did not prove the restoration. Never claim an effective route locally.
            published = null
        }
        return false
    }

    private fun publicationDispositionLocked(): RemoteWorkerPublicationDisposition =
        runCatching(onPublicationChanged)
            .getOrDefault(RemoteWorkerPublicationDisposition.ActivationFailed)

    private fun RemoteWorkerPublicationDisposition.isEffective(): Boolean =
        this is RemoteWorkerPublicationDisposition.Applied ||
            this is RemoteWorkerPublicationDisposition.Unchanged

}

private fun remoteWorkerRevisionToken(
    configurationRevision: Long,
    configurationDigest: String,
): String {
    require(configurationRevision > 0L)
    require(configurationDigest.matches(Regex("[a-f0-9]{64}")))
    return "remote-worker:$configurationRevision:$configurationDigest"
}
