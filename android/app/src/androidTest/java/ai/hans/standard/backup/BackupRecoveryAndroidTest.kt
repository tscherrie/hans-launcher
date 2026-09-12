package ai.hans.standard.backup

import ai.hans.standard.HansApplication
import ai.hans.standard.HansAutomationFixtureApplication
import ai.hans.standard.LauncherActivity
import ai.hans.standard.R
import ai.hans.standard.automations.AndroidAutomationDispatch
import ai.hans.standard.automations.AndroidAutomationWakeupAdapter
import ai.hans.standard.automations.AutomationProcessRecoveryFixture
import ai.hans.standard.automations.AutomationRuntimeTrigger
import ai.hans.standard.automations.AutomationRuntimeWorkKind
import ai.hans.standard.automations.HansAutomationAlarmReceiver
import ai.hans.standard.automations.HansAutomationRuntime
import ai.hans.standard.automations.HansAutomationSystemReceiver
import ai.hans.standard.automations.workKind
import ai.hans.standard.profile.AtomicFileUserProfileStorage
import ai.hans.standard.settings.SharedPreferencesHansSettingsStore
import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.Instrumentation
import android.app.PendingIntent
import android.app.job.JobScheduler
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.core.util.Consumer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One explicitly opted-in cold process on fresh synthetic app data per descriptor scenario.
 * The host creates that descriptor BEFORE instrumentation starts the real Application. Missing
 * opt-in/evidence is a failure, never a skip. No test starts Codex, uses accounts or grants access.
 */
@RunWith(AndroidJUnit4::class)
class BackupRecoveryAndroidTest {
    private lateinit var app: HansApplication
    private lateinit var fixture: BackupRecoveryFixtureInstance
    private val instrumentation: Instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun requireColdStartFixture() {
        val arguments = InstrumentationRegistry.getArguments()
        assertEquals("true", arguments.getString("hansBackupRecovery"))
        app = ApplicationProvider.getApplicationContext()
        assertEquals(HansAutomationFixtureApplication::class.java, app.javaClass)
        val descriptor = checkNotNull(BackupRecoveryFixtureProtocol.readDescriptor(app))
        assertEquals(descriptor.fixtureId, arguments.getString("hansBackupFixtureId"))
        assertEquals(descriptor.scenario, arguments.getString("hansBackupScenario"))
        BackupRecoveryFixtureProtocol.requireBoundDebugEmulator(app, descriptor)
        fixture = BackupRecoveryFixture.requireActive()
        assertEquals(descriptor, fixture.descriptor)
        // The backup bootstrap continues normal Application startup, never arming the older
        // fixture. Its Elvis fallback must not turn a delegated production-guard probe into a
        // fake rejection or an isolated scheduling path.
        assertNull(AutomationProcessRecoveryFixture.interceptEnqueue(AutomationRuntimeTrigger.Timer))
        assertNull(AutomationProcessRecoveryFixture.interceptRetry(AutomationRuntimeTrigger.Timer, 0))
        assertEquals(Process.myPid(), fixture.bootstrapPid)
        assertEquals("The real Application must complete exactly one cold start", 1, fixture.applicationCreateCount)
        assertTrue(fixture.applicationCompletedAtElapsedNanos >= fixture.preparedAtElapsedNanos)
        assertTrue(fixture.applicationCompletedAtElapsedNanos <= SystemClock.elapsedRealtimeNanos())
        assertFalse(
            "Backup observation failed: scenario=${fixture.descriptor.scenario}, " +
                "startupBlocked=${fixture.startupBlocked}, " +
                "maintenanceReady=${HansBackupProcessState.maintenance.isRecoveryReady}, " +
                "applicationCreateCount=${fixture.applicationCreateCount}, " +
                "dispatchCount=${fixture.dispatchCount}, firstDispatch=${fixture.firstDispatch}, " +
                "firstRetry=${fixture.firstRetry}, " +
                "blockedDispatchAttemptCount=${fixture.blockedDispatchAttemptCount}, " +
                "blockedRetryAttemptCount=${fixture.blockedRetryAttemptCount}, " +
                "firstBlockedDispatch=${fixture.firstBlockedDispatch}, " +
                "firstBlockedRetry=${fixture.firstBlockedRetry}, " +
                "unexpectedWakeupCount=${fixture.unexpectedWakeupCount}, " +
                "unexpectedWorkCount=${fixture.unexpectedWorkCount}",
            fixture.observationFailure,
        )
        assertEquals("Unexpected work: wakeups=${fixture.unexpectedWakeupCount}, firstRetry=${fixture.firstRetry}",
            0, fixture.unexpectedWorkCount)
        assertNoHostOrPlatformWork()
    }

    @Test
    fun safeJournalRecoversBeforeFirstDispatch() {
        requireScenario("safe")
        assertEquals(false, fixture.startupBlocked)
        assertFalse(app.backupRecoveryRequired)
        assertTrue(HansBackupProcessState.maintenance.isRecoveryReady)
        assertNotNull(HansAutomationRuntime.currentOrNull())
        assertTrue(fixture.dispatchCount >= 1)
        val first = checkNotNull(fixture.firstDispatch)
        assertEquals(AutomationRuntimeTrigger.DefinitionChanged, first.trigger)
        assertTrue("Dispatch must be observed inside the original Application.onCreate", first.duringApplicationCreate)
        assertTrue(first.recoveryReady)
        assertTrue(first.journalAbsent)
        assertTrue(first.restoredStores)
        assertTrue(first.ownerInstalled)
        assertTrue(first.hostSnapshotAbsent)
        // The fixture rejects the platform enqueue; the real dispatcher must therefore make
        // exactly one initial retry on the same call path before Application.onCreate returns.
        // This is an observed/rejected retry, never permission to schedule a real alarm or job.
        val retry = checkNotNull(fixture.firstRetry)
        assertEquals(AutomationRuntimeTrigger.DefinitionChanged, retry.trigger)
        assertEquals(0, retry.failedAttempt)
        assertTrue(retry.matchedRejectedEnqueue)
        assertTrue(retry.duringApplicationCreate)
        assertTrue(retry.recoveryReady)
        assertEquals(1, fixture.definitionChangedDispatchCount)
        assertEquals(1, fixture.definitionChangedRetryCount)
        assertEquals(fixture.before, fixture.readStores())
        assertTrue(fixture.journalHashes().isEmpty())
        assertFalse(fixture.before.automations.runs.isEmpty())
        assertFalse(fixture.before.automations.receipts.isEmpty())
        assertFalse(fixture.before.automations.manualInvocations.isEmpty())
        assertNotNull(fixture.before.automations.recoveryState)
        assertFalse(fixture.before == fixture.staged)

        // Observing/exporting backup state must not start a Codex host to invent a live catalogue.
        val dispatchesBeforeExport = fixture.definitionChangedDispatchCount
        val retriesBeforeExport = fixture.definitionChangedRetryCount
        val failure = assertThrows(HansBackupException::class.java) { app.backupCoordinator.exportDocument() }
        assertEquals("backup_plugin_catalog_unavailable", failure.errorCode)
        assertEquals(dispatchesBeforeExport, fixture.definitionChangedDispatchCount)
        assertEquals(retriesBeforeExport, fixture.definitionChangedRetryCount)
        assertEquals(fixture.before, fixture.readStores())
        assertNoHostOrPlatformWork()
    }

    @Test
    fun malformedJournalKeepsStartupBlocked() {
        requireScenario("malformed")
        assertBlockedAndUntouched()
        assertMutationBoundaryClosed()
        assertSystemDispatchAttemptsHitClosedProductionGuards()
    }

    @Test
    fun orphanNewJournalKeepsStartupBlocked() {
        requireScenario("orphan-new")
        assertEquals(setOf(".new"), fixture.preparedJournalHashes.keys)
        assertBlockedAndUntouched()
        assertMutationBoundaryClosed()
        assertSystemDispatchAttemptsHitClosedProductionGuards()
    }

    @Test
    fun legacyUnsettledJournalKeepsStartupBlocked() {
        requireScenario("legacy-unsettled")
        assertEquals(1, fixture.before.automations.leases.size)
        assertNotNull(fixture.before.automations.runs.single().dispatchFence)
        assertBlockedAndUntouched()
        assertMutationBoundaryClosed()
        assertSystemDispatchAttemptsHitClosedProductionGuards()
    }

    @Test
    fun recoveryActivitySurvivesLifecycleAndPublicSettingsActions() {
        requireScenario("malformed")
        assertBlockedAndUntouched()
        val settingsMonitor = instrumentation.addMonitor(
            IntentFilter(Settings.ACTION_SETTINGS), Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true,
        )
        val homeMonitor = instrumentation.addMonitor(
            IntentFilter(Settings.ACTION_HOME_SETTINGS), Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true,
        )
        try {
            withRecoveryActivity { scenario, remember ->
                scenario.onActivity { activity ->
                    assertRecoveryUi(activity)
                    assertTrue(button(activity, R.string.backup_recovery_android_settings).performClick())
                    assertTrue(button(activity, R.string.backup_recovery_home_settings).performClick())
                }
                assertEquals(1, settingsMonitor.hits)
                assertEquals(1, homeMonitor.hits)
                // Public navigation is exercised, but monitors prevent actually changing Android
                // settings/Home roles on the emulator.
                instrumentation.removeMonitor(settingsMonitor)
                instrumentation.removeMonitor(homeMonitor)
                scenario.moveToState(Lifecycle.State.CREATED)
                assertEquals(Lifecycle.State.CREATED, scenario.state)
                scenario.moveToState(Lifecycle.State.RESUMED)
                val reentryDelivered = CountDownLatch(1)
                val reentryListener = Consumer<Intent> { intent ->
                    if (intent.action == Intent.ACTION_MAIN &&
                        intent.getStringExtra("backup_fixture_reentry") == fixture.descriptor.fixtureId
                    ) reentryDelivered.countDown()
                }
                var reenteredActivity: LauncherActivity? = null
                var launchIdentity: Intent? = null
                scenario.onActivity { activity ->
                    assertRecoveryUi(activity)
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
                    activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A))
                    reenteredActivity = activity
                    launchIdentity = activity.intent.cloneFilter()
                    assertEquals(Intent.ACTION_MAIN, activity.intent.action)
                    assertTrue(activity.intent.hasCategory(Intent.CATEGORY_LAUNCHER))
                    activity.addOnNewIntentListener(reentryListener)
                }
                try {
                    scenario.onActivity { activity ->
                        // ActivityScenario matches lifecycle events against its initial MAIN +
                        // LAUNCHER identity. Preserve that filter when the real onNewIntent calls
                        // setIntent; only the delivery flags and synthetic UUID should change.
                        val reentry = Intent(activity.intent)
                            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("backup_fixture_reentry", fixture.descriptor.fixtureId)
                        assertTrue("Reentry must preserve ActivityScenario's launch identity",
                            checkNotNull(launchIdentity).filterEquals(reentry))
                        activity.startActivity(reentry)
                    }
                    assertTrue("Framework must deliver MAIN + fixture UUID to the existing Activity.onNewIntent",
                        reentryDelivered.await(10, TimeUnit.SECONDS))
                    // The ComponentActivity listener runs in super.onNewIntent. A subsequent
                    // main-thread assertion also proves the product's setIntent/guard completed.
                    scenario.onActivity { activity ->
                        assertSame(reenteredActivity, activity)
                        assertEquals(fixture.descriptor.fixtureId, activity.intent.getStringExtra("backup_fixture_reentry"))
                        assertTrue("Product setIntent must remain trackable by ActivityScenario",
                            checkNotNull(launchIdentity).filterEquals(activity.intent))
                        assertRecoveryUi(activity)
                    }
                } finally {
                    instrumentation.runOnMainSync {
                        reenteredActivity?.removeOnNewIntentListener(reentryListener)
                    }
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertRecoveryUi(activity)
                    val retry = button(activity, R.string.backup_recovery_retry)
                    assertTrue(retry.performClick())
                    assertFalse(retry.isEnabled)
                }
                // This barrier follows the genuine retry button task; it does not call recovery
                // itself. No timer or polling is added to the production recovery screen.
                var retryExecutor: ExecutorService? = null
                scenario.onActivity { retryExecutor = ownField(it, "backupExecutor") as ExecutorService }
                checkNotNull(retryExecutor).submit {}.get(10, TimeUnit.SECONDS)
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    assertRecoveryUi(activity)
                    assertTrue(button(activity, R.string.backup_recovery_retry).isEnabled)
                }
                assertBlockedAndUntouched()
                scenario.recreate()
                scenario.onActivity { activity ->
                    remember(activity)
                    assertRecoveryUi(activity)
                }
                assertEquals(1, fixture.applicationCreateCount)
                assertBlockedAndUntouched()
            }
        } finally {
            instrumentation.removeMonitor(settingsMonitor)
            instrumentation.removeMonitor(homeMonitor)
        }
        assertBlockedAndUntouched()
    }

    @Test
    fun savedActivityResultsRemainBlockedAcrossStartAndRecreation() {
        requireScenario("malformed")
        assertBlockedAndUntouched()
        val deliveries = linkedSetOf<String>()
        for (success in listOf(true, false)) {
            withRecoveryActivity { scenario, _ ->
                scenario.moveToState(Lifecycle.State.CREATED)
                var observer: BackupRecoveryResultDeliveryObserver? = null
                scenario.onActivity { activity ->
                    assertRecoveryUi(activity)
                    val pending = BackupRecoverySavedResults.enqueue(activity, fixture.descriptor.fixtureId, success)
                    observer = BackupRecoveryResultDeliveryObserver(activity, pending, "restart-$success", deliveries)
                    activity.lifecycle.addObserver(checkNotNull(observer))
                }
                scenario.moveToState(Lifecycle.State.RESUMED)
                scenario.onActivity { activity ->
                    checkNotNull(observer).assertDelivered()
                    assertRecoveryUi(activity)
                    assertNoUiExecutorStarted()
                }
            }

            withRecoveryActivity { scenario, remember ->
                var original: LauncherActivity? = null
                var pending: BackupRecoveryPendingResults? = null
                var restored: BackupRecoveryResultDeliveryObserver? = null
                var frameworkSaved = false
                scenario.onActivity { activity ->
                    original = activity
                    // With no launched key, AndroidX buffers this raw result even when resumed.
                    // ActivityScenario.recreate therefore cannot consume it by resuming the old
                    // instance first: it must pass through the actual framework saved-state path.
                    pending = BackupRecoverySavedResults.enqueue(activity, fixture.descriptor.fixtureId, success)
                }
                val lifecycle = object : BackupRecoveryActivityCallbacks() {
                    override fun onActivityPostSaveInstanceState(activity: Activity, outState: Bundle) {
                        if (activity !== original) return
                        BackupRecoverySavedResults.assertFrameworkSaved(outState, checkNotNull(pending))
                        frameworkSaved = true
                    }

                    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                        if (activity !is LauncherActivity || activity === original) return
                        assertTrue("Original registry results must actually be saved", frameworkSaved)
                        assertNotNull("Framework must supply saved state to the new instance", savedInstanceState)
                        remember(activity)
                        BackupRecoverySavedResults.assertPending(activity, checkNotNull(pending))
                        restored = BackupRecoveryResultDeliveryObserver(
                            activity, checkNotNull(pending), "recreate-$success", deliveries,
                        )
                        activity.lifecycle.addObserver(checkNotNull(restored))
                    }
                }
                app.registerActivityLifecycleCallbacks(lifecycle)
                try {
                    scenario.recreate()
                    scenario.onActivity { activity ->
                        assertFalse(original === activity)
                        assertTrue(frameworkSaved)
                        checkNotNull(restored).assertDelivered()
                        assertRecoveryUi(activity)
                        assertNoUiExecutorStarted()
                    }
                } finally {
                    app.unregisterActivityLifecycleCallbacks(lifecycle)
                }
            }
        }
        assertEquals("7 actual registrations × success/cancel × stop/start/recreation", 28, deliveries.size)
        assertBlockedAndUntouched()
    }

    private fun requireScenario(scenario: String) = assertEquals(scenario, fixture.descriptor.scenario)

    /** Deterministic version of the API-35 BOOT_COMPLETED delivery after a blocked cold start. */
    private fun assertSystemDispatchAttemptsHitClosedProductionGuards() {
        instrumentation.runOnMainSync {
            assertBlockedAndUntouched()
            val storesBefore = durableSyntheticStoreHashes()
            val journalBefore = fixture.journalHashes()
            assertNoAutomationPendingIntents()
            val debugApplication = app as HansAutomationFixtureApplication
            // Prove the complete debug-interceptor chain delegates instead of manufacturing
            // the same false result that the production guard is expected to return below.
            assertNull(debugApplication.interceptAutomationEnqueue(AutomationRuntimeTrigger.Timer))
            assertNull(debugApplication.interceptAutomationRetry(AutomationRuntimeTrigger.Timer, 0))
            assertBlockedAndUntouched()
            val receiver = HansAutomationSystemReceiver()
            listOf(
                Intent.ACTION_BOOT_COMPLETED to AutomationRuntimeWorkKind.BOOT,
                Intent.ACTION_TIMEZONE_CHANGED to AutomationRuntimeWorkKind.TIME_ZONE_CHANGED,
                Intent.ACTION_TIME_CHANGED to AutomationRuntimeWorkKind.WALL_CLOCK_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED to AutomationRuntimeWorkKind.DEFINITION_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED to
                    AutomationRuntimeWorkKind.DEFINITION_CHANGED,
            ).forEach { (action, kind) ->
                assertBlockedAttempt(action, kind) { receiver.onReceive(app, Intent(action)) }
            }
            val alarmReceiver = HansAutomationAlarmReceiver()
            assertBlockedAttempt("exact-alarm receiver", AutomationRuntimeWorkKind.TIMER) {
                alarmReceiver.onReceive(app, Intent(AndroidAutomationWakeupAdapter.ACTION_ALARM))
            }
            val retryIntent = Intent(AndroidAutomationDispatch.ACTION_DISPATCH_RETRY)
                .putExtra("trigger", "connectivity")
                .putExtra("retry_attempt", 2)
            assertEquals(AutomationRuntimeTrigger.ConnectivityRestored, AndroidAutomationDispatch.triggerFrom(retryIntent))
            assertEquals(2, AndroidAutomationDispatch.retryAttemptFrom(retryIntent))
            assertBlockedAttempt("retry-alarm receiver", AutomationRuntimeWorkKind.CONNECTIVITY_RESTORED, 2) {
                alarmReceiver.onReceive(app, retryIntent)
            }
            // The fixture returns null (and the older fixture also returns null), so these
            // negative results come from the actual enqueue/scheduleRetry recovery guards.
            blockedProbeTriggers().forEach { trigger ->
                assertBlockedAttempt("direct ${trigger.workKind()}", trigger.workKind()) {
                    assertFalse("Real enqueue must reject $trigger", AndroidAutomationDispatch.enqueue(app, trigger))
                    assertFalse("Real retry must reject $trigger", AndroidAutomationDispatch.scheduleRetry(app, trigger, 0))
                }
            }
            assertNoAutomationPendingIntents()
            assertBlockedAndUntouched()
            assertEquals("Receiver attempts must retain the exact journal bytes", journalBefore, fixture.journalHashes())
            assertEquals("Receiver attempts must not rewrite persistent stores", storesBefore, durableSyntheticStoreHashes())
        }
    }

    private fun assertBlockedAttempt(
        label: String,
        kind: AutomationRuntimeWorkKind,
        retryAttempt: Int = 0,
        action: () -> Unit,
    ) {
        val enqueues = fixture.blockedDispatchAttemptCount
        val retries = fixture.blockedRetryAttemptCount
        action()
        // Called inside one main-thread block: unrelated OS broadcasts cannot interleave.
        assertEquals("$label must reach the real enqueue entry", enqueues + 1, fixture.blockedDispatchAttemptCount)
        assertEquals("$label must reach the real retry entry", retries + 1, fixture.blockedRetryAttemptCount)
        val enqueue = checkNotNull(fixture.lastBlockedDispatch)
        val retry = checkNotNull(fixture.lastBlockedRetry)
        assertEquals(label, kind, enqueue.trigger.workKind())
        assertEquals("$label retry must retain the exact trigger", enqueue.trigger, retry.trigger)
        assertEquals(label, retryAttempt, retry.failedAttempt)
        assertTrue("$label must retry the same-thread rejected enqueue", retry.matchedRejectedEnqueue)
        assertFalse(enqueue.duringApplicationCreate)
        assertFalse(retry.duringApplicationCreate)
        assertTrue(enqueue.startupBlocked)
        assertFalse(enqueue.recoveryReady)
        assertFalse(retry.recoveryReady)
        assertTrue(enqueue.journalUnchanged)
        assertTrue(enqueue.storesUnchanged)
        assertTrue(enqueue.ownerAbsent)
        assertTrue(enqueue.hostSnapshotAbsent)
        assertBlockedAndUntouched()
    }

    private fun blockedProbeTriggers(): List<AutomationRuntimeTrigger> {
        val observedAt = Instant.parse("2026-01-01T09:00:00Z")
        return listOf(
            AutomationRuntimeTrigger.Timer,
            AutomationRuntimeTrigger.ManualRun,
            AutomationRuntimeTrigger.ConnectivityRestored,
            AutomationRuntimeTrigger.DefinitionChanged,
            AutomationRuntimeTrigger.Boot(observedAt),
            AutomationRuntimeTrigger.TimeZoneChanged(observedAt),
            AutomationRuntimeTrigger.WallClockChanged(observedAt),
        )
    }

    private fun assertNoAutomationPendingIntents() {
        // Inspect only our own private request-code mapping; Android APIs remain public and
        // FLAG_NO_CREATE can neither create nor cancel a PendingIntent during observation.
        val requestCode = AndroidAutomationDispatch.javaClass.getDeclaredMethod(
            "retryAlarmRequestCode", AutomationRuntimeTrigger::class.java,
        ).apply { isAccessible = true }
        blockedProbeTriggers().forEach { trigger ->
            assertNull(
                "Unexpected retry PendingIntent for ${trigger.workKind()}",
                PendingIntent.getBroadcast(
                    app,
                    requestCode.invoke(AndroidAutomationDispatch, trigger) as Int,
                    Intent(app, HansAutomationAlarmReceiver::class.java)
                        .setAction(AndroidAutomationDispatch.ACTION_DISPATCH_RETRY),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        val alarmRequestCode = Class.forName("ai.hans.standard.automations.AndroidPlatformAutomationWakeupBackend")
            .getDeclaredField("ALARM_REQUEST_CODE").apply { isAccessible = true }.getInt(null)
        assertNull(
            "Unexpected exact-alarm PendingIntent",
            PendingIntent.getBroadcast(
                app, alarmRequestCode,
                Intent(app, HansAutomationAlarmReceiver::class.java).setAction(AndroidAutomationWakeupAdapter.ACTION_ALARM),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private fun durableSyntheticStoreHashes(): Map<String, String> = buildMap {
        val data = File(app.applicationInfo.dataDir)
        listOf(
            "shared_prefs/hans_settings_v1.xml", "files/hans-user-profile-v1.json",
            "no_backup/hans-imported-plugin-choices-v1.json", "no_backup/hans_automations_v1.db",
        ).forEach { relative ->
            assertTrue("Missing synthetic store: $relative", Files.isRegularFile(File(data, relative).toPath(), NOFOLLOW_LINKS))
            // SQLite's -shm is volatile reader coordination, not durable user data. Include
            // database/WAL/journal bytes, plus atomic-file sidecars, without opening a store.
            listOf("", ".bak", ".new", "-wal", "-journal").forEach { suffix ->
                val file = File(data, relative + suffix)
                if (Files.exists(file.toPath(), NOFOLLOW_LINKS)) {
                    assertTrue(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
                    put(relative + suffix, BackupRecoveryFixtureProtocol.sha256(file))
                }
            }
        }
    }

    private fun assertBlockedAndUntouched() {
        assertEquals(true, fixture.startupBlocked)
        assertTrue(app.backupRecoveryRequired)
        assertFalse(HansBackupProcessState.maintenance.isRecoveryReady)
        assertNull(HansAutomationRuntime.currentOrNull())
        assertEquals(0, fixture.dispatchCount)
        assertEquals(0, fixture.definitionChangedRetryCount)
        assertNull(fixture.firstDispatch)
        assertNull(fixture.firstRetry)
        assertFalse(fixture.observationFailure)
        assertEquals(0, fixture.unexpectedWorkCount)
        assertEquals(fixture.preparedJournalHashes, fixture.journalHashes())
        assertEquals(fixture.staged, fixture.readStores())
        assertUninitializedApplicationLazy("automationRuntime\$delegate")
        assertNoHostOrPlatformWork()
    }

    private fun assertMutationBoundaryClosed() {
        assertThrows(HansBackupException::class.java) {
            SharedPreferencesHansSettingsStore(app).saveRestoredNonDispatchPreferences(fixture.before.settings)
        }
        assertThrows(HansBackupException::class.java) { AtomicFileUserProfileStorage(app).write(fixture.before.profile) }
        assertThrows(HansBackupException::class.java) { app.backupCoordinator.exportDocument() }
        assertBlockedAndUntouched()
    }

    private fun assertNoHostOrPlatformWork() {
        assertNull(app.passiveInitializedSessionHostSnapshot())
        // Reflection is limited to our own class, not Android internals. A null snapshot alone
        // could also mean an initialized host which has not reported anything yet.
        assertUninitializedApplicationLazy("sessionHostDelegate")
        assertUninitializedApplicationLazy("setupRuntime\$delegate")
        assertTrue(app.getSystemService(JobScheduler::class.java).allPendingJobs.isEmpty())
        val data = File(app.applicationInfo.dataDir)
        listOf(
            "no_backup/codex", "files/codex-workspace", "cache/automation-codex-sessions", "cache/codex-tmp",
            "shared_prefs/hans_codex_session_v1.xml", "shared_prefs/hans_speech_credential_v1.xml",
            "shared_prefs/hans_automation_work_generations_v1.xml", "no_backup/hans-setup-state-v1.json",
            "no_backup/hans-setup-handoff-v1.json",
        ).forEach { relative ->
            listOf("", ".bak", ".new").forEach { suffix ->
                assertFalse("Unexpected runtime/setup store: $relative$suffix",
                    Files.exists(File(data, relative + suffix).toPath(), NOFOLLOW_LINKS))
            }
        }
    }

    private fun assertUninitializedApplicationLazy(name: String) {
        val delegate = HansApplication::class.java.getDeclaredField(name).apply { isAccessible = true }.get(app)
        assertTrue("Expected the owned lazy field $name", delegate is Lazy<*>)
        assertFalse("Unexpectedly initialized $name", (delegate as Lazy<*>).isInitialized())
    }

    private fun assertRecoveryUi(activity: LauncherActivity) {
        assertSame(app, activity.application)
        assertEquals(true, ownField(activity, "backupRecoveryOnlyUi"))
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val texts = descendants(root).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        listOf(R.string.backup_recovery_title, R.string.backup_recovery_message,
            R.string.backup_recovery_retry, R.string.backup_recovery_android_settings,
            R.string.backup_recovery_home_settings).forEach { id ->
            assertEquals("Exactly one static recovery label", 1, texts.count { it == activity.getString(id) })
        }
        assertEquals(3, descendants(root).filterIsInstance<Button>().count())
        listOf("sessionHost", "sessionStore", "settingsStore", "setupRuntime", "automationRuntime",
            "backupCoordinator", "backupDocumentIo", "cameraCaptureCoordinator", "mediaPipeline",
            "periodicAccountRefresh").forEach { field ->
            assertNull("Recovery must not initialize $field", ownField(activity, field))
        }
        assertNoHostOrPlatformWork()
    }

    private fun button(activity: LauncherActivity, stringId: Int): Button =
        descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
            .single { it.text.toString() == activity.getString(stringId) }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun assertNoUiExecutorStarted() {
        // A missing SAF guard would enqueue work that catches its own lateinit failure. Merely
        // surviving is insufficient: those callbacks must not start any of the three executors.
        assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name in UI_EXECUTOR_NAMES })
    }

    private fun withRecoveryActivity(
        block: (ActivityScenario<LauncherActivity>, (LauncherActivity) -> Unit) -> Unit,
    ) {
        val executors = linkedSetOf<ExecutorService>()
        val remember: (LauncherActivity) -> Unit = { activity ->
            listOf("backupExecutor", "mediaExecutor", "appCatalogExecutor").forEach {
                executors += ownField(activity, it) as ExecutorService
            }
        }
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use { scenario ->
                scenario.onActivity(remember)
                block(scenario, remember)
            }
        } finally {
            executors.forEach {
                assertTrue("Real Activity.onDestroy must shut down its executor", it.isShutdown)
                assertTrue("No executor can leak into another result case", it.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    private fun ownField(activity: LauncherActivity, name: String): Any? =
        LauncherActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private companion object {
        val UI_EXECUTOR_NAMES = setOf("hans-backup", "hans-private-media", "hans-app-catalog")
    }
}

internal open class BackupRecoveryActivityCallbacks : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
