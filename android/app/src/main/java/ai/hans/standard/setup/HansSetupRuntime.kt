package ai.hans.standard.setup

import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.localization.AndroidHansTextResolver

import ai.hans.standard.phone.keys.ActionKeyCommand
import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.core.app.NotificationCompat
import ai.hans.standard.R
import ai.hans.standard.phone.accessibility.android.HansAccessibilitySessions
import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.SemanticUiAction
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import ai.hans.standard.phone.capabilities.AndroidCapabilityEnvironment
import ai.hans.standard.phone.capabilities.AccessibilityGrantState
import ai.hans.standard.phone.capabilities.AndroidSpecialAccess
import ai.hans.standard.phone.keys.ActionKeyMappingPreferencesStore
import ai.hans.standard.phone.keys.ActionKeyTrigger
import ai.hans.standard.phone.keys.forTaskVoiceControls
import ai.hans.standard.phone.keys.AndroidMp01VendorActionRemediation
import ai.hans.standard.phone.keys.KeySemanticAction
import ai.hans.standard.phone.keys.Mp01VendorActionConflictKind
import ai.hans.standard.phone.publicapi.ActiveNotificationReplyRegistry
import ai.hans.standard.phone.notifications.NotificationSetupLiveTestReceiptTracker
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import ai.hans.standard.phone.consent.PersistentAndroidConsentStore
import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import ai.hans.standard.settings.SharedPreferencesHansSettingsStore
import ai.hans.standard.voice.tts.android.AndroidKeystoreSpeechCredentialStore
import ai.hans.standard.voice.tts.android.SpeechCredentialStatus
import ai.hans.standard.workspace.HansDesktopProject
import java.io.Closeable
import java.util.concurrent.Executor

interface HansSetupFreshProbe {
    /** Localized current controls, not evidence that the user has tested voice. */
    fun voiceUsageInstructions(): List<String> = emptyList()

    /** Only a freshly verified, prepared task directory; never remote-connection evidence. */
    fun desktopProject(): HansSetupDesktopProject? = null

    fun probe(step: HansSetupStep, operationNonce: String? = null): HansSetupProbeResult

    fun probeOptionalCapability(
        capability: HansSetupOptionalCapability,
        operationNonce: String? = null,
    ): HansSetupProbeResult = HansSetupProbeResult(
        verified = false,
        detailCode = "optional_capability_probe_unavailable",
        blocked = true,
    )

    fun hardwareHoldCompatible(): Boolean

    /**
     * Returns only an effective, persisted dictation mapping. A null result means that setup
     * must ask; it never means that the user refused a hardware key.
     */
    fun configuredInputChoice(): HansSetupInputChoice? = null

    /** Default false is deliberately not evidence. Only an effectively enabled gesture is adopted. */
    fun configuredCameraHoldEnabled(): Boolean = false

    fun postNotificationLiveTest(operationNonce: String): Boolean

    fun armLiveTest(step: HansSetupStep, operationNonce: String): Boolean = true

    fun accessibilityTestCopy(operationNonce: String): AccessibilitySetupTestCopy? = null

    fun recordAccessibilityTargetAction(operationNonce: String): Boolean = false

    fun recordAccessibilityPostcondition(operationNonce: String): Boolean = false
}

class AndroidHansSetupFreshProbe(
    context: Context,
    private val persistentAndroidConsentStore: PersistentAndroidConsentStore =
        PersistentAndroidConsentStore.NONE,
    private val actionPolicy: HansPhoneActionPolicy = HansPhoneActionPolicy.CONFIRM_ACTIONS,
) : HansSetupFreshProbe {
    private val appContext = context.applicationContext
    private val capabilities = AndroidCapabilityEnvironment(appContext)
    private val accessibilityReceipt = AccessibilitySetupReceiptTracker(appContext.packageName)
    private val speechCredentialStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidKeystoreSpeechCredentialStore(appContext)
    }

    override fun desktopProject(): HansSetupDesktopProject? {
        val path = HansDesktopProject.preparedPath(appContext.filesDir) ?: return null
        val text = AndroidHansTextResolver(appContext)
        return HansSetupDesktopProject(
            path = path,
            instructions = text.text(R.string.setup_desktop_project_instructions, path),
            permissions = text.text(R.string.setup_desktop_project_permissions),
        )
    }

    override fun probe(step: HansSetupStep, operationNonce: String?): HansSetupProbeResult = when (step) {
        HansSetupStep.HARDWARE_MAPPING -> result(
            ActionKeyMappingPreferencesStore(appContext).read().mappings.any {
                it.action == KeySemanticAction.DICTATION
            },
            "hardware_mapping_present",
            "hardware_mapping_missing",
        )
        HansSetupStep.CAMERA_HOLD_CHOICE -> result(
            SharedPreferencesHansSettingsStore(appContext).read().cameraHoldToTalkEnabled,
            "camera_hold_effective",
            "camera_hold_not_effective",
        )
        HansSetupStep.NOTIFICATION_ACCESS -> result(
            capabilities.hasSpecialAccess(AndroidSpecialAccess.NOTIFICATION_LISTENER),
            "notification_access_granted",
            "notification_access_missing",
        )
        HansSetupStep.NOTIFICATION_LIVE_TEST -> result(
            capabilities.hasSpecialAccess(AndroidSpecialAccess.NOTIFICATION_LISTENER) &&
                ActiveNotificationReplyRegistry.processWide.isAvailable() &&
                operationNonce != null &&
                NotificationSetupLiveTestReceiptTracker.processWide.consumeVerified(
                    operationNonce,
                ).also { verified ->
                    if (verified) {
                        appContext.getSystemService(NotificationManager::class.java)
                            ?.cancel(NotificationSetupLiveTestReceiptTracker.NOTIFICATION_ID)
                    }
                },
            "notification_ingress_receipt_observed",
            "notification_ingress_receipt_missing",
            transient = capabilities.hasSpecialAccess(AndroidSpecialAccess.NOTIFICATION_LISTENER) &&
                ActiveNotificationReplyRegistry.processWide.isAvailable(),
        )
        HansSetupStep.ACCESSIBILITY_ACCESS ->
            accessibilitySetupGrantProbe(capabilities.accessibilityGrantState())
        HansSetupStep.ACCESSIBILITY_LIVE_TEST -> probeAccessibilityReceipt(operationNonce)
        HansSetupStep.HOME_ROLE -> result(
            capabilities.hasSpecialAccess(AndroidSpecialAccess.HOME_ROLE),
            "home_role_held",
            "home_role_missing",
        )
        HansSetupStep.MICROPHONE_ACCESS -> result(
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
            "microphone_permission_granted",
            "microphone_permission_missing",
        )
        HansSetupStep.APP_NOTIFICATIONS_ACCESS -> result(
            Build.VERSION.SDK_INT < 33 ||
                appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
            "app_notification_permission_granted",
            "app_notification_permission_missing",
        )
        HansSetupStep.SPEECH_CREDENTIAL_ACCESS -> when (
            speechCredentialStore.credentialStatus()
        ) {
            SpeechCredentialStatus.AVAILABLE -> result(
                true,
                "speech_credential_available",
                "speech_credential_missing",
            )
            SpeechCredentialStatus.MISSING -> result(
                false,
                "speech_credential_available",
                "speech_credential_missing",
            )
            SpeechCredentialStatus.TEMPORARILY_UNAVAILABLE -> HansSetupProbeResult(
                verified = false,
                detailCode = "speech_credential_temporarily_unavailable",
                transient = true,
            )
        }
        else -> HansSetupProbeResult(false, "step_has_no_fresh_probe")
    }

    override fun probeOptionalCapability(
        capability: HansSetupOptionalCapability,
        operationNonce: String?,
    ): HansSetupProbeResult {
        fun granted(permission: String): Boolean =
            appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return when (capability) {
            HansSetupOptionalCapability.ALL_FILES -> result(
                runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false),
                "all_files_access_granted",
                "all_files_access_missing",
            )
            HansSetupOptionalCapability.EVERYDAY_ACCESS -> everydayAccessSetupProbe(
                actionPolicy,
                persistentAndroidConsentStore::hasEverydayBundle,
            )
            HansSetupOptionalCapability.NOTIFICATION_LINK_METADATA -> result(
                persistentAndroidConsentStore.contains(
                    PersistentAndroidConsentDescriptor.category(
                        PersistentAndroidConsentScope.NOTIFICATION_LINK_METADATA,
                    ),
                ),
                "notification_link_metadata_consent_active",
                "notification_link_metadata_consent_missing",
            )
            HansSetupOptionalCapability.CONTACTS -> result(
                granted(Manifest.permission.READ_CONTACTS),
                "contacts_permission_granted",
                "contacts_permission_missing",
            )
            HansSetupOptionalCapability.CALENDAR -> result(
                granted(Manifest.permission.READ_CALENDAR) &&
                    granted(Manifest.permission.WRITE_CALENDAR),
                "calendar_permissions_granted",
                "calendar_permissions_missing",
            )
            HansSetupOptionalCapability.LOCATION -> result(
                granted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
                    granted(Manifest.permission.ACCESS_FINE_LOCATION),
                "location_permission_granted",
                "location_permission_missing",
            )
            HansSetupOptionalCapability.PHOTOS_VIDEOS -> result(
                when {
                    Build.VERSION.SDK_INT >= 34 ->
                        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ||
                            granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                            granted(Manifest.permission.READ_MEDIA_VIDEO)
                    Build.VERSION.SDK_INT >= 33 ->
                        granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                            granted(Manifest.permission.READ_MEDIA_VIDEO)
                    else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
                },
                "photos_videos_permission_granted",
                "photos_videos_permission_missing",
            )
            HansSetupOptionalCapability.AUDIO_MEDIA -> result(
                if (Build.VERSION.SDK_INT >= 33) {
                    granted(Manifest.permission.READ_MEDIA_AUDIO)
                } else {
                    granted(Manifest.permission.READ_EXTERNAL_STORAGE)
                },
                "audio_media_permission_granted",
                "audio_media_permission_missing",
            )
            HansSetupOptionalCapability.EXACT_ALARMS -> result(
                runCatching {
                    appContext.getSystemService(AlarmManager::class.java)
                        ?.canScheduleExactAlarms() == true
                }.getOrDefault(false),
                "exact_alarm_access_granted",
                "exact_alarm_access_missing",
            )
            HansSetupOptionalCapability.QUICK_SETTINGS_TILE -> HansSetupProbeResult(
                verified = false,
                detailCode = if (Build.VERSION.SDK_INT >= 33) {
                    "quick_settings_tile_callback_receipt_required"
                } else {
                    "quick_settings_tile_manual_add_required"
                },
                blocked = Build.VERSION.SDK_INT < 33,
            )
        }
    }

    override fun hardwareHoldCompatible(): Boolean {
        val evidence = AndroidMp01VendorActionRemediation(appContext).probe()
        return !(evidence.trustedSystemPackage &&
            evidence.conflictKind == Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY)
    }

    override fun voiceUsageInstructions(): List<String> = listOf(
        appContext.getString(if (configuredInputChoice() != null) R.string.voice_setup_short_task_hardware
            else R.string.voice_setup_short_task_screen),
        appContext.getString(R.string.voice_setup_phone),
        appContext.getString(R.string.voice_setup_optional_practice),
    )

    override fun configuredInputChoice(): HansSetupInputChoice? {
        val triggers = ActionKeyMappingPreferencesStore(appContext).read().forTaskVoiceControls().mappings
            .asSequence()
            .filter { it.action == KeySemanticAction.DICTATION }
            .map { it.trigger }
            .distinct()
            .toList()
        if (triggers.size != 1) return null
        return when (triggers.single()) {
            ActionKeyTrigger.PRESS -> HansSetupInputChoice.HARDWARE_TOGGLE
            ActionKeyTrigger.HOLD_TO_TALK -> HansSetupInputChoice.HARDWARE_HOLD
        }
    }

    override fun configuredCameraHoldEnabled(): Boolean =
        SharedPreferencesHansSettingsStore(appContext).read().cameraHoldToTalkEnabled

    override fun postNotificationLiveTest(operationNonce: String): Boolean {
        requireSetupNonce(operationNonce)
        if (
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
        ) {
            return false
        }
        val receiptTracker = NotificationSetupLiveTestReceiptTracker.processWide
        if (!receiptTracker.arm(operationNonce)) return false
        val posted = runCatching {
            val manager = appContext.getSystemService(NotificationManager::class.java)
                ?: return@runCatching false
            manager.createNotificationChannel(
                NotificationChannel(
                    NotificationSetupLiveTestReceiptTracker.NOTIFICATION_CHANNEL_ID,
                    appContext.getString(R.string.integration_hans_setup_test_71bd55e),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            manager.notify(
                NotificationSetupLiveTestReceiptTracker.NOTIFICATION_ID,
                NotificationCompat.Builder(
                    appContext,
                    NotificationSetupLiveTestReceiptTracker.NOTIFICATION_CHANNEL_ID,
                )
                    .setSmallIcon(R.drawable.ic_hans)
                    .setContentTitle(appContext.getString(R.string.integration_hans_setup_test_71bd55e))
                    .setContentText(appContext.getString(R.string.integration_checking_notification_access_safely_80f47a9))
                    .addExtras(
                        Bundle().apply {
                            putString(
                                NotificationSetupLiveTestReceiptTracker.EXTRA_OPERATION_NONCE,
                                operationNonce,
                            )
                        },
                    )
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .build(),
            )
            true
        }.getOrDefault(false)
        if (!posted) receiptTracker.clear(operationNonce)
        return posted
    }

    override fun armLiveTest(step: HansSetupStep, operationNonce: String): Boolean {
        requireSetupNonce(operationNonce)
        if (step != HansSetupStep.ACCESSIBILITY_LIVE_TEST) return true
        val session = HansAccessibilitySessions.current()
        accessibilityReceipt.arm(
            nonce = operationNonce,
            sessionId = session?.sessionId,
            baseline = session?.currentSnapshot(),
            displayCopy = AccessibilitySetupTestCopy.capture(AndroidHansTextResolver(appContext)),
        )
        return true
    }

    override fun recordAccessibilityTargetAction(operationNonce: String): Boolean {
        requireSetupNonce(operationNonce)
        val session = HansAccessibilitySessions.current() ?: return false
        val snapshot = session.currentSnapshot() ?: return false
        return accessibilityReceipt.observeTargetAction(operationNonce, session.sessionId, snapshot)
    }

    override fun recordAccessibilityPostcondition(operationNonce: String): Boolean {
        requireSetupNonce(operationNonce)
        val session = HansAccessibilitySessions.current() ?: return false
        val snapshot = session.currentSnapshot() ?: return false
        return accessibilityReceipt.observePostcondition(operationNonce, session.sessionId, snapshot)
    }

    override fun accessibilityTestCopy(operationNonce: String): AccessibilitySetupTestCopy? =
        accessibilityReceipt.displayCopy(operationNonce)

    private fun probeAccessibilityReceipt(operationNonce: String?): HansSetupProbeResult {
        val grant = capabilities.accessibilityGrantState()
        val state = operationNonce?.let(accessibilityReceipt::state)
            ?: AccessibilitySetupReceiptState.NOT_ARMED
        val result = accessibilitySetupLiveProbe(
            grant = grant,
            serviceConnected = HansAccessibilitySessions.isServiceConnected(),
            sessionAvailable = HansAccessibilitySessions.current() != null,
            receiptState = state,
        )
        if (result.verified) {
            operationNonce?.let(accessibilityReceipt::clear)
        }
        return result
    }

    private fun result(
        verified: Boolean,
        verifiedCode: String,
        missingCode: String,
        transient: Boolean = false,
    ) = HansSetupProbeResult(
        verified = verified,
        detailCode = if (verified) verifiedCode else missingCode,
        transient = !verified && transient,
    )

}

/** Only Hans's extra everyday confirmation is satisfied by this explicitly selected policy. */
internal fun everydayAccessSetupProbe(
    actionPolicy: HansPhoneActionPolicy,
    hasEverydayBundle: () -> Boolean,
): HansSetupProbeResult = when (actionPolicy) {
    HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS -> HansSetupProbeResult(
        verified = true,
        detailCode = "everyday_access_full_access_policy",
    )
    HansPhoneActionPolicy.CONFIRM_ACTIONS -> {
        val granted = hasEverydayBundle()
        HansSetupProbeResult(
            verified = granted,
            detailCode = if (granted) {
                "everyday_access_bundle_active"
            } else {
                "everyday_access_bundle_missing"
            },
        )
    }
}

/** Describes Hans's trusted action policy, never whether Android has granted Accessibility. */
internal fun setupAccessibilityDescription(actionPolicy: HansPhoneActionPolicy, text: HansTextResolver): String = when (actionPolicy) {
    HansPhoneActionPolicy.CONFIRM_ACTIONS -> text.text(R.string.integration_accessibility_confirm)
    HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS -> text.text(R.string.integration_accessibility_full)
}

/** Setup records consent separately from live command/session readiness. */
internal fun accessibilitySetupGrantProbe(grant: AccessibilityGrantState): HansSetupProbeResult =
    when (grant) {
        AccessibilityGrantState.GRANTED ->
            HansSetupProbeResult(true, "accessibility_access_granted")
        AccessibilityGrantState.NOT_GRANTED ->
            HansSetupProbeResult(false, "accessibility_access_missing")
        AccessibilityGrantState.UNKNOWN -> HansSetupProbeResult(
            verified = false,
            detailCode = "accessibility_access_temporarily_unavailable",
            transient = true,
        )
    }

/** A durable permission alone can never prove a successful Accessibility live test. */
internal fun accessibilitySetupLiveProbe(
    grant: AccessibilityGrantState,
    serviceConnected: Boolean,
    sessionAvailable: Boolean,
    receiptState: AccessibilitySetupReceiptState,
): HansSetupProbeResult {
    val granted = grant == AccessibilityGrantState.GRANTED
    val ready = serviceConnected && sessionAvailable
    val verified = granted && ready && receiptState == AccessibilitySetupReceiptState.VERIFIED
    return HansSetupProbeResult(
        verified = verified,
        detailCode = if (verified) {
            "accessibility_nonce_target_action_verified"
        } else {
            "accessibility_nonce_target_action_unavailable"
        },
        transient = grant == AccessibilityGrantState.UNKNOWN ||
            (granted && (!ready || receiptState == AccessibilitySetupReceiptState.ARMED)),
    )
}

/** Copy captured with the receipt arm, never re-resolved between offer and click. */
data class AccessibilitySetupTestCopy(
    val targetDescription: String,
    val postconditionText: String,
) {
    init {
        require(targetDescription.isNotBlank() && targetDescription.length <= 200)
        require(postconditionText.isNotBlank() && postconditionText.length <= 200)
    }

    companion object {
        fun capture(text: HansTextResolver): AccessibilitySetupTestCopy = AccessibilitySetupTestCopy(
            text.text(R.string.integration_accessibility_test_target),
            text.text(R.string.integration_accessibility_test_postcondition),
        )
    }
}

internal enum class AccessibilitySetupReceiptState {
    NOT_ARMED,
    ARMED,
    VERIFIED,
}

/** Nonce-bound evidence chain: fresh Hans target -> trusted local tap callback -> fresh marker. */
internal class AccessibilitySetupReceiptTracker(
    private val ownPackageName: String,
) {
    private var arm: Arm? = null

    @Synchronized
    fun arm(
        nonce: String,
        sessionId: AccessibilitySessionId?,
        baseline: SemanticUiSnapshot?,
        displayCopy: AccessibilitySetupTestCopy,
    ) {
        requireSetupNonce(nonce)
        arm = Arm(
            nonce = nonce,
            sessionId = sessionId,
            baselineCorrelation = baseline?.correlation,
            baselineCapturedAtElapsedMillis = baseline?.capturedAtElapsedMillis,
            displayCopy = displayCopy,
        )
    }

    @Synchronized
    fun observeTargetAction(
        nonce: String,
        sessionId: AccessibilitySessionId,
        snapshot: SemanticUiSnapshot,
    ): Boolean {
        val current = arm?.takeIf { it.nonce == nonce } ?: return false
        if (!current.accepts(sessionId, snapshot) || current.verified) return false
        val exactTarget = snapshot.nodes.singleOrNull { node ->
            node.packageName?.value == ownPackageName &&
                node.contentDescription?.value == current.displayCopy.targetDescription &&
                node.visible && node.enabled && node.clickable &&
                SemanticUiAction.CLICK in node.actions
        } ?: return false
        arm = current.copy(actionCorrelation = exactTarget.handle.correlation)
        return true
    }

    @Synchronized
    fun observePostcondition(
        nonce: String,
        sessionId: AccessibilitySessionId,
        snapshot: SemanticUiSnapshot,
    ): Boolean {
        val current = arm?.takeIf { it.nonce == nonce } ?: return false
        val action = current.actionCorrelation ?: return false
        if (action.sessionId != sessionId || snapshot.correlation.sessionId != sessionId) return false
        if (snapshot.correlation.snapshotId.value <= action.snapshotId.value) return false
        val exactMarker = snapshot.nodes.any { node ->
            node.packageName?.value == ownPackageName &&
                (node.text?.value == current.displayCopy.postconditionText ||
                    node.contentDescription?.value == current.displayCopy.postconditionText) &&
                node.visible
        }
        if (!exactMarker) return false
        arm = current.copy(verified = true)
        return true
    }

    @Synchronized
    fun state(nonce: String): AccessibilitySetupReceiptState = when {
        arm?.nonce != nonce -> AccessibilitySetupReceiptState.NOT_ARMED
        arm?.verified == true -> AccessibilitySetupReceiptState.VERIFIED
        else -> AccessibilitySetupReceiptState.ARMED
    }

    @Synchronized
    fun clear(nonce: String) {
        if (arm?.nonce == nonce) arm = null
    }

    @Synchronized
    fun displayCopy(nonce: String): AccessibilitySetupTestCopy? =
        arm?.takeIf { it.nonce == nonce }?.displayCopy

    private data class Arm(
        val nonce: String,
        val sessionId: AccessibilitySessionId?,
        val baselineCorrelation: UiSnapshotCorrelation?,
        val baselineCapturedAtElapsedMillis: Long?,
        val displayCopy: AccessibilitySetupTestCopy,
        val actionCorrelation: UiSnapshotCorrelation? = null,
        val verified: Boolean = false,
    ) {
        fun accepts(
            observedSessionId: AccessibilitySessionId,
            snapshot: SemanticUiSnapshot,
        ): Boolean {
            if (sessionId != null && sessionId != observedSessionId) return false
            if (snapshot.correlation.sessionId != observedSessionId) return false
            val baseline = baselineCorrelation ?: return true
            if (baseline.sessionId != observedSessionId) return sessionId == null
            return snapshot.correlation.snapshotId.value > baseline.snapshotId.value ||
                baselineCapturedAtElapsedMillis == null ||
                snapshot.capturedAtElapsedMillis > baselineCapturedAtElapsedMillis
        }
    }
}

/** Process-wide setup owner; the Activity only supplies visible UI effects and observations. */
class HansSetupRuntime(
    context: Context,
    backgroundExecutor: Executor,
    storage: HansSetupStorage = AtomicFileHansSetupStorage(context),
    val uiRouter: SetupUiCommandRouter = SetupUiCommandRouter(),
    persistentAndroidConsentStore: PersistentAndroidConsentStore =
        PersistentAndroidConsentStore.NONE,
    val actionPolicy: HansPhoneActionPolicy = HansPhoneActionPolicy.CONFIRM_ACTIONS,
    private val probe: HansSetupFreshProbe = AndroidHansSetupFreshProbe(
        context,
        persistentAndroidConsentStore,
        actionPolicy,
    ),
) {
    init {
        // Also provision when setup is opened without constructing the normal chat store.
        // Failure remains unavailable in the fresh projection; setup must not claim success.
        runCatching { HansDesktopProject.ensure(context.applicationContext.filesDir) }
    }

    val repository = HansSetupRepository(storage)
    val dynamicTools = HansSetupDynamicToolExecutor(
        repository = repository,
        probe = probe,
        uiRouter = uiRouter,
        backgroundExecutor = backgroundExecutor,
        actionPolicy = actionPolicy,
    )

    fun attachUi(handler: SetupUiCommandHandler): Closeable = uiRouter.attach(handler)

    fun startOrResume(): HansSetupDocument {
        repository.startOrResume()
        return dynamicTools.refreshFreshEvidence()
    }

    fun snapshot(): HansSetupDocument = repository.read()

    /** Lifecycle/event driven re-probe; no timer or polling loop is installed. */
    fun refreshFreshEvidence(): HansSetupDocument = dynamicTools.refreshFreshEvidence()

    fun observeEffectiveSelection(model: String, reasoningEffort: String): HansSetupDocument =
        repository.observeEffectiveSelection(model, reasoningEffort)

    fun observeProfileConfirmed(confirmed: Boolean): HansSetupDocument =
        repository.observeProfileConfirmed(confirmed)

    fun observeSpeechCredentialAvailability(available: Boolean): HansSetupDocument =
        repository.observeSpeechCredentialAvailability(available)

    fun acceptRestrictedSettingsRecovery(
        token: HansSetupOperationToken,
    ): HansSetupDocument = repository.acceptRestrictedSettingsRecovery(token)

    fun markRestrictedSettingsManualRequired(
        token: HansSetupOperationToken,
    ): HansSetupDocument = repository.markRestrictedSettingsManualRequired(token)

    fun recordRestrictedSettingsNotNow(
        step: HansSetupStep,
    ): HansSetupDocument = repository.recordRestrictedSettingsNotNow(step)

    fun acceptKeyCapture(
        token: HansSetupOperationToken,
        success: Boolean,
        blocked: Boolean,
        detailCode: String,
    ): HansSetupDocument = repository.acceptKeyCapture(token, success, blocked, detailCode)

    fun abandonKeyCaptureForActivityRecreation(
        token: HansSetupOperationToken,
    ): HansSetupDocument = repository.abandonKeyCaptureForActivityRecreation(token)

    fun abandonDictationLiveTestForActivityRecreation(
        token: HansSetupOperationToken,
    ): HansSetupDocument = repository.abandonDictationLiveTestForActivityRecreation(token)

    fun acceptHardwareKeyCommand(
        token: HansSetupOperationToken,
        command: ActionKeyCommand,
    ): HansSetupDocument = repository.acceptHardwareCommand(token, command)

    fun acceptAccessibilityHardwareKeyCommand(
        token: HansSetupOperationToken,
        command: ActionKeyCommand,
    ): HansSetupDocument = repository.acceptAccessibilityHardwareCommand(token, command)

    fun acceptCameraDictation(
        token: HansSetupOperationToken,
        started: Boolean,
    ): HansSetupDocument = repository.acceptCameraDictation(token, started)

    fun acceptCameraCapture(token: HansSetupOperationToken): HansSetupDocument =
        repository.acceptCameraCapture(token)

    fun recordAccessibilityTargetAction(operationNonce: String): Boolean =
        probe.recordAccessibilityTargetAction(operationNonce)

    fun accessibilityTestCopy(operationNonce: String): AccessibilitySetupTestCopy? =
        probe.accessibilityTestCopy(operationNonce)

    fun recordAccessibilityPostcondition(operationNonce: String): Boolean =
        probe.recordAccessibilityPostcondition(operationNonce)

    fun acceptDictationEvidence(
        token: HansSetupOperationToken,
        evidence: HansSetupDictationEvidence,
    ): HansSetupDocument = repository.acceptDictationEvidence(token, evidence)

    fun postNotificationLiveTest(operationNonce: String): Boolean =
        probe.postNotificationLiveTest(operationNonce)

    fun armLiveTest(step: HansSetupStep, operationNonce: String): Boolean =
        probe.armLiveTest(step, operationNonce)

}
