package ai.hans.standard

import ai.hans.standard.localization.AndroidHansTextResolver

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log
import ai.hans.standard.automations.AndroidAutomationRuntimeFactory
import ai.hans.standard.automations.BackupMaintainedAutomationStorage
import ai.hans.standard.automations.PersistentAutomationStorage
import ai.hans.standard.automations.SQLiteAutomationSnapshotPersistence
import ai.hans.standard.automations.AutomationRuntimeOwner
import ai.hans.standard.automations.AutomationRuntimeActivityObserver
import ai.hans.standard.automations.HansAutomationDynamicToolExecutor
import ai.hans.standard.automations.HansAutomationRuntime
import ai.hans.standard.backup.AndroidHansBackupStateGateway
import ai.hans.standard.backup.HansBackupCoordinator
import ai.hans.standard.backup.HansBackupProcessState
import ai.hans.standard.backup.startRuntimeAfterBackupRecovery
import ai.hans.standard.automations.androidgateway.AndroidAutomationCodexGateway
import ai.hans.standard.automations.androidgateway.AndroidAutomationCodexGatewayFactory
import ai.hans.standard.automations.androidgateway.AutomationDeveloperInstructionsSource
import ai.hans.standard.automations.androidgateway.AutomationDynamicToolExecutorSource
import ai.hans.standard.automations.androidgateway.IndependentCodexSelection
import ai.hans.standard.automations.androidgateway.IndependentCodexSelectionSource
import ai.hans.standard.automations.androidintegration.AndroidAutomationLiveEnvironmentSource
import ai.hans.standard.automations.androidintegration.ValidatedNetworkRecoveryMonitor
import ai.hans.standard.automations.AndroidAutomationNetworkRecoveryBackstop
import ai.hans.standard.automations.androidui.SwappableAutomationToolApprovalProvider
import ai.hans.standard.artifacts.AtomicArtifactStore
import ai.hans.standard.browser.BrowserDynamicToolExecutor
import ai.hans.standard.browser.IsolatedWorkBrowserHttpCallFactory
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.diagnostics.MigrationAccountPhase
import ai.hans.standard.diagnostics.MigrationDiagnosticsAndroidEnvironment
import ai.hans.standard.diagnostics.MigrationDiagnosticsHashes
import ai.hans.standard.diagnostics.MigrationSessionReadinessProjection
import ai.hans.standard.diagnostics.MigrationSessionReadinessPublisher
import ai.hans.standard.diagnostics.PassiveInitializedValue
import ai.hans.standard.documents.DocumentArtifactAdapter
import ai.hans.standard.documents.DocumentDynamicToolExecutor
import ai.hans.standard.integration.AndroidCodexSessionHost
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.CodexClientObserver
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.integration.DynamicToolContributor
import ai.hans.standard.integration.DynamicToolSnapshotApplyResult
import ai.hans.standard.integration.ValidatedNotificationAnnouncementCenter
import ai.hans.standard.git.AppPrivateGitRepositoryStore
import ai.hans.standard.git.GitCredentialBroker
import ai.hans.standard.git.GitCredentialSession
import ai.hans.standard.git.GitDynamicReadiness
import ai.hans.standard.git.GitDynamicReadinessProbe
import ai.hans.standard.git.GitDynamicToolExecutor
import ai.hans.standard.git.GitRemoteScheme
import ai.hans.standard.git.GitIdentity
import ai.hans.standard.git.GitTransportReadiness
import ai.hans.standard.git.GitTransportReadinessProbe
import ai.hans.standard.git.JGitRepositoryService
import ai.hans.standard.notifications.NotificationRelevanceContext
import ai.hans.standard.notifications.NotificationTriageIntegration
import ai.hans.standard.notifications.UserFacingDeliveryDisposition
import ai.hans.standard.notifications.UserFacingNotificationActivationDisposition
import ai.hans.standard.notifications.UserFacingNotificationDelivery
import ai.hans.standard.notifications.UserFacingNotificationSuggestionSink
import ai.hans.standard.notifications.ValidatedNotificationSuggestionController
import ai.hans.standard.mcp.AndroidKeystoreRemoteMcpOAuthVault
import ai.hans.standard.mcp.OkHttpRemoteMcpCallFactory
import ai.hans.standard.mcp.PluginSurfaceRemoteMcpRequirementSource
import ai.hans.standard.mcp.RemoteMcpBuiltInVerifiers
import ai.hans.standard.mcp.RemoteMcpActivationDefinition
import ai.hans.standard.mcp.RemoteMcpActivationIdentity
import ai.hans.standard.mcp.RemoteMcpConfigurationIdentity
import ai.hans.standard.mcp.RemoteMcpDiscoveryCatalogStore
import ai.hans.standard.mcp.RemoteMcpFinalizedDynamicToolSource
import ai.hans.standard.mcp.RemoteMcpPassiveStatusRegistry
import ai.hans.standard.mcp.RemoteMcpPluginRuntimeDependencyPreparer
import ai.hans.standard.mcp.RemoteMcpPolicyOwnerReloadCallback
import ai.hans.standard.mcp.RemoteMcpPolicyReviewCatalog
import ai.hans.standard.mcp.RemoteMcpPolicyReviewCoordinator
import ai.hans.standard.mcp.RemoteMcpPolicyReviewDecision
import ai.hans.standard.mcp.RemoteMcpContractRefreshCallback
import ai.hans.standard.mcp.RemoteMcpExactSessionCloseCallback
import ai.hans.standard.mcp.RemoteMcpSession
import ai.hans.standard.mcp.RemoteMcpSessionFactory
import ai.hans.standard.mcp.RemoteMcpSessionRegistry
import ai.hans.standard.mcp.RemoteMcpToolPolicyOwner
import ai.hans.standard.mcp.RemoteMcpToolPolicyStore
import ai.hans.standard.mcp.RemoteMcpToolEffect
import ai.hans.standard.mcp.SessionRemoteMcpPreflightDiscoverer
import ai.hans.standard.mcp.oauth.RemoteMcpOAuthConnectionSpec
import ai.hans.standard.mcp.oauth.SignedRemoteMcpOAuthClientOptionsProvider
import ai.hans.standard.phone.accessibility.android.AndroidDictationLifecycleRegistry
import ai.hans.standard.phone.consent.AtomicPersistentAndroidConsentStore
import ai.hans.standard.phone.consent.PersistentAndroidConsentStore
import ai.hans.standard.phone.display.AndroidMp01DisplayRuntime
import ai.hans.standard.phone.display.Mp01DisplayDynamicToolExecutor
import ai.hans.standard.phone.publicapi.AndroidPublicPhonePlatform
import ai.hans.standard.phone.publicapi.PublicPhoneCapabilityState
import ai.hans.standard.phone.publicapi.PublicPhoneToolIntegration
import ai.hans.standard.phone.publicapi.PublicPhoneToolIntegrationFactory
import ai.hans.standard.phone.publicapi.SwappablePublicPhoneConfirmationProvider
import ai.hans.standard.plugins.PluginDomainSnapshot
import ai.hans.standard.plugins.AndroidRuntimeEvidenceProjection
import ai.hans.standard.plugins.PluginRuntimeAbi
import ai.hans.standard.plugins.PluginRuntimeEvidenceSource
import ai.hans.standard.plugins.PluginInstallTransactionCoordinator
import ai.hans.standard.plugins.BoundedPluginSourceIdentityHasher
import ai.hans.standard.plugins.PluginRemoteMcpPolicyEffect
import ai.hans.standard.plugins.PluginRemoteMcpPolicyReviewResult
import ai.hans.standard.plugins.PluginRemoteMcpPolicyReviewSubmission
import ai.hans.standard.plugins.PluginRuntimeManifestResult
import ai.hans.standard.plugins.PluginSourceHashCancellation
import ai.hans.standard.plugins.PluginRuntimeCompatibilityService
import ai.hans.standard.plugins.PluginRuntimeManifestLoader
import ai.hans.standard.plugins.PluginRuntimeProbeRegistry
import ai.hans.standard.plugins.CancellablePythonWheelDownloader
import ai.hans.standard.plugins.CompositePluginRuntimeDependencyPreparer
import ai.hans.standard.plugins.CompositePluginRuntimeDependencyRecoveryProvider
import ai.hans.standard.plugins.PythonPluginRuntimeDependencyPreparer
import ai.hans.standard.plugins.install.PluginInstallJournal
import ai.hans.standard.plugins.PythonRuntimePluginEntrypointProber
import ai.hans.standard.plugins.StandardAndroidRuntimeProbeRegistrations
import ai.hans.standard.plugins.runtime.AndroidDynamicToolEntrypointRegistry
import ai.hans.standard.plugins.runtime.HansDeclarativeHookRegistry
import ai.hans.standard.plugins.runtime.PluginSurfaceActivationStore
import ai.hans.standard.plugins.runtime.PluginSurfaceEvidenceStager
import ai.hans.standard.plugins.runtime.PluginSurfaceInventoryProjector
import ai.hans.standard.plugins.runtime.PluginSurfaceManifestLoader
import ai.hans.standard.plugins.runtime.PluginSurfacePluginRuntimeDependencyPreparer
import ai.hans.standard.plugins.runtime.PluginSurfacePreflight
import ai.hans.standard.plugins.runtime.PublishedPluginSurfaceRegistry
import ai.hans.standard.plugins.runtime.RemoteMcpPassiveStatusSource
import ai.hans.standard.plugins.uninstall.CompositePluginRuntimeUninstallManager
import ai.hans.standard.plugins.uninstall.PluginUninstallJournal
import ai.hans.standard.plugins.uninstall.PluginUninstallTransactionCoordinator
import ai.hans.standard.profile.SetupAwareUserProfileMutationPolicy
import ai.hans.standard.profile.UserProfileSetupSequenceState
import ai.hans.standard.runtime.python.PythonDynamicCapabilityGateway
import ai.hans.standard.runtime.python.PythonDynamicToolExecutor
import ai.hans.standard.runtime.python.AndroidPythonRuntimeDescriptorBroker
import ai.hans.standard.runtime.python.AndroidPythonNativePackageCatalog
import ai.hans.standard.runtime.python.PythonEnvironmentImportSelfTester
import ai.hans.standard.runtime.python.PythonEnvironmentRuntimeSelfTester
import ai.hans.standard.runtime.python.PythonEnvironmentStore
import ai.hans.standard.runtime.python.PythonEnvironmentTarget
import ai.hans.standard.runtime.python.PythonPluginEntrypointRegistry
import ai.hans.standard.runtime.python.PrivateWorkspacePythonArchiveProvider
import ai.hans.standard.runtime.python.PythonWheelArchiveValidator
import ai.hans.standard.runtime.python.PythonRuntimeSnapshot
import ai.hans.standard.runtime.python.PythonRuntimeSupervisor
import ai.hans.standard.runtime.python.resolver.AndroidPythonResolverBundleMaterializer
import ai.hans.standard.runtime.python.resolver.FilePythonResolverHttpCache
import ai.hans.standard.runtime.python.resolver.IsolatedPythonResolverWorker
import ai.hans.standard.runtime.python.resolver.PyPiSimpleJsonCatalog
import ai.hans.standard.runtime.python.resolver.PythonResolverArchiveRegistry
import ai.hans.standard.runtime.python.resolver.PythonResolverEngine
import ai.hans.standard.runtime.python.resolver.PythonResolverHttpClient
import ai.hans.standard.runtime.python.resolver.ResolverAwarePythonEnvironmentArchiveProvider
import ai.hans.standard.runtime.python.resolver.SecurePyPiWheelDownloader
import ai.hans.standard.runtime.python.resolver.SignedNativeFirstPythonPackageCatalog
import ai.hans.standard.runtime.BundledSetupPluginContract
import ai.hans.standard.settings.SharedPreferencesHansSettingsStore
import ai.hans.standard.setup.HansSetupRuntime
import ai.hans.standard.setup.HansSetupStep
import ai.hans.standard.voice.android.HansDictationRuntime
import ai.hans.standard.voice.android.DictationRuntimeObserver
import ai.hans.standard.voice.realtime.AndroidLiveVoiceRuntime
import ai.hans.standard.voice.realtime.LiveVoiceCancellation
import ai.hans.standard.voice.realtime.LiveVoiceCaptureActivityObserver
import ai.hans.standard.voice.realtime.LiveVoicePhase
import ai.hans.standard.voice.realtime.LiveVoiceObserver
import ai.hans.standard.voice.realtime.LiveVoiceSnapshot
import ai.hans.standard.voice.realtime.LiveVoiceResponseReady
import ai.hans.standard.voice.feedback.ResponseReadyFeedbackController
import ai.hans.standard.voice.feedback.AndroidResponseReadyHaptics
import ai.hans.standard.workspace.PrivateWorkspaceStore
import ai.hans.standard.workspace.WorkspaceDynamicToolExecutor
import ai.hans.standard.work.OkHttpWorkHttpCallFactory
import ai.hans.standard.work.PrivateNetworkAuthorization
import ai.hans.standard.work.WorkUtilityDynamicToolExecutor
import ai.hans.standard.work.remote.RemoteWorkerConfiguration
import ai.hans.standard.work.remote.RemoteWorkerProductionRuntime
import ai.hans.standard.work.remote.RemoteWorkerEditableConfigurationState
import ai.hans.standard.work.remote.RemoteWorkerRuntimeProjection
import ai.hans.standard.work.remote.RemoteWorkerRuntimeUpdateResult
import ai.hans.standard.work.remote.toRemoteWorkerPublicationDisposition
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

open class HansApplication : Application() {
    private var productRuntimeInitialized = false

    @Volatile
    var backupRecoveryRequired: Boolean = false
        private set

    // Session events publish immutable catalogue snapshots. Backup never takes the host's monitor
    // while holding maintenance, nor starts an account just to observe installed capabilities.
    private val latestBackupPluginSnapshot = AtomicReference<PluginDomainSnapshot?>(null)

    // Backup observation opens only these app-private stores, never a Codex session or scheduler.
    private val automationStorage by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BackupMaintainedAutomationStorage(
            PersistentAutomationStorage(SQLiteAutomationSnapshotPersistence(this)),
            HansBackupProcessState.maintenance,
        )
    }
    private val backupGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidHansBackupStateGateway(
            context = this,
            settingsStore = SharedPreferencesHansSettingsStore(this),
            automationStorage = automationStorage,
            pluginSnapshotProvider = { latestBackupPluginSnapshot.get() },
        )
    }
    val backupCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HansBackupCoordinator(backupGateway)
    }

    /** Inert in the shipping app; a debug source-set may bootstrap an isolated test owner. */
    protected open fun bootstrapIsolatedAutomationFixture(): Boolean = false

    /** Shared by the launcher and voice services; observing never starts the Codex runtime. */
    val internetConnectivity by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ai.hans.standard.network.AndroidInternetConnectivityMonitor(this)
    }

    /** One process-owned output router shared by TTS and Live; never starts a microphone. */
    val speechAudioRoutes by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ai.hans.standard.voice.audio.AndroidSpeechAudioRouteController(this)
    }

    private val responseReadyFeedbackDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        ResponseReadyFeedbackController(
            AndroidResponseReadyHaptics(this),
            java.util.concurrent.Executor { command ->
                check(handler.post(command)) { "haptic_dispatch_unavailable" }
            },
            text = AndroidHansTextResolver(this),
        )
    }
    private var responseReadySessionObserver: CodexClientObserver? = null
    private var responseReadyLiveSubscription: LiveVoiceCancellation? = null

    private val backgroundExecutor: ExecutorService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "hans-background-runtime").apply { isDaemon = true }
        }
    }

    /** Dependency resolution never blocks the App Server frame/controller monitor. */
    private val pluginPreparationExecutor: ExecutorService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hans-plugin-preparation").apply { isDaemon = true }
        }
    }

    /** Immutable app-private workspaces shared by Codex and local language runtimes. */
    val workspaceStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PrivateWorkspaceStore(
            requestedRoot = java.io.File(noBackupFilesDir, "hans-workspaces"),
            privateBoundary = noBackupFilesDir,
        )
    }

    /** Large and binary results cross process boundaries through opaque read-only handles. */
    val artifactStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AtomicArtifactStore(
            requestedRoot = java.io.File(noBackupFilesDir, "hans-artifacts"),
            privateBoundary = noBackupFilesDir,
        )
    }

    private val workspaceDynamicTools by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        WorkspaceDynamicToolExecutor(
            workspaceStore = workspaceStore,
            artifactStore = artifactStore,
            backgroundExecutor = backgroundExecutor,
        )
    }

    private val fileDynamicTools by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ai.hans.standard.files.FileDynamicToolExecutor(
            files = ai.hans.standard.files.AndroidSharedFiles.store(this),
            artifacts = artifactStore,
            executor = backgroundExecutor,
            downloads = ai.hans.standard.files.AndroidSharedFiles::downloads,
            mimeType = ai.hans.standard.files.AndroidSharedFiles::mime,
            links = ai.hans.standard.files.AndroidFileLinks(this)::issue,
        )
    }

    private val browserDynamicTools by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BrowserDynamicToolExecutor(
            artifactStore = artifactStore,
            httpCallFactory = IsolatedWorkBrowserHttpCallFactory(),
            backgroundExecutor = backgroundExecutor,
        )
    }

    private val documentDynamicTools by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        DocumentDynamicToolExecutor(
            adapter = DocumentArtifactAdapter(artifactStore),
            backgroundExecutor = backgroundExecutor,
        )
    }

    /**
     * Optional authenticated compute remains inert on construction and after process restart.
     * An enabled saved request becomes effective only through [activateRemoteWorkerExplicitly].
     */
    private val remoteWorkerRuntimeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteWorkerProductionRuntime.create(
            context = this,
            workspaceStore = workspaceStore,
            artifactStore = artifactStore,
            activationExecutor = backgroundExecutor,
            onPublicationChanged = ::publishRemoteWorkerToolContract,
        )
    }

    private val remoteWorkerRuntime: RemoteWorkerProductionRuntime
        get() = remoteWorkerRuntimeDelegate.value

    /** App-private, network-free and secret-free Settings projection. */
    internal fun passiveRemoteWorkerRuntimeState(): RemoteWorkerRuntimeProjection =
        remoteWorkerRuntime.passiveState()

    /** App-private, network-free form values; no Android-Keystore alias is exposed. */
    internal fun passiveRemoteWorkerEditableConfiguration(): RemoteWorkerEditableConfigurationState =
        remoteWorkerRuntime.passiveEditableConfiguration()

    /** Saving an enabled request never performs the activation probe. */
    internal fun saveRemoteWorkerConfiguration(
        expectedRevision: Long,
        replacement: RemoteWorkerConfiguration,
    ): RemoteWorkerRuntimeUpdateResult = remoteWorkerRuntime.save(expectedRevision, replacement)

    /** Explicit user action only; the authenticated probe and App Server apply run off-main. */
    internal fun activateRemoteWorkerExplicitly(
        expectedRevision: Long,
        completion: (RemoteWorkerRuntimeUpdateResult) -> Unit,
    ): Boolean = remoteWorkerRuntime.activateExplicitly(expectedRevision, completion)

    /**
     * Typed, bounded replacements for the common shell/file/network operations used by skills.
     * Public HTTP is always available through the app's normal INTERNET permission. Access to a
     * private/LAN endpoint is accepted only for a correlated Codex call under the explicit Hans
     * full-access policy; DNS and redirect targets are revalidated by the transport itself.
     */
    private val workUtilityDynamicTools by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val actionPolicy =
            ai.hans.standard.phone.consent.HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS
        WorkUtilityDynamicToolExecutor(
            workspaceStore = workspaceStore,
            artifactStore = artifactStore,
            httpCallFactory = OkHttpWorkHttpCallFactory(),
            privateNetworkAuthorization = PrivateNetworkAuthorization {
                    threadId,
                    turnId,
                    callId,
                    _,
                ->
                actionPolicy ==
                    ai.hans.standard.phone.consent.HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS &&
                    threadId.isNotBlank() && turnId.isNotBlank() && callId.isNotBlank()
            },
            backgroundExecutor = backgroundExecutor,
        )
    }

    /**
     * Root-free Git over JGit. Repositories never leave noBackupFilesDir, HTTPS URLs cannot carry
     * credentials, and SSH remains unadvertised until a separately verified Android SSH provider
     * is installed. Public HTTPS is available now; private-provider authentication plugs into the
     * opaque broker contract without exposing secrets to Codex.
     */
    private val gitDynamicTools by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val transport = GitTransportReadiness(https = true, ssh = false)
        GitDynamicToolExecutor(
            service = JGitRepositoryService(
                store = AppPrivateGitRepositoryStore(
                    requestedRoot = java.io.File(noBackupFilesDir, "hans-git-repositories"),
                    privateBoundary = noBackupFilesDir,
                ),
                credentialBroker = GitCredentialBroker { request ->
                    if (request.scheme == GitRemoteScheme.HTTPS) {
                        GitCredentialSession.PUBLIC
                    } else {
                        null
                    }
                },
                transportReadinessProbe = GitTransportReadinessProbe { transport },
            ),
            readinessProbe = GitDynamicReadinessProbe {
                GitDynamicReadiness(
                    localRepository = true,
                    commitIdentity = true,
                    transport = transport,
                )
            },
            identityProvider = { GitIdentity("Hans on Android", "hans@android.invalid") },
            backgroundExecutor = backgroundExecutor,
        )
    }
    val publicPhoneConfirmationRouter: SwappablePublicPhoneConfirmationProvider by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        SwappablePublicPhoneConfirmationProvider(
            persistentAndroidConsentStore,
            actionPolicy = ai.hans.standard.phone.consent.HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
        )
    }
    val automationApprovalRouter = SwappableAutomationToolApprovalProvider(
        actionPolicy = ai.hans.standard.phone.consent.HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
    )
    val persistentAndroidConsentStore: PersistentAndroidConsentStore by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        AtomicPersistentAndroidConsentStore(
            java.io.File(
                noBackupFilesDir,
                AtomicPersistentAndroidConsentStore.FILE_NAME,
            ),
        )
    }
    val setupRuntime: HansSetupRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HansSetupRuntime(
            context = this,
            backgroundExecutor = backgroundExecutor,
            persistentAndroidConsentStore = persistentAndroidConsentStore,
            actionPolicy = ai.hans.standard.phone.consent.HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
        )
    }

    private val publicPhoneIntegration: PublicPhoneToolIntegration by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        PublicPhoneToolIntegrationFactory.create(
            context = this,
            backgroundExecutor = backgroundExecutor,
            confirmations = publicPhoneConfirmationRouter,
        )
    }

    /**
     * Python can call only explicitly installed Hans dynamic tools. Those tools retain their own
     * live Android capability probes, permission gates, confirmation policy and postconditions.
     * Constructing this route table does not bind or start the Python process.
     */
    private val pythonCapabilityGateway by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PythonDynamicCapabilityGateway(
            listOf(
                publicPhoneIntegration.executor,
                workspaceDynamicTools,
                fileDynamicTools,
                workUtilityDynamicTools,
                gitDynamicTools,
                browserDynamicTools,
                documentDynamicTools,
            ),
        )
    }

    /**
     * Immutable plugin environments live beside, but never inside, the executable runtime.
     * The self-test callback is intentionally lazy: constructing this store must not bind the
     * isolated worker, while a real prepare transaction must prove its imports in that worker.
     */
    internal val pythonEnvironmentStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PythonEnvironmentStore(
            rootDirectory = java.io.File(noBackupFilesDir, "python/environments"),
            wheelValidator = PythonWheelArchiveValidator(
                deviceAndroidApi = android.os.Build.VERSION.SDK_INT,
                supportedAndroidAbis = android.os.Build.SUPPORTED_ABIS.toSet(),
            ),
            importSelfTester = PythonEnvironmentImportSelfTester { prepared ->
                PythonEnvironmentRuntimeSelfTester(pythonRuntime).test(prepared)
            },
            nativeCatalog = pythonNativePackageCatalog,
        )
    }

    /** Parsed from the signed APK and verified against nativeLibraryDir before use. */
    private val pythonNativePackageCatalog by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidPythonNativePackageCatalog.load(this)
    }

    private val pythonResolverBundle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidPythonResolverBundleMaterializer(this).materialize()
    }

    /** Leased resolver archives are visible to CPython only for the matching invocation. */
    private val pythonResolverArchives by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val bundle = pythonResolverBundle
        PythonResolverArchiveRegistry(
            baseResolverArchive = bundle.file,
            rootDirectory = java.io.File(noBackupFilesDir, "python/resolver/invocations"),
            expectedBaseSha256 = bundle.sha256,
        )
    }

    private val pythonArchiveProvider by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ResolverAwarePythonEnvironmentArchiveProvider(
            resolver = pythonResolverArchives,
            environments = pythonEnvironmentStore,
        )
    }

    private val pythonRuntimeDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PythonRuntimeSupervisor(
            context = this,
            capabilityGateway = pythonCapabilityGateway,
            callbackExecutor = backgroundExecutor,
            descriptorBroker = AndroidPythonRuntimeDescriptorBroker(
                context = this,
                environmentArchiveProvider = pythonArchiveProvider,
                workspaceArchiveProvider = PrivateWorkspacePythonArchiveProvider(
                    store = workspaceStore,
                    requestedArchiveRoot = java.io.File(
                        noBackupFilesDir,
                        "python/workspaces",
                    ),
                    privateBoundary = noBackupFilesDir,
                ),
            ),
        )
    }
    private val passivePythonRuntime = PassiveInitializedValue(pythonRuntimeDelegate)
    private val pythonRuntime: PythonRuntimeSupervisor
        get() = pythonRuntimeDelegate.value

    /** Workbench visibility must never construct, bind or start the isolated Python worker. */
    internal fun passiveInitializedPythonRuntimeSnapshot(): PythonRuntimeSnapshot? =
        passivePythonRuntime.snapshot(PythonRuntimeSupervisor::snapshot)

    /**
     * Passive, owner-backed execution inventory for plugin compatibility and activation.
     * Reading it never starts Codex, Code Mode or the isolated Python worker.
     */
    internal val pluginRuntimeProbeRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val localAbi = PluginRuntimeAbi.ANDROID_ARM64_V8A
        PluginRuntimeProbeRegistry(
            StandardAndroidRuntimeProbeRegistrations.create(
                localAbi = localAbi,
                codeMode = PluginRuntimeEvidenceSource {
                    AndroidRuntimeEvidenceProjection.codeMode(
                        runtimePhase = passiveInitializedSessionHostSnapshot()?.runtimePhase,
                        packagedVersion = BuildConfig.CODE_MODE_HOST_VERSION,
                        abi = localAbi,
                    )
                },
                python = PluginRuntimeEvidenceSource {
                    AndroidRuntimeEvidenceProjection.python(
                        snapshot = passiveInitializedPythonRuntimeSnapshot(),
                        packagedVersion = BuildConfig.PYTHON_RUNTIME_VERSION,
                        abi = localAbi,
                    )
                },
                git = PluginRuntimeEvidenceSource {
                    AndroidRuntimeEvidenceProjection.ready("7.7.1", localAbi)
                },
                http = PluginRuntimeEvidenceSource {
                    AndroidRuntimeEvidenceProjection.ready("1.0", localAbi)
                },
                androidCapabilities = PluginRuntimeEvidenceSource {
                    AndroidRuntimeEvidenceProjection.ready("1.0", localAbi)
                },
            ),
        )
    }

    private val pythonEnvironmentTarget by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val version = BuildConfig.PYTHON_RUNTIME_VERSION
        val parts = version.split('.')
        check(parts.size >= 2 && parts[0].all(Char::isDigit) && parts[1].all(Char::isDigit)) {
            "Packaged Python version cannot be converted to an interpreter tag"
        }
        PythonEnvironmentTarget(
            pythonVersion = version,
            interpreterTag = "cp${parts[0]}${parts[1]}",
            androidAbi = BuildConfig.PYTHON_RUNTIME_ABI,
            minimumAndroidApi = 31,
        )
    }

    private val pythonResolverHttp by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PythonResolverHttpClient(
            calls = OkHttpWorkHttpCallFactory(),
            cache = FilePythonResolverHttpCache(
                java.io.File(noBackupFilesDir, "python/resolver/http-cache"),
            ),
        )
    }

    private val pythonEnvironmentResolver by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PythonResolverEngine(
            catalog = SignedNativeFirstPythonPackageCatalog(
                nativeCatalog = pythonNativePackageCatalog,
                purePythonFallback = PyPiSimpleJsonCatalog(pythonResolverHttp),
            ),
            worker = IsolatedPythonResolverWorker(pythonRuntime, pythonResolverArchives),
        )
    }

    private val pythonPluginEntrypointRegistry by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PythonPluginEntrypointRegistry(
            java.io.File(
                noBackupFilesDir,
                "python/plugin-entrypoints/registry-v1.json",
            ),
        )
    }

    private val pythonPluginRuntimeDependencyPreparer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val wheelDownloader = SecurePyPiWheelDownloader(pythonResolverHttp)
        PythonPluginRuntimeDependencyPreparer(
            stagingRoot = java.io.File(cacheDir, "python-plugin-downloads"),
            target = pythonEnvironmentTarget,
            resolver = pythonEnvironmentResolver,
            downloader = CancellablePythonWheelDownloader { pin, destination, cancellation ->
                wheelDownloader.download(pin, destination, cancellation)
            },
            environments = pythonEnvironmentStore,
            entrypoints = PythonRuntimePluginEntrypointProber(pythonRuntime),
            entrypointRegistry = pythonPluginEntrypointRegistry,
        )
    }

    private val privatePluginSourceRoots by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        listOf(
            java.io.File(
                BundledSetupPluginContract.marketplaceRootForFilesDirectory(filesDir),
                "plugins",
            ),
            java.io.File(noBackupFilesDir, "codex/home/plugins"),
            java.io.File(noBackupFilesDir, "hans-plugin-sources"),
        ).map { directory ->
            check(directory.mkdirs() || directory.isDirectory) {
                "Cannot create private plugin source root"
            }
            check(!java.nio.file.Files.isSymbolicLink(directory.toPath())) {
                "Private plugin source root cannot be a symbolic link"
            }
            directory.canonicalFile
        }
    }

    private val pluginSurfaceManifestLoader by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PluginSurfaceManifestLoader(privatePluginSourceRoots)
    }

    /** Only signed-APK registrations may be added here; plugin metadata cannot name a class. */
    private val pluginSurfaceAndroidEntrypoints by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidDynamicToolEntrypointRegistry(emptyList())
    }

    /** Declarative hooks likewise remain empty until a signed Hans action owns the identifier. */
    private val pluginSurfaceHooks by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HansDeclarativeHookRegistry(emptyList())
    }

    private val remoteMcpPassiveStatuses by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteMcpPassiveStatusRegistry()
    }

    private val remoteMcpOAuthVault by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidKeystoreRemoteMcpOAuthVault(this)
    }

    /** Plugin input can reference only verifier ids compiled into the signed Hans APK. */
    private val remoteMcpSignedVerifiers
        get() = RemoteMcpBuiltInVerifiers.registry

    /** One durable policy document is shared by install preflight and every live MCP session. */
    private val remoteMcpToolPolicyStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteMcpToolPolicyStore(this, remoteMcpSignedVerifiers)
    }

    /** Live owner invalidates already-open sessions as soon as the durable revision changes. */
    private val remoteMcpToolPolicies by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteMcpToolPolicyOwner(remoteMcpToolPolicyStore, remoteMcpSignedVerifiers)
    }

    private val remoteMcpPolicyReviewCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteMcpPolicyReviewCoordinator(
            store = remoteMcpToolPolicyStore,
            reloadPolicyOwner = RemoteMcpPolicyOwnerReloadCallback {
                remoteMcpToolPolicies.reloadFromStore()
            },
            closeExactSession = RemoteMcpExactSessionCloseCallback { identity ->
                remoteMcpSessionRegistry.closeSessionIfPresent(identity)
            },
            refreshContract = RemoteMcpContractRefreshCallback {
                refreshFinalizedPluginToolContract()
            },
        )
    }

    private val remoteMcpHttpCalls by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpRemoteMcpCallFactory()
    }

    private val remoteMcpSessionFactory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteMcpSessionFactory { definition ->
            RemoteMcpSession(
                definition = definition,
                http = remoteMcpHttpCalls,
                oauth = remoteMcpOAuthVault,
                toolPolicies = remoteMcpToolPolicies,
                passiveStatuses = remoteMcpPassiveStatuses,
            )
        }
    }

    private val remoteMcpSessionRegistryDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteMcpSessionRegistry(this, remoteMcpSessionFactory)
    }
    private val remoteMcpSessionRegistry: RemoteMcpSessionRegistry
        get() = remoteMcpSessionRegistryDelegate.value

    private val remoteMcpCatalogs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteMcpDiscoveryCatalogStore(this)
    }

    private val remoteMcpRequirements by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PluginSurfaceRemoteMcpRequirementSource(pluginSurfaceManifestLoader)
    }

    /** Registrations can only be added by a future signed Hans build, never by plugin metadata. */
    private val remoteMcpOAuthClientOptions by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SignedRemoteMcpOAuthClientOptionsProvider()
    }

    private val pluginSurfaceInventory by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PluginSurfaceInventoryProjector(
            androidTools = pluginSurfaceAndroidEntrypoints,
            hooks = pluginSurfaceHooks,
            remoteMcp = RemoteMcpPassiveStatusSource {
                remoteMcpPassiveStatuses.snapshot().map { it.publicProjection() }
            },
        )
    }

    private val pluginSurfacePreflight by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PluginSurfacePreflight(
            manifests = pluginSurfaceManifestLoader,
            androidTools = pluginSurfaceAndroidEntrypoints,
            hooks = pluginSurfaceHooks,
            inventory = pluginSurfaceInventory,
        )
    }

    private val pluginSurfaceStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PluginSurfaceActivationStore(
            java.io.File(noBackupFilesDir, "plugins/surfaces-v1"),
        )
    }

    private val pluginSurfaceEvidenceStager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PluginSurfaceEvidenceStager(pluginSurfacePreflight, pluginSurfaceStore)
    }

    private val pluginSurfaceDependencyPreparer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PluginSurfacePluginRuntimeDependencyPreparer(pluginSurfacePreflight, pluginSurfaceStore)
    }

    private val publishedPluginSurfaces by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PublishedPluginSurfaceRegistry(pluginSurfacePreflight, pluginSurfaceStore)
    }

    private val remoteMcpDependencyPreparer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteMcpPluginRuntimeDependencyPreparer(
            requirements = remoteMcpRequirements,
            oauth = remoteMcpOAuthVault,
            activations = remoteMcpSessionRegistry,
            catalogs = remoteMcpCatalogs,
            preflight = SessionRemoteMcpPreflightDiscoverer(remoteMcpSessionFactory),
            toolPolicies = remoteMcpToolPolicyStore,
        )
    }

    /**
     * Blocking exact re-resolution for the launcher OAuth button. The caller must run off-main.
     * Nothing is installed or published here; source changes are caught again by explicit retry.
     */
    internal fun resolveRemoteMcpOAuthConnectionSpec(
        pluginId: String,
        serverId: String,
    ): RemoteMcpOAuthConnectionSpec? {
        val initial = sessionHost.resolveRemoteMcpOAuthTarget(pluginId, serverId) ?: return null
        val source = runCatching { java.io.File(initial.localSourcePath).canonicalFile }
            .getOrNull() ?: return null
        val current = sessionHost.resolveRemoteMcpOAuthTarget(pluginId, serverId) ?: return null
        if (
            current.handle != initial.handle ||
            runCatching { java.io.File(current.localSourcePath).canonicalFile }.getOrNull() != source
        ) {
            return null
        }
        val requirement = runCatching {
            remoteMcpRequirements.load(pluginId, source).singleOrNull { it.id == serverId }
        }.getOrNull() ?: return null
        return runCatching {
            RemoteMcpOAuthConnectionSpec.exact(
                request = current.request,
                requirement = requirement,
                clientOptions = remoteMcpOAuthClientOptions.options(pluginId, serverId),
            )
        }.getOrNull()
    }

    /**
     * Blocking one-shot policy approval. Launcher must call this off-main. It re-resolves the
     * current source and manifests, performs fresh isolated discovery, then exact-CASes policy.
     * Success only reveals a separate Retry action; installation is never resumed here.
     */
    internal fun approveRemoteMcpPolicyReview(
        submission: PluginRemoteMcpPolicyReviewSubmission,
    ): PluginRemoteMcpPolicyReviewResult {
        val mainLooper = android.os.Looper.getMainLooper()
        check(mainLooper == null || android.os.Looper.myLooper() != mainLooper) {
            "Remote MCP policy review must run off-main"
        }
        val target = sessionHost.resolveRemoteMcpPolicyReviewTarget(
            submission.pluginId,
            submission.serverId,
            submission.review,
        ) ?: return PluginRemoteMcpPolicyReviewResult.STALE_REVIEW
        val source = runCatching { java.io.File(target.localSourcePath).canonicalFile }
            .getOrNull() ?: return PluginRemoteMcpPolicyReviewResult.STALE_REVIEW
        val sourceHasher = BoundedPluginSourceIdentityHasher()
        fun currentSourceDigest(): String? = runCatching {
            sourceHasher.digest(source, PluginSourceHashCancellation.NONE)
        }.getOrNull()
        if (currentSourceDigest() != target.sourceSha256) {
            return PluginRemoteMcpPolicyReviewResult.STALE_REVIEW
        }
        val runtimeManifest = when (val loaded =
            PluginRuntimeManifestLoader(privatePluginSourceRoots).load(target.runtimeRecord)
        ) {
            is PluginRuntimeManifestResult.Declared -> loaded
            PluginRuntimeManifestResult.NotDeclared,
            is PluginRuntimeManifestResult.Rejected,
            -> return PluginRemoteMcpPolicyReviewResult.STALE_REVIEW
        }
        if (runCatching { runtimeManifest.sourceRoot.canonicalFile }.getOrNull() != source ||
            runtimeManifest.requirements.pluginId != submission.pluginId
        ) {
            return PluginRemoteMcpPolicyReviewResult.STALE_REVIEW
        }
        val requirement = runCatching {
            remoteMcpRequirements.load(submission.pluginId, source)
                .singleOrNull { it.id == submission.serverId }
        }.getOrNull() ?: return PluginRemoteMcpPolicyReviewResult.STALE_REVIEW
        val identity = RemoteMcpActivationIdentity(
            pluginId = submission.pluginId,
            serverId = requirement.id,
            configurationDigest = RemoteMcpConfigurationIdentity.digest(requirement),
        )
        if (identity != target.request.activationIdentity) {
            return PluginRemoteMcpPolicyReviewResult.STALE_REVIEW
        }
        val definition = runCatching { RemoteMcpActivationDefinition(identity, requirement) }
            .getOrNull() ?: return PluginRemoteMcpPolicyReviewResult.STALE_REVIEW
        val discovery = runCatching {
            SessionRemoteMcpPreflightDiscoverer(remoteMcpSessionFactory).discover(
                definition,
                ai.hans.standard.plugins.MutablePluginRuntimePreparationCancellation(),
            )
        }.getOrElse { return PluginRemoteMcpPolicyReviewResult.UNAVAILABLE }
        val catalog = runCatching {
            RemoteMcpPolicyReviewCatalog.fromDiscovery(
                activationIdentity = identity,
                allowedToolNames = requirement.allowedTools,
                discovery = discovery,
            )
        }.getOrElse { return PluginRemoteMcpPolicyReviewResult.STALE_REVIEW }
        if (catalog.request(target.request.policyStoreRevision) != target.request ||
            currentSourceDigest() != target.sourceSha256
        ) {
            return PluginRemoteMcpPolicyReviewResult.STALE_REVIEW
        }
        val stillCurrent = sessionHost.resolveRemoteMcpPolicyReviewTarget(
            submission.pluginId,
            submission.serverId,
            submission.review,
        )
        if (stillCurrent != target || currentSourceDigest() != target.sourceSha256) {
            return PluginRemoteMcpPolicyReviewResult.STALE_REVIEW
        }
        val decisions = runCatching {
            submission.decisions.map { decision ->
                RemoteMcpPolicyReviewDecision(
                    toolName = decision.toolName,
                    toolMetadataDigest = decision.toolMetadataDigest,
                    effect = when (decision.effect) {
                        PluginRemoteMcpPolicyEffect.READ_ONLY -> RemoteMcpToolEffect.READ_ONLY
                        PluginRemoteMcpPolicyEffect.MUTATING -> RemoteMcpToolEffect.MUTATING
                    },
                    signedVerifierId = if (
                        decision.effect == PluginRemoteMcpPolicyEffect.MUTATING
                    ) {
                        RemoteMcpBuiltInVerifiers.SERVER_ACKNOWLEDGED_V1
                    } else {
                        null
                    },
                )
            }
        }.getOrElse { return PluginRemoteMcpPolicyReviewResult.INVALID_DECISIONS }
        fun reviewFailureResult(failure: Throwable): PluginRemoteMcpPolicyReviewResult {
            val code = (failure as? ai.hans.standard.mcp.RemoteMcpFailure)?.code
            return when (code) {
                "mcp_policy_review_decision_incomplete",
                "mcp_tool_policy_verifier_untrusted",
                -> PluginRemoteMcpPolicyReviewResult.INVALID_DECISIONS
                "mcp_policy_review_identity_changed",
                "mcp_policy_review_catalog_changed",
                "mcp_tool_policy_revision_stale",
                "mcp_policy_review_commit_not_recoverable",
                -> PluginRemoteMcpPolicyReviewResult.STALE_REVIEW
                else -> PluginRemoteMcpPolicyReviewResult.UNAVAILABLE
            }
        }
        val approved = runCatching {
            remoteMcpPolicyReviewCoordinator.approve(target.request, catalog, decisions)
        }.getOrElse { failure ->
            val code = (failure as? ai.hans.standard.mcp.RemoteMcpFailure)?.code
            if (code != "mcp_policy_review_runtime_refresh_failed" &&
                code != "mcp_tool_policy_revision_stale"
            ) {
                return reviewFailureResult(failure)
            }
            // The durable CAS may have succeeded before a process-local refresh callback failed.
            // Reconciliation proves the exact submitted policy already exists, performs no write,
            // and retries only the idempotent owner/session/contract refresh sequence.
            runCatching {
                remoteMcpPolicyReviewCoordinator.reconcileCommittedApproval(
                    target.request,
                    catalog,
                    decisions,
                )
            }.getOrElse { recoveryFailure ->
                return reviewFailureResult(recoveryFailure)
            }
        }
        if (approved.revision <= target.request.policyStoreRevision) {
            return PluginRemoteMcpPolicyReviewResult.UNAVAILABLE
        }
        return if (sessionHost.markRemoteMcpPolicyReviewed(target)) {
            PluginRemoteMcpPolicyReviewResult.APPROVED_RETRY_REQUIRED
        } else {
            PluginRemoteMcpPolicyReviewResult.STALE_REVIEW
        }
    }

    private val compositePluginDependencyPreparer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val recovery = CompositePluginRuntimeDependencyRecoveryProvider(
            python = pythonPluginRuntimeDependencyPreparer,
            surface = pluginSurfaceDependencyPreparer,
            remoteMcp = remoteMcpDependencyPreparer,
        )
        CompositePluginRuntimeDependencyPreparer(
            python = pythonPluginRuntimeDependencyPreparer,
            surface = pluginSurfaceDependencyPreparer,
            remoteMcp = remoteMcpDependencyPreparer,
            recovery = recovery,
        )
    }

    private val finalizedRemoteMcpTools by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        RemoteMcpFinalizedDynamicToolSource(
            catalogs = remoteMcpCatalogs,
            activations = remoteMcpSessionRegistry,
            backgroundExecutor = backgroundExecutor,
            currentPolicyRevision = remoteMcpToolPolicies::currentRevisionOrThrow,
        )
    }

    private fun finalizedPluginToolContributors(): List<DynamicToolContributor> = buildList {
        publishedPluginSurfaces.snapshots().forEach { surface ->
            surface.dynamicToolExecutors.forEach { add(DynamicToolContributor(it)) }
        }
        // A missing/corrupt policy store must fail Remote MCP closed without suppressing unrelated
        // local plugin surfaces or an independently configured remote worker.
        runCatching { finalizedRemoteMcpTools.snapshotWithPolicyRevision() }
            .getOrNull()
            ?.let { remoteMcp ->
                remoteMcp.executors.forEach {
                    add(DynamicToolContributor(it, revisionToken = remoteMcp.revisionToken))
                }
            }
        if (remoteWorkerRuntimeDelegate.isInitialized()) {
            remoteWorkerRuntime.activePublication()?.let { active ->
                add(
                    DynamicToolContributor(
                        executor = active.contribution.executor,
                        revisionToken = active.revisionToken,
                    ),
                )
            }
        }
    }

    /**
     * Exact publication receipt for RemoteWorkerRuntimeOwner. A missing host, queued migration or
     * stale/failed generation never becomes an effective remote route.
     */
    private fun publishRemoteWorkerToolContract() = passiveSessionHost.snapshot { host ->
        host.applyDynamicToolSnapshot(
            host.dynamicToolSnapshot(finalizedPluginToolContributors()),
        )
    }.toRemoteWorkerPublicationDisposition()

    private fun refreshFinalizedPluginToolContract() {
        val result = passiveSessionHost.snapshot { host ->
            val snapshot = host.dynamicToolSnapshot(finalizedPluginToolContributors())
            host.applyDynamicToolSnapshot(snapshot)
        } ?: return
        check(
            result is DynamicToolSnapshotApplyResult.Applied ||
                result is DynamicToolSnapshotApplyResult.Unchanged ||
                result is DynamicToolSnapshotApplyResult.Queued,
        ) { "Finalized plugin tool contract publication failed" }
    }

    private val pluginInstallTransactions by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PluginInstallTransactionCoordinator(
            compatibility = PluginRuntimeCompatibilityService(
                manifests = PluginRuntimeManifestLoader(privatePluginSourceRoots),
                runtimes = pluginRuntimeProbeRegistry,
            ),
            dependencies = compositePluginDependencyPreparer,
            availableCapabilities = { pythonCapabilityGateway.supportedCapabilities },
            runtimeRemoval = pythonPluginRuntimeDependencyPreparer,
            installJournal = PluginInstallJournal(this),
            onRuntimePublicationChanged = ::refreshFinalizedPluginToolContract,
        )
    }

    private val pluginUninstallTransactions by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PluginUninstallTransactionCoordinator(
            runtime = CompositePluginRuntimeUninstallManager(
                python = pythonPluginEntrypointRegistry,
                surfaces = pluginSurfaceStore,
                remoteCatalogs = remoteMcpCatalogs,
                remoteActivations = remoteMcpSessionRegistry,
            ),
            journal = PluginUninstallJournal(this),
            onRuntimePublicationChanged = ::refreshFinalizedPluginToolContract,
        )
    }

    /** The service remains stopped until Codex invokes a real Python execution tool. */
    private val pythonDynamicTools by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PythonDynamicToolExecutor(
            runtime = pythonRuntime,
            environmentSelectionProvider = pythonEnvironmentStore,
            pluginEntrypointResolver = pythonPluginEntrypointRegistry,
            capabilityProvider = { pythonCapabilityGateway.supportedCapabilities },
        )
    }

    private val automationGateway: AndroidAutomationCodexGateway by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        AndroidAutomationCodexGatewayFactory.create(
            context = this,
            existingThreadProvider = { sessionHost.automationThreadAdapter() },
            selectionSource = IndependentCodexSelectionSource {
                val settings = SharedPreferencesHansSettingsStore(this).read()
                IndependentCodexSelection(
                    model = settings.model,
                    effort = ReasoningEffort.of(settings.reasoningEffort),
                    serviceTier = settings.serviceTier,
                )
            },
            dynamicToolsSource = AutomationDynamicToolExecutorSource {
                sessionHost.backgroundDynamicToolExecutor()
            },
            developerInstructionsSource = AutomationDeveloperInstructionsSource {
                sessionHost.automationDeveloperInstructions()
            },
        )
    }

    val automationRuntime: AutomationRuntimeOwner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidAutomationRuntimeFactory.create(
            context = this,
            backgroundExecutor = backgroundExecutor,
            liveEnvironment = AndroidAutomationLiveEnvironmentSource(
                context = this,
                hostProvider = { sessionHost },
                additionalCapabilityIds = ::availablePublicPhoneCapabilityIds,
            ),
            codexGateway = automationGateway,
            storage = automationStorage,
            backupMaintenance = HansBackupProcessState.maintenance,
        )
    }

    private val automationDynamicTools by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        HansAutomationDynamicToolExecutor(
            runtime = automationRuntime,
            backgroundExecutor = backgroundExecutor,
            approvalProvider = automationApprovalRouter,
        )
    }

    private val mp01DisplayDynamicTools by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Mp01DisplayDynamicToolExecutor(
            controller = AndroidMp01DisplayRuntime.create(this),
            backgroundExecutor = backgroundExecutor,
        )
    }

    val notificationAnnouncementCenter: ValidatedNotificationAnnouncementCenter by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED,
    ) {
        ValidatedNotificationAnnouncementCenter(this)
    }

    internal val whatsAppAgentChannel by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ai.hans.standard.integration.AndroidWhatsAppAgentChannelCoordinator(
            this,
            ai.hans.standard.notifications.agentchannel.WhatsAppAgentChannel(
                ai.hans.standard.notifications.agentchannel.AtomicFileAgentChannelStorage(this),
            ),
        )
    }

    private val notificationEventsDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ai.hans.standard.notifications.hooks.AndroidNotificationEventCoordinator(this)
    }
    internal val notificationEvents get() = notificationEventsDelegate.value
    internal fun passiveNotificationEventStatus() =
        if (notificationEventsDelegate.isInitialized()) notificationEventsDelegate.value.status() else null

    /** Set only after the interactive host has already been constructed; reading never boots it. */
    private val initializedNotificationSuggestionController =
        AtomicReference<ValidatedNotificationSuggestionController?>(null)

    /**
     * A notification/accessibility/test process wakeup must not eagerly boot a
     * second Codex App Server session. The launcher, dictation service, or an
     * explicitly agent-backed component starts the one process-wide host on
     * first use, and every later caller receives that same instance.
     */
    private val sessionHostDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        check(HansBackupProcessState.maintenance.isRecoveryReady) { "backup_recovery_required" }
        AndroidCodexSessionHost(
            context = this,
            persistentAndroidConsentStore = persistentAndroidConsentStore,
            notificationAnnouncements = notificationAnnouncementCenter,
            additionalDynamicToolExecutors = listOf(
                publicPhoneIntegration.executor,
                automationDynamicTools,
                workspaceDynamicTools,
                fileDynamicTools,
                workUtilityDynamicTools,
                gitDynamicTools,
                pythonDynamicTools,
                browserDynamicTools,
                documentDynamicTools,
            ),
            interactiveOnlyDynamicToolExecutors = listOf(
                setupRuntime.dynamicTools,
                mp01DisplayDynamicTools,
            ),
            setupSnapshot = setupRuntime::snapshot,
            profileMutationPolicy = SetupAwareUserProfileMutationPolicy {
                val setup = setupRuntime.snapshot()
                UserProfileSetupSequenceState(
                    started = setup.started,
                    complete = setup.complete,
                    atPersonalProfileStep =
                        setup.currentStep == HansSetupStep.PERSONAL_PROFILE,
                )
            },
            pluginInstallTransactions = pluginInstallTransactions,
            pluginUninstallTransactions = pluginUninstallTransactions,
            pluginPreparationExecutor = pluginPreparationExecutor,
            pluginSurfaceEvidenceStager = pluginSurfaceEvidenceStager,
        ).also { host ->
            runCatching {
                host.applyDynamicToolSnapshot(
                    host.dynamicToolSnapshot(finalizedPluginToolContributors()),
                )
            }.onFailure { failure ->
                Log.e("HansPluginRuntime", "Finalized plugin routes remain unpublished", failure)
            }
            initializedNotificationSuggestionController.set(
                object : ValidatedNotificationSuggestionController {
                    override fun invalidatePending(supersessionKey: String): Boolean =
                        host.invalidatePendingNotificationSuggestion(supersessionKey)

                    override fun clearAll(): Boolean =
                        host.clearValidatedNotificationSuggestions()

                    override fun quarantineOutput(): Boolean =
                        host.quarantineValidatedNotificationOutput()

                    override fun resumeOutputAfterAuthorityRestored(): Boolean =
                        host.resumeValidatedNotificationOutputAfterAuthorityRestored()
                },
            )
            installMigrationReadinessObservers(host)
            installResponseReadyObservers(host)
            whatsAppAgentChannel.attach(host)
            notificationEvents.attach(host)
            host.start()
        }
    }
    private val passiveSessionHost = PassiveInitializedValue(sessionHostDelegate)
    val sessionHost: AndroidCodexSessionHost
        get() = sessionHostDelegate.value

    /** Diagnostics may observe an existing host but can never cause its construction. */
    internal fun passiveInitializedSessionHostSnapshot(): CodexClientSnapshot? =
        passiveSessionHost.snapshot(AndroidCodexSessionHost::snapshot)

    private val migrationReadinessPublisher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MigrationSessionReadinessPublisher(
            file = java.io.File(
                noBackupFilesDir,
                MigrationSessionReadinessPublisher.FILE_NAME,
            ),
            packageSnapshot = { MigrationDiagnosticsAndroidEnvironment.packageSnapshot(this) },
        )
    }
    private var migrationSessionObserver: CodexClientObserver? = null
    private var migrationDictationObserver: DictationRuntimeObserver? = null
    private var migrationLiveVoiceCancellation: LiveVoiceCancellation? = null
    private var migrationAutomationRegistration: AutoCloseable? = null
    @Volatile
    private var migrationActiveAutomationCount =
        MigrationSessionReadinessProjection.MAX_ACTIVE_AUTOMATIONS

    private var dictationLifecycleRegistration: AutoCloseable? = null
    private var notificationContextRegistration: Closeable? = null
    private var notificationSuggestionRegistration: Closeable? = null
    private var notificationSuggestionControllerRegistration: Closeable? = null
    private var networkRecoveryMonitor: ValidatedNetworkRecoveryMonitor? = null

    override fun onCreate() {
        super.onCreate()
        if (getProcessName() != packageName) return
        if (bootstrapIsolatedAutomationFixture()) return
        val gateway = runCatching { backupGateway }.getOrElse {
            recordBackupRecoveryOutcome(false)
            return
        }
        val recovery = startRuntimeAfterBackupRecovery(gateway, ::startProductRuntimeAfterBackupRecovery)
        recordBackupRecoveryOutcome(recovery.isSuccess)
    }

    /** Explicit UI retry; it does not activate Codex or scheduled work on the backup worker. */
    internal fun retryBackupRecovery(): Boolean {
        val result = runCatching { backupGateway.recoverInterruptedImport() }
        recordBackupRecoveryOutcome(result.isSuccess)
        return result.isSuccess
    }

    private fun recordBackupRecoveryOutcome(success: Boolean) {
        backupRecoveryRequired = !success
        if (!success) {
            HansBackupProcessState.maintenance.requireRecovery()
            Log.e("HansBackup", "Backup recovery requires attention; scheduled work remains blocked")
        }
    }

    internal fun startProductRuntimeAfterBackupRecovery() {
        check(HansBackupProcessState.maintenance.isRecoveryReady) { "backup_recovery_required" }
        if (productRuntimeInitialized) {
            automationRuntime.scheduleChanged()
            return
        }
        productRuntimeInitialized = true
        HansAutomationRuntime.install(automationRuntime)
        automationRuntime.scheduleChanged()
        networkRecoveryMonitor = ValidatedNetworkRecoveryMonitor(
            context = this,
            onRestored = {
                when (automationRuntime.connectivityRestored()) {
                    ai.hans.standard.automations.AutomationAdapterResult.Accepted ->
                        AndroidAutomationNetworkRecoveryBackstop.cancel(this)
                    is ai.hans.standard.automations.AutomationAdapterResult.Rejected ->
                        AndroidAutomationNetworkRecoveryBackstop.schedule(this)
                }
            },
            onUnavailable = {
                AndroidAutomationNetworkRecoveryBackstop.schedule(this)
            },
        )
        // Accessibility actions fail closed until this passive, process-wide
        // voice lifecycle probe exists. It has no authority to alter recording.
        dictationLifecycleRegistration = AndroidDictationLifecycleRegistry.register(HansDictationRuntime)
        notificationContextRegistration =
            NotificationTriageIntegration.attachRelevanceContextProvider {
                currentNotificationRelevanceContext()
            }
        notificationSuggestionControllerRegistration =
            NotificationTriageIntegration.attachValidatedSuggestionController(
                ai.hans.standard.notifications.PassiveFirstValidatedSuggestionController(
                    initializedInteractiveController = initializedNotificationSuggestionController,
                    passiveInvalidate = notificationAnnouncementCenter::removePendingBySupersessionKey,
                    passiveClear = notificationAnnouncementCenter::clearAll,
                ),
            )
        notificationSuggestionRegistration = NotificationTriageIntegration.attachSuggestionSink(
            object : UserFacingNotificationSuggestionSink {
                // This readiness check must remain passive: only a validated suggestion may start
                // the lazy Codex host, never an arbitrary notification-listener wakeup.
                override fun isReady(): Boolean = true

                override fun deliver(
                    delivery: UserFacingNotificationDelivery,
                ): UserFacingDeliveryDisposition = if (
                    sessionHost.presentValidatedNotificationSuggestion(delivery)
                ) {
                    UserFacingDeliveryDisposition.ACCEPTED
                } else {
                    UserFacingDeliveryDisposition.RETRY
                }

                override fun activate(
                    delivery: UserFacingNotificationDelivery,
                ): UserFacingNotificationActivationDisposition =
                    sessionHost.activateValidatedNotificationSuggestion(delivery)

                override fun finalizeActivation(
                    delivery: UserFacingNotificationDelivery,
                    disposition: UserFacingNotificationActivationDisposition,
                ) {
                    sessionHost.finalizeValidatedNotificationSuggestion(delivery, disposition)
                }

                override fun revoke(delivery: UserFacingNotificationDelivery) {
                    sessionHost.revokeValidatedNotificationSuggestion(delivery)
                }
            },
        )
    }

    override fun onTerminate() {
        // A fixture (or a secondary process) must not instantiate lazy product services while
        // being torn down. Normal Android process death does not call this testing callback.
        if (!productRuntimeInitialized) {
            super.onTerminate()
            return
        }
        migrationSessionObserver?.let { observer ->
            passiveSessionHost.snapshot { host ->
                host.removeObserver(observer)
                Unit
            }
        }
        migrationSessionObserver = null
        responseReadySessionObserver?.let { observer ->
            passiveSessionHost.snapshot { host -> host.removeObserver(observer); Unit }
        }
        responseReadySessionObserver = null
        responseReadyLiveSubscription?.cancel()
        responseReadyLiveSubscription = null
        if (responseReadyFeedbackDelegate.isInitialized()) responseReadyFeedbackDelegate.value.close()
        migrationDictationObserver?.let(HansDictationRuntime::removeObserver)
        migrationDictationObserver = null
        migrationLiveVoiceCancellation?.cancel()
        migrationLiveVoiceCancellation = null
        migrationAutomationRegistration?.close()
        migrationAutomationRegistration = null
        networkRecoveryMonitor?.close()
        networkRecoveryMonitor = null
        notificationSuggestionRegistration?.close()
        notificationSuggestionRegistration = null
        notificationSuggestionControllerRegistration?.close()
        notificationSuggestionControllerRegistration = null
        initializedNotificationSuggestionController.set(null)
        notificationContextRegistration?.close()
        notificationContextRegistration = null
        dictationLifecycleRegistration?.close()
        dictationLifecycleRegistration = null
        runCatching { automationGateway.close() }
        if (pythonRuntimeDelegate.isInitialized()) runCatching { pythonRuntime.close() }
        if (remoteMcpSessionRegistryDelegate.isInitialized()) {
            runCatching { remoteMcpSessionRegistry.close() }
        }
        pluginPreparationExecutor.shutdownNow()
        backgroundExecutor.shutdownNow()
        super.onTerminate()
    }

    private fun installResponseReadyObservers(host: AndroidCodexSessionHost) {
        check(responseReadySessionObserver == null)
        val feedback = responseReadyFeedbackDelegate.value
        val observer = CodexClientObserver { snapshot ->
            feedback.acceptCodex(
                snapshot,
                enabled = true,
                userFacing = true,
                liveVoiceOwnsOutput = AndroidLiveVoiceRuntime.isCaptureRequestedOrActive(),
            )
        }
        responseReadySessionObserver = observer
        host.addObserver(observer)
        responseReadyLiveSubscription = AndroidLiveVoiceRuntime.addObserver(object : LiveVoiceObserver {
            override fun onSnapshot(snapshot: LiveVoiceSnapshot) = feedback.onLiveSnapshot(snapshot)
            override fun onHansResponseReady(event: LiveVoiceResponseReady) =
                feedback.acceptLive(event, enabled = false)
        })
    }

    private fun installMigrationReadinessObservers(host: AndroidCodexSessionHost) {
        check(migrationSessionObserver == null)
        val sessionObserver = CodexClientObserver { snapshot ->
            latestBackupPluginSnapshot.set(snapshot.plugins)
            publishMigrationReadiness(host, snapshot)
        }
        migrationSessionObserver = sessionObserver
        host.addObserver(sessionObserver)

        val dictationObserver = DictationRuntimeObserver {
            host.snapshot()?.let { publishMigrationReadiness(host, it) }
        }
        migrationDictationObserver = dictationObserver
        HansDictationRuntime.addObserver(dictationObserver)

        migrationLiveVoiceCancellation = AndroidLiveVoiceRuntime.addCaptureActivityObserver(
            LiveVoiceCaptureActivityObserver {
                host.snapshot()?.let { snapshot -> publishMigrationReadiness(host, snapshot) }
            },
        )

        check(migrationAutomationRegistration == null)
        migrationAutomationRegistration = automationRuntime.addActivityObserver(
            AutomationRuntimeActivityObserver { activity ->
                migrationActiveAutomationCount = activity.activeAutomationCount
                host.snapshot()?.let { publishMigrationReadiness(host, it) }
            },
        )
    }

    private fun publishMigrationReadiness(
        host: AndroidCodexSessionHost,
        snapshot: CodexClientSnapshot,
    ) {
        val currentThread = snapshot.session.currentThreadId
        val activeAutomationCount = migrationActiveAutomationCount
        val accountPhase = when {
            !snapshot.migrationReadiness.accountReadComplete -> MigrationAccountPhase.UNKNOWN
            snapshot.session.account.phase == AccountPhase.SIGNED_IN ->
                MigrationAccountPhase.SIGNED_IN
            snapshot.session.account.phase == AccountPhase.SIGNED_OUT ->
                MigrationAccountPhase.SIGNED_OUT
            else -> MigrationAccountPhase.UNKNOWN
        }
        runCatching {
            migrationReadinessPublisher.publish(
                MigrationSessionReadinessProjection(
                    accountPhase = accountPhase,
                    accountReadComplete = snapshot.migrationReadiness.accountReadComplete,
                    runtimeReady = snapshot.runtimePhase == ClientRuntimePhase.READY,
                    threadCorrelationSha256 = currentThread?.let(MigrationDiagnosticsHashes::thread),
                    threadResumeConfirmed =
                        snapshot.migrationReadiness.threadResumeConfirmed && currentThread != null,
                    memoryModeEnabledAck =
                        snapshot.migrationReadiness.memoryModeEnabledAck && currentThread != null,
                    effectiveModel = snapshot.migrationReadiness.effectiveSelection?.model,
                    effectiveReasoningEffort =
                        snapshot.migrationReadiness.effectiveSelection?.effort?.wireValue,
                    speechCredentialAvailable = host.hasSpeechCredential(),
                    setupComplete = setupRuntime.snapshot().complete,
                    activeTurn = snapshot.migrationReadiness.activeTurn,
                    dictationActive = HansDictationRuntime.snapshot().recordingActive,
                    liveVoiceActive = AndroidLiveVoiceRuntime.isCaptureRequestedOrActive(),
                    activeAutomationCount = activeAutomationCount,
                ),
            )
        }
    }

    private fun availablePublicPhoneCapabilityIds(): Set<String> = runCatching {
        AndroidPublicPhonePlatform(
            context = this,
            notificationReplies = publicPhoneIntegration.notificationReplyRegistry,
        ).probeCapabilities()
            .filter { probe ->
                probe.state == PublicPhoneCapabilityState.AVAILABLE ||
                    probe.capability == "location.read" &&
                    probe.limitationCode == "coarse_location_only"
            }
            .mapTo(linkedSetOf()) { it.capability }
    }.getOrDefault(emptySet())

    private fun currentNotificationRelevanceContext(): NotificationRelevanceContext =
        NotificationRelevanceContext(
            userIsDictating = HansDictationRuntime.snapshot().recordingActive,
            liveVoiceIsActive = AndroidLiveVoiceRuntime.snapshot().phase !in setOf(
                LiveVoicePhase.IDLE,
                LiveVoicePhase.STOPPED,
                LiveVoicePhase.FAILED,
            ),
            screenIsInteractive = runCatching {
                getSystemService(PowerManager::class.java)?.isInteractive == true
            }.getOrDefault(false),
            quietModeIsActive = quietModeIsActive(),
            userLocale = java.util.Locale.getDefault().toLanguageTag()
                .takeIf(String::isNotBlank) ?: "und",
            timeZoneId = java.util.TimeZone.getDefault().id
                .takeIf(String::isNotBlank) ?: "UTC",
        )

    private fun quietModeIsActive(): Boolean = runCatching {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notifications = getSystemService(NotificationManager::class.java)
        audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            notifications?.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }.getOrDefault(true)
}
