package ai.hans.standard.work.remote

import android.content.Context
import ai.hans.standard.artifacts.AtomicArtifactStore
import ai.hans.standard.integration.DynamicToolSnapshotApplyResult
import ai.hans.standard.workspace.PrivateWorkspaceStore
import java.io.File
import java.util.concurrent.Executor

/**
 * Production composition root for the optional remote compute route.
 *
 * Construction and every passive read are deliberately local-only. In particular, an enabled
 * persisted request is not treated as a live route after process restart. Only
 * [activateExplicitly] crosses the authenticated network boundary, and publication has to be
 * proven by the current App Server generation before [passiveState] can report EFFECTIVE.
 */
internal class RemoteWorkerProductionRuntime private constructor(
    private val owner: RemoteWorkerRuntimeOwner,
    private val activationExecutor: Executor,
) {
    /** App-private storage read only: no probe, network request, worker start or App Server start. */
    fun passiveState(): RemoteWorkerRuntimeProjection = owner.passiveState()

    /** Local-only Settings projection; authentication material and key aliases remain private. */
    fun passiveEditableConfiguration(): RemoteWorkerEditableConfigurationState =
        owner.passiveEditableConfiguration()

    /** Persists requested configuration. Enabling is still inert until [activateExplicitly]. */
    fun save(
        expectedRevision: Long,
        replacement: RemoteWorkerConfiguration,
    ): RemoteWorkerRuntimeUpdateResult = owner.save(expectedRevision, replacement)

    /** Current-process proof used while assembling the immutable interactive tool contract. */
    fun activePublication(): RemoteWorkerActivePublication? = owner.activePublication()

    /**
     * The only production activation boundary. The caller must be handling an explicit user
     * action. Scheduling failure is reported as ActivationFailed and cannot create effective
     * state. The completion runs on [activationExecutor].
     */
    fun activateExplicitly(
        expectedRevision: Long,
        completion: (RemoteWorkerRuntimeUpdateResult) -> Unit,
    ): Boolean = try {
        activationExecutor.execute {
            completion(owner.activate(expectedRevision))
        }
        true
    } catch (_: RuntimeException) {
        completion(RemoteWorkerRuntimeUpdateResult.ActivationFailed)
        false
    }

    internal companion object {
        fun create(
            context: Context,
            workspaceStore: PrivateWorkspaceStore,
            artifactStore: AtomicArtifactStore,
            activationExecutor: Executor,
            onPublicationChanged: () -> RemoteWorkerPublicationDisposition,
        ): RemoteWorkerProductionRuntime {
            val transport = OkHttpPinnedRemoteWorkerTransport(
                authenticator = AndroidKeystoreRemoteWorkAuthenticator(),
            )
            val contributorFactory = RemoteWorkDynamicToolContributorFactory(
                activationProbe = RemoteWorkerActivationProbe(transport),
                transport = transport,
                journal = RemoteWorkJournal(
                    File(context.applicationContext.noBackupFilesDir, JOURNAL_DIRECTORY),
                ),
                workspaceStore = workspaceStore,
                artifactStore = artifactStore,
                backgroundExecutor = activationExecutor,
            )
            return RemoteWorkerProductionRuntime(
                owner = RemoteWorkerRuntimeOwner(
                    configurations = AppPrivateRemoteWorkerConfigurationStore(context),
                    activator = RemoteWorkerContributionActivator(contributorFactory::create),
                    onPublicationChanged = onPublicationChanged,
                ),
                activationExecutor = activationExecutor,
            )
        }

        /** Narrow test seam; it does not weaken the production composition above. */
        internal fun fromOwner(
            owner: RemoteWorkerRuntimeOwner,
            activationExecutor: Executor,
        ): RemoteWorkerProductionRuntime = RemoteWorkerProductionRuntime(owner, activationExecutor)

        private const val JOURNAL_DIRECTORY = "remote-work/operations-v1"
    }
}

/** Exact, exhaustive translation of the App Server contract-application result. */
internal fun DynamicToolSnapshotApplyResult?.toRemoteWorkerPublicationDisposition():
    RemoteWorkerPublicationDisposition = when (this) {
    is DynamicToolSnapshotApplyResult.Applied -> RemoteWorkerPublicationDisposition.Applied
    is DynamicToolSnapshotApplyResult.Unchanged -> RemoteWorkerPublicationDisposition.Unchanged
    is DynamicToolSnapshotApplyResult.Queued -> RemoteWorkerPublicationDisposition.Queued
    is DynamicToolSnapshotApplyResult.StaleDenied -> RemoteWorkerPublicationDisposition.StaleDenied
    is DynamicToolSnapshotApplyResult.ActivationFailed ->
        RemoteWorkerPublicationDisposition.ActivationFailed
    null -> RemoteWorkerPublicationDisposition.MissingHost
}
