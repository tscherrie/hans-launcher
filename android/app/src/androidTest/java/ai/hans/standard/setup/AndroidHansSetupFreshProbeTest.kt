package ai.hans.standard.setup

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import ai.hans.standard.phone.consent.PersistentAndroidConsentCatalog
import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentStore
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHansSetupFreshProbeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val probe = AndroidHansSetupFreshProbe(context)

    @Test
    fun runtimePolicyProjectionUsesTrustedInjectionWithoutGrantingAndroidAccessOrCreatingABundle() {
        listOf(
            null to emptySet(), // The real Runtime constructor's fallback must stay CONFIRM_ACTIONS.
            HansPhoneActionPolicy.CONFIRM_ACTIONS to PersistentAndroidConsentCatalog.EVERYDAY_DESCRIPTORS,
            HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS to emptySet(),
        ).forEach { (requestedPolicy, bundle) ->
            withIsolatedPolicyContext { isolated, preferences ->
                val consent = ReadOnlySetupConsentStore(bundle)
                val storage = InMemoryPolicySetupStorage()
                val beforeAndroidGrants = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS)
                    .associateWith(context::checkSelfPermission)
                var uiRequests = 0
                val router = SetupUiCommandRouter()
                val registration = router.attach(SetupUiCommandHandler { _, _, completion ->
                    uiRequests += 1
                    completion(SetupUiCommandResult.Rejected("unexpected_policy_ui_request"))
                })
                try {
                    val runtime = if (requestedPolicy == null) {
                        HansSetupRuntime(
                            context = isolated,
                            backgroundExecutor = Executor(Runnable::run),
                            storage = storage,
                            uiRouter = router,
                            persistentAndroidConsentStore = consent,
                        )
                    } else {
                        HansSetupRuntime(
                            context = isolated,
                            backgroundExecutor = Executor(Runnable::run),
                            storage = storage,
                            uiRouter = router,
                            persistentAndroidConsentStore = consent,
                            actionPolicy = requestedPolicy,
                        )
                    }
                    var result: DynamicToolExecutionResult? = null
                    runtime.dynamicTools.execute(
                        DynamicToolCallParams(
                            threadId = "synthetic_setup_policy_thread",
                            turnId = "synthetic_setup_policy_${UUID.randomUUID()}",
                            callId = "synthetic_setup_policy_${UUID.randomUUID()}",
                            namespace = HansSetupDynamicToolCatalog.NAMESPACE,
                            tool = "get_setup_state",
                            argumentsJson = "{}",
                        ),
                    ) { result = it }
                    val actual = checkNotNull(result)
                    assertTrue(actual.success)
                    val json = JSONObject(actual.contentText)
                    val expectedPolicy = requestedPolicy ?: HansPhoneActionPolicy.CONFIRM_ACTIONS
                    assertEquals(expectedPolicy, runtime.actionPolicy)
                    assertEquals(expectedPolicy.wireValue, json.getString("phoneActionPolicy"))
                    assertEquals("intro", json.getString("currentStep"))
                    assertFalse(json.getBoolean("complete"))
                    val state = runtime.snapshot()
                    assertFalse(state.record(HansSetupStep.MICROPHONE_ACCESS).status == HansSetupStepStatus.VERIFIED)
                    assertFalse(state.optionalCapabilityRecord(HansSetupOptionalCapability.CONTACTS).effective == true)
                    assertEquals(
                        requestedPolicy != null,
                        state.optionalCapabilityRecord(HansSetupOptionalCapability.EVERYDAY_ACCESS).effective == true,
                    )
                    assertEquals(bundle, consent.active())
                    assertEquals(0, consent.mutationAttempts)
                    assertEquals(0, uiRequests)
                    assertEquals(beforeAndroidGrants, beforeAndroidGrants.keys.associateWith(context::checkSelfPermission))
                    assertTrue("Policy reads must not write settings or credentials", preferences.all { it.all.isEmpty() })
                } finally {
                    registration.close()
                }
            }
        }
    }

    @Test
    fun appNotificationProbeMatchesTheEffectiveRuntimePermissionContract() {
        val expected = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

        HansPhoneActionPolicy.entries.forEach { policy ->
            val policyProbe = AndroidHansSetupFreshProbe(context, actionPolicy = policy)
            assertEquals(
                policy.name,
                expected,
                policyProbe.probe(HansSetupStep.APP_NOTIFICATIONS_ACCESS).verified,
            )
        }
    }

    @Test
    fun optionalRuntimePermissionProbesMatchFreshAndroidState() {
        val expected = mapOf(
            HansSetupOptionalCapability.CONTACTS to granted(Manifest.permission.READ_CONTACTS),
            HansSetupOptionalCapability.CALENDAR to (
                granted(Manifest.permission.READ_CALENDAR) &&
                    granted(Manifest.permission.WRITE_CALENDAR)
                ),
            HansSetupOptionalCapability.LOCATION to (
                granted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
                    granted(Manifest.permission.ACCESS_FINE_LOCATION)
                ),
            HansSetupOptionalCapability.PHOTOS_VIDEOS to when {
                Build.VERSION.SDK_INT >= 34 ->
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ||
                        granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                        granted(Manifest.permission.READ_MEDIA_VIDEO)
                Build.VERSION.SDK_INT >= 33 ->
                    granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                        granted(Manifest.permission.READ_MEDIA_VIDEO)
                else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
            HansSetupOptionalCapability.AUDIO_MEDIA to if (Build.VERSION.SDK_INT >= 33) {
                granted(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            },
            HansSetupOptionalCapability.EXACT_ALARMS to runCatching {
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
            }.getOrDefault(false),
        )

        HansPhoneActionPolicy.entries.forEach { policy ->
            val policyProbe = AndroidHansSetupFreshProbe(context, actionPolicy = policy)
            expected.forEach { (capability, effective) ->
                assertEquals(
                    "${policy.name}/${capability.name}",
                    effective,
                    policyProbe.probeOptionalCapability(capability).verified,
                )
            }
            assertEquals(
                "${policy.name}/microphone",
                granted(Manifest.permission.RECORD_AUDIO),
                policyProbe.probe(HansSetupStep.MICROPHONE_ACCESS).verified,
            )
        }
    }

    @Test
    fun quickSettingsTileProbeNeverInventsPersistentState() {
        val receipt = probe.probeOptionalCapability(
            HansSetupOptionalCapability.QUICK_SETTINGS_TILE,
        )

        assertFalse(receipt.verified)
        assertEquals(Build.VERSION.SDK_INT < 33, receipt.blocked)
    }

    @Test
    fun everydayAccessProbeReadsTheDurableStoreInsteadOfButtonState() {
        val active = PersistentAndroidConsentCatalog.EVERYDAY_DESCRIPTORS
        val bundleProbe = AndroidHansSetupFreshProbe(
            context,
            ReadOnlySetupConsentStore(active),
        )

        assertEquals(
            true,
            bundleProbe.probeOptionalCapability(
                HansSetupOptionalCapability.EVERYDAY_ACCESS,
            ).verified,
        )
    }

    @Test
    fun fullAccessEverydayProbeDoesNotReadOrCreateTheDurableBundle() {
        val store = ReadOnlySetupConsentStore(emptySet())
        val fullAccessProbe = AndroidHansSetupFreshProbe(
            context,
            store,
            HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
        )

        val result = fullAccessProbe.probeOptionalCapability(
            HansSetupOptionalCapability.EVERYDAY_ACCESS,
        )

        assertTrue(result.verified)
        assertEquals("everyday_access_full_access_policy", result.detailCode)
        assertEquals(0, store.readCount)
        assertEquals(0, store.mutationAttempts)
        assertTrue(store.active().isEmpty())
    }

    @Test
    fun confirmationModeEverydayProbeStillRequiresTheDurableBundle() {
        val store = ReadOnlySetupConsentStore(emptySet())
        val confirmationProbe = AndroidHansSetupFreshProbe(context, store)

        val result = confirmationProbe.probeOptionalCapability(
            HansSetupOptionalCapability.EVERYDAY_ACCESS,
        )

        assertFalse(result.verified)
        assertEquals("everyday_access_bundle_missing", result.detailCode)
        assertTrue(store.readCount > 0)
        assertEquals(0, store.mutationAttempts)
    }

    @Test
    fun notificationLinkMetadataProbeRequiresItsExactSeparateConsent() {
        val descriptor = PersistentAndroidConsentDescriptor.category(
            PersistentAndroidConsentScope.NOTIFICATION_LINK_METADATA,
        )
        HansPhoneActionPolicy.entries.forEach { policy ->
            val without = AndroidHansSetupFreshProbe(
                context,
                ReadOnlySetupConsentStore(emptySet()),
                policy,
            )
            val withOther = AndroidHansSetupFreshProbe(
                context,
                ReadOnlySetupConsentStore(PersistentAndroidConsentCatalog.EVERYDAY_DESCRIPTORS),
                policy,
            )
            val withExact = AndroidHansSetupFreshProbe(
                context,
                ReadOnlySetupConsentStore(setOf(descriptor)),
                policy,
            )

            assertFalse(
                policy.name,
                without.probeOptionalCapability(
                    HansSetupOptionalCapability.NOTIFICATION_LINK_METADATA,
                ).verified,
            )
            assertFalse(
                policy.name,
                withOther.probeOptionalCapability(
                    HansSetupOptionalCapability.NOTIFICATION_LINK_METADATA,
                ).verified,
            )
            assertTrue(
                policy.name,
                withExact.probeOptionalCapability(
                    HansSetupOptionalCapability.NOTIFICATION_LINK_METADATA,
                ).verified,
            )
        }
    }

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Synthetic denied probes; no runtime permission or real Hans preference is modified. */
    private fun withIsolatedPolicyContext(action: (Context, Collection<SharedPreferences>) -> Unit) {
        val prefix = "setup_policy_test_${UUID.randomUUID()}_"
        val fixture = File(context.cacheDir, prefix)
        check(fixture.mkdir())
        val preferences = linkedMapOf<String, SharedPreferences>()
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = fixture
            override fun getNoBackupFilesDir(): File = fixture
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                preferences.getOrPut(prefix + name) { context.getSharedPreferences(prefix + name, mode) }
            override fun checkSelfPermission(permission: String): Int = when (permission) {
                Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS -> PackageManager.PERMISSION_DENIED
                else -> context.checkSelfPermission(permission)
            }
        }
        try {
            action(isolated, preferences.values)
        } finally {
            preferences.keys.forEach { name ->
                check(name.startsWith(prefix))
                context.deleteSharedPreferences(name)
            }
            check(fixture.deleteRecursively())
        }
    }
}

private class InMemoryPolicySetupStorage : HansSetupStorage {
    private var document = HansSetupDocument()
    override fun read(): HansSetupDocument = document
    override fun write(document: HansSetupDocument) { this.document = document }
}

private class ReadOnlySetupConsentStore(
    private val values: Set<PersistentAndroidConsentDescriptor>,
) : PersistentAndroidConsentStore {
    var readCount = 0
        private set
    var mutationAttempts = 0
        private set

    override fun contains(descriptor: PersistentAndroidConsentDescriptor): Boolean {
        readCount += 1
        return descriptor in values
    }
    override fun grant(descriptor: PersistentAndroidConsentDescriptor) = rejectMutation()
    override fun grantAll(descriptors: Set<PersistentAndroidConsentDescriptor>) = rejectMutation()
    override fun revoke(descriptor: PersistentAndroidConsentDescriptor) = rejectMutation()
    override fun revokeAll() = rejectMutation()
    override fun active(): Set<PersistentAndroidConsentDescriptor> {
        readCount += 1
        return values
    }

    private fun rejectMutation(): Boolean {
        mutationAttempts += 1
        return false
    }
}
