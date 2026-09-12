package ai.hans.standard.phone.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.hans.standard.notifications.HansNotificationExclusionPolicy
import ai.hans.standard.notifications.NotificationDeliveryState
import ai.hans.standard.notifications.NotificationTriageQueue
import ai.hans.standard.notifications.NotificationTriageQueueState
import ai.hans.standard.notifications.NotificationTriageStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NotificationPrivacyPersistenceTest {
    private lateinit var context: Context
    private lateinit var settingsFileName: String
    private lateinit var purgeFenceFileName: String
    private lateinit var databaseName: String
    private lateinit var triagePurger: FakeTriageDataPurger
    private lateinit var factPrivacy: RecordingNotificationFactPrivacyPort
    private lateinit var purgeFence: NotificationPrivacyPurgeFence

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val suffix = System.nanoTime()
        settingsFileName = "notification-privacy-test-$suffix.json"
        purgeFenceFileName = "notification-privacy-purge-fence-test-$suffix"
        databaseName = "notification-privacy-test-$suffix.db"
        triagePurger = FakeTriageDataPurger()
        factPrivacy = RecordingNotificationFactPrivacyPort()
        deleteSettingsFiles()
        deletePurgeFenceFiles()
        purgeFence = AtomicNotificationPrivacyPurgeFence(context, purgeFenceFileName)
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        deleteSettingsFiles()
        deletePurgeFenceFiles()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun privacyPurgeFenceFailsClosedAndSurvivesReopen() {
        val missing = AtomicNotificationPrivacyPurgeFence(context, purgeFenceFileName)
        assertTrue(missing.isRequired())

        assertTrue(missing.markClean())
        assertFalse(AtomicNotificationPrivacyPurgeFence(context, purgeFenceFileName).isRequired())

        assertTrue(missing.markRequired())
        assertTrue(AtomicNotificationPrivacyPurgeFence(context, purgeFenceFileName).isRequired())

        context.noBackupFilesDir.resolve(purgeFenceFileName).writeText("corrupt")
        assertTrue(AtomicNotificationPrivacyPurgeFence(context, purgeFenceFileName).isRequired())
    }

    @Test
    fun exclusionsAndRetentionSurviveReopenAndCorruptionFailsClosed() {
        val first = repository()
        first.excludePackage("com.example.chat")
        first.setRetention(321, 48)

        val reopened = repository()
        assertEquals(
            NotificationCaptureDecision.UserExcludedPackage,
            reopened.captureDecision("com.example.chat"),
        )
        assertEquals(NotificationRetentionPolicy(321, 48), reopened.status().retention)

        context.noBackupFilesDir.resolve(settingsFileName).writeText("{broken")
        val corrupted = repository()
        assertFalse(corrupted.status().policyAvailable)
        assertEquals(
            NotificationCaptureDecision.PolicyUnavailable,
            corrupted.captureDecision("com.example.other"),
        )

        corrupted.importRecoveryDocument(
            NotificationPrivacyRecoveryCodec.encode(NotificationPrivacySettings()),
        )
        assertTrue(corrupted.status().policyAvailable)
        assertEquals(
            NotificationCaptureDecision.Allowed,
            corrupted.captureDecision("com.example.other"),
        )
    }

    @Test
    fun backupOnlySettingsDocumentRestoresExclusionsBeforeCaptureDecision() {
        repository().excludePackage("com.example.chat")
        val base = context.noBackupFilesDir.resolve(settingsFileName)
        val backup = context.noBackupFilesDir.resolve("$settingsFileName.bak")
        backup.delete()
        assertTrue(base.renameTo(backup))

        val reopened = repository()

        assertEquals(
            NotificationCaptureDecision.UserExcludedPackage,
            reopened.captureDecision("com.example.chat"),
        )
        assertEquals(setOf("com.example.chat"), reopened.status().userExcludedPackages)
    }

    @Test
    fun initializedPolicyLossFailsClosedInsteadOfReenablingCapture() {
        val initialized = repository()
        initialized.excludePackage("com.example.chat")
        context.noBackupFilesDir.resolve(settingsFileName).delete()
        context.noBackupFilesDir.resolve("$settingsFileName.bak").delete()

        val reopened = repository()

        assertFalse(reopened.status().policyAvailable)
        assertEquals(
            NotificationCaptureDecision.PolicyUnavailable,
            reopened.captureDecision("com.example.other"),
        )
    }

    @Test
    fun genuineFirstUseAtomicallyMaterializesDefaultsAndInitializationSeal() {
        deleteSettingsFiles()

        val first = repository()

        assertTrue(first.status().policyAvailable)
        assertEquals(
            NotificationCaptureDecision.Allowed,
            first.captureDecision("com.example.chat"),
        )
        assertTrue(context.noBackupFilesDir.resolve(settingsFileName).isFile)
        assertTrue(context.noBackupFilesDir.resolve("$settingsFileName.initialized").isFile)
        assertTrue(repository().status().policyAvailable)
    }

    @Test
    fun committedUpdateOutboxReplaysAfterProcessRecreationAndSupersedesStaleQueueExactlyOnce() {
        val queue = durableTestQueue()
        val ingress = NotificationTriageIngress(queue)
        val oldSignal = signalForKey("com.example.chat", "thread", "Old text", 1_000L)
        val firstStore = notificationStore()
        val oldStored = firstStore.accept(oldSignal) as NotificationWriteResult.Stored
        replayAndAcknowledge(firstStore, ingress)
        assertEquals(oldStored.sequence, queue.receipts().single().sourceSequence)

        val currentSignal = signalForKey("com.example.chat", "thread", "Current text", 2_000L)
        val currentStored = firstStore.accept(currentSignal) as NotificationWriteResult.Stored
        // Crash boundary: SQLite event + outbox committed, Queue/Center handoff not yet replayed.
        firstStore.close()

        val reopened = notificationStore()
        assertTrue(reopened.accept(currentSignal) is NotificationWriteResult.Duplicate)
        assertEquals(
            listOf(currentStored.sequence),
            reopened.pendingTriageOutbox().map { it.sequence },
        )
        replayAndAcknowledge(reopened, ingress)
        replayAndAcknowledge(reopened, ingress)

        assertTrue(reopened.pendingTriageOutbox().isEmpty())
        val pending = queue.receipts().filter {
            it.state == NotificationDeliveryState.PENDING_RESTRICTED_TRIAGE
        }
        assertEquals(1, pending.size)
        assertEquals(currentStored.sequence, pending.single().sourceSequence)
        assertEquals("Current text", requireNotNull(queue.claimNextRestrictedTriage()).notification.text)
        reopened.close()
    }

    @Test
    fun committedRemovalOutboxReplaysAfterProcessRecreationAndCancelsStaleQueue() {
        val queue = durableTestQueue()
        val ingress = NotificationTriageIngress(queue)
        val posted = signalForKey("com.example.chat", "thread", "Will be removed", 1_000L)
        val firstStore = notificationStore()
        firstStore.accept(posted)
        replayAndAcknowledge(firstStore, ingress)
        val removed = NotificationNormalizer.removed(
            packageName = "com.example.chat",
            androidKey = "thread",
            observedAtEpochMillis = 2_000L,
            reason = 7,
        )
        val removalStored = firstStore.accept(removed) as NotificationWriteResult.Stored
        firstStore.close()

        val reopened = notificationStore()
        assertEquals(
            listOf(removalStored.sequence),
            reopened.pendingTriageOutbox().map { it.sequence },
        )
        replayAndAcknowledge(reopened, ingress)

        assertTrue(reopened.pendingTriageOutbox().isEmpty())
        assertTrue(queue.claimNextRestrictedTriage() == null)
        assertEquals(NotificationDeliveryState.DISMISSED_BY_TRIAGE, queue.receipts().single().state)
        reopened.close()
    }

    @Test
    fun protectedAndExcludedPackagesNeverPersistAndExclusionPurgesExistingEvents() {
        val now = System.currentTimeMillis()
        val store = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
        )
        assertTrue(
            store.accept(signal("com.android.systemui", "system", now)) is
                NotificationWriteResult.ExcludedByPrivacy,
        )
        assertTrue(
            store.accept(signal("com.example.chat", "private", now + 1)) is
                NotificationWriteResult.Stored,
        )
        assertEquals(1, store.queryPage(limit = 10).events.size)

        val mutation = store.excludePackage("com.example.chat")

        assertEquals(1, mutation.removedEvents)
        assertEquals(1, triagePurger.purgeCalls)
        assertTrue(store.queryPage(limit = 10).events.isEmpty())
        assertTrue(
            store.accept(signal("com.example.chat", "later", now + 2)) is
                NotificationWriteResult.ExcludedByPrivacy,
        )
        store.close()
    }

    @Test
    fun retentionPrunesByAgeAndCountAndClearRemovesHistory() {
        val now = System.currentTimeMillis()
        val store = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
        )
        store.setRetention(maxEvents = 50, maxAgeHours = 1)
        store.accept(signal("com.example.chat", "old", now - 2 * 60 * 60 * 1_000L))
        repeat(55) { index ->
            store.accept(signal("com.example.chat", "new-$index", now + index + 1))
        }

        val retained = store.queryPage(limit = 200)
        assertEquals(50, retained.events.size)
        assertTrue(retained.events.none { it.snapshot.text == "old" })

        val clearsBeforeExplicitClear = triagePurger.clearCalls
        val clear = store.clearHistory()
        assertEquals(50, clear.removedInboxEvents)
        assertTrue(clear.triageQueueCleared)
        assertEquals(clearsBeforeExplicitClear + 1, triagePurger.clearCalls)
        assertTrue(store.queryPage(limit = 10).events.isEmpty())
        store.close()
    }

    @Test
    fun automaticCountPruneClearsQueueAndAnnouncementDataInsideDurableFence() {
        assertTrue(purgeFence.markClean())
        val store = NotificationInboxStore(
            context = context,
            retentionLimit = 1,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
        )
        val baselineClears = triagePurger.clearCalls

        assertTrue(store.accept(signal("com.example.chat", "first", 1L)) is NotificationWriteResult.Stored)
        assertTrue(store.accept(signal("com.example.chat", "second", 2L)) is NotificationWriteResult.Stored)

        assertEquals(baselineClears + 1, triagePurger.clearCalls)
        assertFalse(purgeFence.isRequired())
        assertEquals(listOf("second"), store.queryPage(limit = 10).events.map { it.snapshot.text })
        store.close()
    }

    @Test
    fun reconciledRemovalsRaiseFenceBeforeCountRetentionCanDeleteAnything() {
        assertTrue(purgeFence.markClean())
        val fenceRaised = CountDownLatch(1)
        val releaseRetention = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val store = NotificationInboxStore(
            context = context,
            retentionLimit = 2,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
            beforeRetentionMutation = {
                check(purgeFence.isRequired())
                fenceRaised.countDown()
                check(releaseRetention.await(2, TimeUnit.SECONDS))
            },
        )
        store.accept(signal("com.example.chat", "first", 1L))
        store.accept(signal("com.example.chat", "second", 2L))

        val reconciliation = Thread {
            runCatching {
                store.reconcileActiveKeys(emptySet(), observedAtEpochMillis = 3L)
            }.exceptionOrNull()?.let(failure::set)
        }
        reconciliation.start()
        assertTrue(fenceRaised.await(2, TimeUnit.SECONDS))
        assertTrue(purgeFence.isRequired())
        // Center/context/TTS all consult this same fence and therefore cannot expose stale data
        // while SQLite is about to remove rows for the two reconciled removals.
        releaseRetention.countDown()
        reconciliation.join(2_000)

        assertFalse(reconciliation.isAlive)
        assertEquals(null, failure.get())
        assertFalse(purgeFence.isRequired())
        assertEquals(1, triagePurger.clearCalls)
        store.close()
    }

    @Test
    fun ageRetentionIsEnforcedAtReadWithoutRequiringAnotherNotification() {
        var now = TimeUnit.HOURS.toMillis(10)
        assertTrue(purgeFence.markClean())
        val store = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
            clock = { now },
        )
        store.setRetention(maxEvents = 50, maxAgeHours = 1)
        val clearsAfterSettingPolicy = triagePurger.clearCalls
        assertTrue(
            store.accept(signal("com.example.chat", "expires", now)) is
                NotificationWriteResult.Stored,
        )
        assertEquals(1, store.queryPage(limit = 10).events.size)

        now += TimeUnit.HOURS.toMillis(2)

        assertTrue(store.queryPage(limit = 10).events.isEmpty())
        assertEquals(clearsAfterSettingPolicy + 1, triagePurger.clearCalls)
        assertFalse(purgeFence.isRequired())
        store.close()
    }

    @Test
    fun duplicateCallbackStillPrunesExpiredInboxAndAllDerivedStores() {
        var now = TimeUnit.HOURS.toMillis(10)
        assertTrue(purgeFence.markClean())
        val store = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
            clock = { now },
        )
        store.setRetention(maxEvents = 50, maxAgeHours = 1)
        val original = signal("com.example.chat", "same", now)
        assertTrue(store.accept(original) is NotificationWriteResult.Stored)
        val beforeExpiryPurge = triagePurger.clearCalls

        now += TimeUnit.HOURS.toMillis(2)
        assertTrue(store.accept(original) is NotificationWriteResult.Duplicate)

        assertTrue(store.queryPage(limit = 10).events.isEmpty())
        assertEquals(beforeExpiryPurge + 1, triagePurger.clearCalls)
        assertFalse(purgeFence.isRequired())
        store.close()
    }

    @Test
    fun snapshotReconcileAppliesRetentionEvenWhenNoAndroidKeyIsStale() {
        var now = TimeUnit.HOURS.toMillis(10)
        assertTrue(purgeFence.markClean())
        val store = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
            clock = { now },
        )
        store.setRetention(maxEvents = 50, maxAgeHours = 1)
        val observedAt = now
        assertTrue(
            store.accept(signal("com.example.chat", "active", observedAt)) is
                NotificationWriteResult.Stored,
        )
        val beforeExpiryPurge = triagePurger.clearCalls

        now += TimeUnit.HOURS.toMillis(2)
        assertTrue(
            store.reconcileActiveKeys(
                activeAndroidKeys = setOf("android-key-$observedAt"),
                observedAtEpochMillis = now,
            ).isEmpty(),
        )

        assertTrue(store.queryPage(limit = 10).events.isEmpty())
        assertEquals(beforeExpiryPurge + 1, triagePurger.clearCalls)
        assertFalse(purgeFence.isRequired())
        store.close()
    }

    @Test
    fun importingTighterRetentionPurgesQueueAndAnnouncementsNotOnlySqlite() {
        val now = System.currentTimeMillis()
        assertTrue(purgeFence.markClean())
        val store = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
        )
        assertTrue(
            store.accept(
                signal("com.example.chat", "old under new policy", now - TimeUnit.HOURS.toMillis(2)),
            ) is NotificationWriteResult.Stored,
        )
        val beforeImport = triagePurger.clearCalls
        val recovery = NotificationPrivacyRecoveryCodec.encode(
            NotificationPrivacySettings(
                retention = NotificationRetentionPolicy(maxEvents = 50, maxAgeHours = 1),
            ),
        )

        store.importPrivacySettings(recovery)

        assertEquals(beforeImport + 1, triagePurger.clearCalls)
        assertTrue(store.queryPage(limit = 10).events.isEmpty())
        assertFalse(purgeFence.isRequired())
        store.close()
    }

    @Test
    fun failedRetentionWidePurgeStaysFencedAcrossReopenUntilCombinedRetrySucceeds() {
        val first = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
        )
        triagePurger.clearSucceeds = false

        assertThrows(IllegalStateException::class.java) {
            first.setRetention(maxEvents = 50, maxAgeHours = 1)
        }
        assertTrue(purgeFence.isRequired())
        first.close()

        val reopenedFence = AtomicNotificationPrivacyPurgeFence(context, purgeFenceFileName)
        val repairedPurger = FakeTriageDataPurger()
        val reopened = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = repairedPurger,
            privacyPurgeFence = reopenedFence,
            factPrivacy = factPrivacy,
        )
        assertTrue(reopened.queryPage(limit = 10).events.isEmpty())
        // Queries remain fail-closed while REQUIRED; a write performs the complete destructive
        // retry before it can persist fresh notification content.
        assertTrue(
            reopened.accept(
                signal("com.example.chat", "fresh", System.currentTimeMillis()),
            ) is NotificationWriteResult.Stored,
        )
        assertEquals(1, repairedPurger.clearCalls)
        assertFalse(reopenedFence.isRequired())
        assertEquals(listOf("fresh"), reopened.queryPage(limit = 10).events.map { it.snapshot.text })
        reopened.close()
    }

    @Test
    fun privacyMutationNeverClaimsSuccessWhenDurableTriagePurgeFails() {
        val store = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
        )
        store.accept(signal("com.example.chat", "private", System.currentTimeMillis()))
        triagePurger.purgeSucceeds = false

        assertThrows(IllegalStateException::class.java) {
            store.excludePackage("com.example.chat")
        }
        assertEquals(1, triagePurger.purgeCalls)
        assertTrue(purgeFence.isRequired())

        triagePurger.clearSucceeds = false
        val clear = store.clearHistory()
        assertFalse(clear.triageQueueCleared)
        assertTrue(purgeFence.isRequired())
        store.close()
    }

    @Test
    fun exportedRecoveryContainsNoNotificationBodyKeyOrActionMetadata() {
        val store = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
        )
        store.accept(signal("com.example.chat", "top secret", System.currentTimeMillis()))
        store.excludePackage("com.example.other")

        val export = store.exportPrivacySettings()

        assertTrue(export.contains("com.example.other"))
        assertFalse(export.contains("top secret"))
        assertFalse(export.contains("android-key"))
        assertFalse(export.contains("actions"))
        store.close()
    }

    @Test
    fun policyCorruptionRejectsNewCaptureAndFailClosedClearsPriorHistory() {
        val store = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
        )
        store.accept(signal("com.example.chat", "private", System.currentTimeMillis()))
        // Materialize the settings file, then corrupt it as a crash/recovery fixture.
        store.setRetention(100, 24)
        context.noBackupFilesDir.resolve(settingsFileName).writeText("{broken")

        val result = store.accept(
            signal("com.example.other", "must not persist", System.currentTimeMillis() + 1),
        )

        assertEquals(
            NotificationWriteResult.ExcludedByPrivacy(
                NotificationCaptureDecision.PolicyUnavailable,
            ),
            result,
        )
        assertTrue(store.queryPage(limit = 10).events.isEmpty())
        store.close()
    }

    @Test
    fun policyCorruptionNeverMasksFailureToClearDurableTriageAndAnnouncements() {
        val store = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
        )
        store.setRetention(100, 24)
        context.noBackupFilesDir.resolve(settingsFileName).writeText("{broken")
        triagePurger.clearSucceeds = false
        val clearsBeforeCorruptCapture = triagePurger.clearCalls

        assertThrows(IllegalStateException::class.java) {
            store.accept(
                signal("com.example.other", "must not persist", System.currentTimeMillis()),
            )
        }
        assertEquals(clearsBeforeCorruptCapture + 1, triagePurger.clearCalls)
        store.close()
    }

    @Test
    fun unavailablePolicyQueryAndConcurrentCaptureUseOneLockOrderWithoutDeadlock() {
        val queryEntered = CountDownLatch(1)
        val releaseQuery = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val store = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
            afterQueryPrivacyBoundaryAcquired = {
                queryEntered.countDown()
                check(releaseQuery.await(2, TimeUnit.SECONDS))
            },
        )
        store.setRetention(100, 24)
        context.noBackupFilesDir.resolve(settingsFileName).writeText("{broken")

        val query = Thread {
            runCatching { store.queryPage(limit = 10) }
                .exceptionOrNull()
                ?.let(failure::set)
        }
        val capture = Thread {
            runCatching {
                store.accept(
                    signal("com.example.chat", "must not persist", System.currentTimeMillis()),
                )
            }.exceptionOrNull()?.let(failure::set)
        }

        query.start()
        assertTrue(queryEntered.await(2, TimeUnit.SECONDS))
        capture.start()
        releaseQuery.countDown()
        query.join(2_000)
        capture.join(2_000)

        assertFalse("query deadlocked on the store monitor", query.isAlive)
        assertFalse("capture deadlocked on the privacy boundary", capture.isAlive)
        assertEquals(null, failure.get())
        store.close()
    }

    @Test
    fun exclusionCannotBeOvertakenByALateAllowedCaptureWrite() {
        val now = System.currentTimeMillis()
        val allowedRead = CountDownLatch(1)
        val releaseCapture = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val captureStore = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
            afterAllowedCaptureDecision = {
                allowedRead.countDown()
                check(releaseCapture.await(2, TimeUnit.SECONDS))
            },
        )
        val privacyStore = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
        )
        val accept = Thread {
            runCatching {
                captureStore.accept(signal("com.example.chat", "private", now))
            }.exceptionOrNull()?.let(failure::set)
        }
        val exclusionFinished = CountDownLatch(1)
        val exclude = Thread {
            runCatching { privacyStore.excludePackage("com.example.chat") }
                .exceptionOrNull()?.let(failure::set)
            exclusionFinished.countDown()
        }

        accept.start()
        assertTrue(allowedRead.await(2, TimeUnit.SECONDS))
        exclude.start()
        assertFalse(exclusionFinished.await(100, TimeUnit.MILLISECONDS))
        releaseCapture.countDown()
        accept.join(2_000)
        exclude.join(2_000)

        assertEquals(null, failure.get())
        assertTrue(captureStore.queryPage(limit = 10).events.isEmpty())
        captureStore.close()
        privacyStore.close()
        val reopened = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
        )
        assertTrue(reopened.queryPage(limit = 10).events.isEmpty())
        reopened.close()
    }

    @Test
    fun exclusionCannotBeOvertakenByALateReconciledRemovalWrite() {
        val now = System.currentTimeMillis()
        NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
        ).use { initial ->
            initial.accept(signal("com.example.chat", "private", now))
        }
        val reconcileChecked = CountDownLatch(1)
        val releaseReconcile = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val reconcileStore = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
            afterReconcilePolicyCheck = {
                reconcileChecked.countDown()
                check(releaseReconcile.await(2, TimeUnit.SECONDS))
            },
        )
        val privacyStore = NotificationInboxStore(
            context = context,
            databaseName = databaseName,
            privacyRepository = repository(),
            triageDataPurger = triagePurger,
            privacyPurgeFence = purgeFence,
            factPrivacy = factPrivacy,
        )
        val reconcile = Thread {
            runCatching { reconcileStore.reconcileActiveKeys(emptySet(), now + 1) }
                .exceptionOrNull()?.let(failure::set)
        }
        val exclusionFinished = CountDownLatch(1)
        val exclude = Thread {
            runCatching { privacyStore.excludePackage("com.example.chat") }
                .exceptionOrNull()?.let(failure::set)
            exclusionFinished.countDown()
        }

        reconcile.start()
        assertTrue(reconcileChecked.await(2, TimeUnit.SECONDS))
        exclude.start()
        assertFalse(exclusionFinished.await(100, TimeUnit.MILLISECONDS))
        releaseReconcile.countDown()
        reconcile.join(2_000)
        exclude.join(2_000)

        assertEquals(null, failure.get())
        assertTrue(reconcileStore.queryPage(limit = 10).events.isEmpty())
        reconcileStore.close()
        privacyStore.close()
    }

    private fun repository() = NotificationPrivacyRepository(
        storage = AtomicFileNotificationPrivacySettingsStorage(context, settingsFileName),
        ownPackageName = context.packageName,
    )

    private fun notificationStore() = NotificationInboxStore(
        context = context,
        databaseName = databaseName,
        privacyRepository = repository(),
        triageDataPurger = triagePurger,
        privacyPurgeFence = purgeFence,
        factPrivacy = factPrivacy,
        clock = { 10_000L },
    )

    private fun durableTestQueue() = NotificationTriageQueue(
        storage = MemoryTriageStorage(),
        exclusionPolicy = HansNotificationExclusionPolicy(setOf(context.packageName)),
        clock = { 10_000L },
    )

    private fun replayAndAcknowledge(
        store: NotificationInboxStore,
        ingress: NotificationTriageIngress,
    ) {
        store.pendingTriageOutbox().forEach { event ->
            val signal = if (event.kind == NotificationEventKind.REMOVED) {
                NotificationSignal.Removed(
                    event.snapshot.packageName,
                    event.snapshot.androidKey,
                    event.observedAtEpochMillis,
                    event.removalReason,
                )
            } else {
                NotificationSignal.Upsert(event.snapshot, event.observedAtEpochMillis)
            }
            ingress.afterInboxWrite(
                signal,
                NotificationWriteResult.Stored(event.sequence, event.kind),
            )
            assertTrue(store.acknowledgeTriageOutbox(event.sequence))
        }
    }

    private fun signalForKey(
        packageName: String,
        androidKey: String,
        text: String,
        observedAt: Long,
    ) = NotificationNormalizer.upsert(
        raw = RawNotificationSnapshot(
            packageName = packageName,
            androidKey = androidKey,
            postTimeEpochMillis = observedAt,
            notificationWhenEpochMillis = observedAt,
            title = "Title",
            text = text,
            subtext = "Subtext",
            category = "message",
            channelId = "messages",
            ongoing = false,
            clearable = true,
            actions = emptyList(),
        ),
        observedAtEpochMillis = observedAt,
    )

    private fun deleteSettingsFiles() {
        listOf(
            settingsFileName,
            "$settingsFileName.bak",
            "$settingsFileName.new",
            "$settingsFileName.initialized",
            "$settingsFileName.initialized.bak",
            "$settingsFileName.initialized.new",
        ).forEach { relativePath -> context.noBackupFilesDir.resolve(relativePath).delete() }
    }

    private fun deletePurgeFenceFiles() {
        val base = context.noBackupFilesDir.resolve(purgeFenceFileName)
        base.delete()
        context.noBackupFilesDir.resolve("${purgeFenceFileName}.bak").delete()
        context.noBackupFilesDir.resolve("${purgeFenceFileName}.new").delete()
    }

    private fun signal(packageName: String, text: String, observedAt: Long) =
        NotificationNormalizer.upsert(
            raw = RawNotificationSnapshot(
                packageName = packageName,
                androidKey = "android-key-$observedAt",
                postTimeEpochMillis = observedAt,
                notificationWhenEpochMillis = observedAt,
                title = "Title",
                text = text,
                subtext = "Subtext",
                category = "message",
                channelId = "messages",
                ongoing = false,
                clearable = true,
                actions = emptyList(),
            ),
            observedAtEpochMillis = observedAt,
        )

    private class FakeTriageDataPurger : NotificationTriageDataPurger {
        var clearCalls = 0
        var purgeCalls = 0
        var clearSucceeds = true
        var purgeSucceeds = true

        override fun clearAll(): Boolean {
            clearCalls += 1
            return clearSucceeds
        }

        override fun purgeExcluded(): Boolean {
            purgeCalls += 1
            return purgeSucceeds
        }
    }

    private class MemoryTriageStorage : NotificationTriageStorage {
        private var state = NotificationTriageQueueState(emptyList())
        override fun read(): NotificationTriageQueueState = state
        override fun write(state: NotificationTriageQueueState) {
            this.state = state
        }
    }
}
