package ai.hans.standard.phone.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyBeginResult
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyIntent
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyPort
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyRequest
import ai.hans.standard.phone.notifications.facts.NotificationFactPrivacyScope
import ai.hans.standard.phone.notifications.facts.NotificationFactUnavailableReason
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Hook/orchestration tests. The separate repository suite proves real archive SQLite durability. */
@RunWith(AndroidJUnit4::class)
class NotificationFactPrivacyHooksTest {
    private lateinit var context: Context
    private lateinit var settingsName: String
    private lateinit var fenceName: String
    private lateinit var databaseName: String
    private lateinit var fence: NotificationPrivacyPurgeFence
    private lateinit var facts: RecordingNotificationFactPrivacyPort
    private lateinit var triage: RecordingTransientPurger
    private val stores = mutableListOf<NotificationInboxStore>()
    private val now = 20_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val suffix = UUID.randomUUID().toString()
        settingsName = "notification-fact-hooks-" + suffix + ".json"
        fenceName = "notification-fact-hooks-" + suffix + ".fence"
        databaseName = "notification-fact-hooks-" + suffix + ".db"
        fence = AtomicNotificationPrivacyPurgeFence(context, fenceName)
        assertTrue(fence.markClean())
        facts = RecordingNotificationFactPrivacyPort()
        triage = RecordingTransientPurger(facts.actions)
    }

    @After
    fun tearDown() {
        stores.forEach(NotificationInboxStore::close)
        stores.clear()
        context.deleteDatabase(databaseName)
        listOf(settingsName, settingsName + ".initialized", fenceName).forEach { name ->
            listOf(name, name + ".bak", name + ".new").forEach {
                context.noBackupFilesDir.resolve(it).delete()
            }
        }
    }

    @Test
    fun transientClearRetentionAndSwipeNeverForgetFactsOrValidatedCandidates() {
        val store = store(retentionLimit = 1)
        seedArchive()
        store.accept(signal(CHAT, "first", now))
        store.accept(signal(CHAT, "second", now + 1))
        store.accept(NotificationNormalizer.removed(
            packageName = CHAT,
            androidKey = "key-" + (now + 1),
            observedAtEpochMillis = now + 2,
            reason = 1,
        ))
        store.setRetention(maxEvents = 50, maxAgeHours = 1)

        assertTrue(store.clearHistory().triageQueueCleared)

        assertEquals(setOf("fact_chat", "fact_other"), facts.factIds)
        assertEquals(setOf("fact_chat", "fact_other"), facts.candidateFactIds)
        assertTrue(facts.begunScopes.isEmpty())
        assertTrue(triage.clearCalls > 0)
    }

    @Test
    fun exclusionPurgesArchiveAndCandidatesEvenWhenNoInboxRowsRemain() {
        val store = store()
        seedArchive()
        facts.onBegin = {
            assertTrue(Thread.holdsLock(NotificationPrivacyMutationCoordinator.lock))
            assertTrue(fence.isRequired())
            assertEquals(NotificationCaptureDecision.UserExcludedPackage, repository().captureDecision(CHAT))
        }

        val result = store.excludePackage(CHAT)

        assertEquals(0, result.removedEvents)
        assertEquals(setOf("fact_other"), facts.factIds)
        assertEquals(setOf("fact_other"), facts.candidateFactIds)
        assertEquals(listOf(NotificationFactPrivacyScope.Package(CHAT)), facts.begunScopes)
        assertTrue(facts.pending().isEmpty())
        assertFalse(fence.isRequired())
    }

    @Test
    fun combinedExclusionAndTighterRetentionImportStillPurgesArchiveOnlyPackage() {
        val store = store()
        seedArchive()
        val document = NotificationPrivacyRecoveryCodec.encode(NotificationPrivacySettings(
            excludedPackages = setOf(CHAT),
            retention = NotificationRetentionPolicy(maxEvents = 50, maxAgeHours = 1),
        ))

        val result = store.importPrivacySettings(document)

        assertEquals(setOf(CHAT), result.settings.excludedPackages)
        assertTrue(triage.clearCalls > 0)
        assertEquals(setOf("fact_other"), facts.factIds)
        assertEquals(setOf("fact_other"), facts.candidateFactIds)
        assertTrue(facts.begunScopes.contains(NotificationFactPrivacyScope.Package(CHAT)))
        assertFalse(fence.isRequired())
    }

    @Test
    fun explicitAllDataClearCommitsArchiveBeforeCandidatePurgeAndAck() {
        val store = store()
        store.accept(signal(CHAT, "remembered event", now))
        seedArchive()
        facts.onCandidatePurge = {
            assertTrue(Thread.holdsLock(NotificationPrivacyMutationCoordinator.lock))
            assertTrue(fence.isRequired())
            assertTrue(facts.factIds.isEmpty())
            assertEquals(0, triage.clearCalls)
        }

        val result = store.clearAllNotificationData()

        assertTrue(result.completed)
        assertEquals(1, result.removedInboxEvents)
        assertTrue(store.queryPage().events.isEmpty())
        assertTrue(facts.factIds.isEmpty())
        assertTrue(facts.candidateFactIds.isEmpty())
        assertTrue(facts.pending().isEmpty())
        assertFalse(fence.isRequired())
        assertTrue(facts.actions.indexOf("archive.begin.all") <
            facts.actions.indexOf("candidate.purge.all"))
        assertTrue(facts.actions.indexOf("candidate.purge.all") <
            facts.actions.indexOf("triage.clear"))
        assertTrue(facts.actions.indexOf("triage.clear") <
            facts.actions.indexOf("archive.ack.all"))
    }

    @Test
    fun explicitAllRepairsUnknownQueueWithFreshFence() {
        assertExplicitAllRepairsUnknownQueue(rawFenceAlreadyRequired = false)
    }

    @Test
    fun explicitAllRepairsUnknownQueueWithAlreadyRequiredFence() {
        assertExplicitAllRepairsUnknownQueue(rawFenceAlreadyRequired = true)
    }

    @Test
    fun startupRepairsPendingAllBeforeEarlierScopedIntentAndRawRecovery() {
        val first = store()
        first.accept(signal(CHAT, "pending raw event", now))
        seedArchive()
        facts.begin(NotificationFactPrivacyRequest(
            UUID.randomUUID().toString(), NotificationFactPrivacyScope.Package(CHAT),
        ))
        facts.begin(NotificationFactPrivacyRequest(
            UUID.randomUUID().toString(), NotificationFactPrivacyScope.All,
        ))
        assertTrue(fence.markRequired())
        makeQueueUnreadableUntilExplicitAllPurge()
        first.close()

        val reopened = store()
        assertTrue(reopened.recoverFactPrivacyMutations())

        assertTrue(triage.queueReadable)
        assertTrue(reopened.queryPage().events.isEmpty())
        assertTrue(facts.factIds.isEmpty())
        assertTrue(facts.candidateFactIds.isEmpty())
        assertTrue(facts.pending().isEmpty())
        assertFalse(fence.isRequired())
        assertEquals(listOf(NotificationFactPrivacyScope.Package(CHAT), NotificationFactPrivacyScope.All),
            facts.begunScopes)
        assertTrue(facts.actions.indexOf("candidate.purge.all") <
            facts.actions.indexOf("triage.clear"))
        assertTrue(facts.actions.indexOf("candidate.purge.all") <
            facts.actions.indexOf("candidate.purge.package"))
    }

    @Test
    fun packageMutationCanFinishAnAlreadyAuthorizedPendingAllRepairFirst() {
        val store = store()
        store.accept(signal(CHAT, "pending raw event", now))
        seedArchive()
        facts.begin(NotificationFactPrivacyRequest(
            UUID.randomUUID().toString(), NotificationFactPrivacyScope.All,
        ))
        assertTrue(fence.markRequired())
        makeQueueUnreadableUntilExplicitAllPurge()

        store.excludePackage(CHAT)

        assertTrue(triage.queueReadable)
        assertTrue(facts.pending().isEmpty())
        assertFalse(fence.isRequired())
        assertEquals(NotificationCaptureDecision.UserExcludedPackage, repository().captureDecision(CHAT))
        assertEquals(listOf(NotificationFactPrivacyScope.All, NotificationFactPrivacyScope.Package(CHAT)),
            facts.begunScopes)
        assertTrue(facts.actions.indexOf("candidate.purge.all") <
            facts.actions.indexOf("triage.clear"))
        assertTrue(facts.actions.indexOf("archive.ack.all") <
            facts.actions.indexOf("archive.begin.package"))
    }

    @Test
    fun startupPackageForgetDoesNotGloballyRepairUnknownQueue() {
        assertUnknownQueueDoesNotWidenScope(NotificationFactPrivacyScope.Package(CHAT))
    }

    @Test
    fun startupSourceForgetDoesNotGloballyRepairUnknownQueue() {
        assertUnknownQueueDoesNotWidenScope(NotificationFactPrivacyScope.Source(CHAT, "source_chat"))
    }

    @Test
    fun startupFactForgetDoesNotGloballyRepairUnknownQueue() {
        assertUnknownQueueDoesNotWidenScope(NotificationFactPrivacyScope.Fact("fact_chat", 1))
    }

    @Test
    fun allCandidatePurgeFailureDoesNotBeginRawClearOrAck() {
        val store = store()
        store.accept(signal(CHAT, "pending raw event", now))
        seedArchive()
        facts.candidatePurgeSucceeds = false

        assertThrows(IllegalStateException::class.java) { store.clearAllNotificationData() }

        assertEquals(0, triage.clearCalls)
        assertTrue(facts.factIds.isEmpty())
        assertEquals(setOf("fact_chat", "fact_other"), facts.candidateFactIds)
        assertEquals(1, facts.pending().size)
        assertFalse(facts.actions.contains("archive.ack.all"))
        assertTrue(fence.isRequired())
    }

    @Test
    fun allRawClearFailureRetainsIntentAfterSuccessfulCandidatePurge() {
        val store = store()
        seedArchive()
        triage.clearSucceeds = false

        assertThrows(IllegalStateException::class.java) { store.clearAllNotificationData() }

        assertTrue(facts.factIds.isEmpty())
        assertTrue(facts.candidateFactIds.isEmpty())
        assertEquals(1, facts.pending().size)
        assertFalse(facts.actions.contains("archive.ack.all"))
        assertTrue(fence.isRequired())

        triage.clearSucceeds = true
        assertTrue(store.recoverFactPrivacyMutations())
        assertTrue(facts.pending().isEmpty())
        assertFalse(fence.isRequired())
    }

    @Test
    fun reopenedInboxRetriesPendingPurgeWithoutRecreatingArchiveFacts() {
        val first = store()
        seedArchive()
        facts.candidatePurgeSucceeds = false

        assertThrows(IllegalStateException::class.java) { first.excludePackage(CHAT) }
        assertEquals(setOf("fact_other"), facts.factIds)
        assertTrue(facts.candidateFactIds.contains("fact_chat"))
        assertEquals(1, facts.pending().size)
        assertTrue(fence.isRequired())
        val generationAfterBegin = facts.packageGenerations.getValue(CHAT)
        first.close()

        facts.candidatePurgeSucceeds = true
        val reopened = store()
        assertTrue(reopened.recoverFactPrivacyMutations())

        assertEquals(setOf("fact_other"), facts.factIds)
        assertEquals(setOf("fact_other"), facts.candidateFactIds)
        assertTrue(facts.pending().isEmpty())
        assertEquals(generationAfterBegin, facts.packageGenerations.getValue(CHAT))
        assertFalse(fence.isRequired())
    }

    @Test
    fun failedArchiveBeginCannotBeBypassedByIncludingPackageAgain() {
        val store = store()
        seedArchive()
        facts.beginSucceeds = false

        assertThrows(IllegalStateException::class.java) { store.excludePackage(CHAT) }
        assertEquals(NotificationCaptureDecision.UserExcludedPackage, repository().captureDecision(CHAT))
        assertTrue(facts.pending().isEmpty())
        assertTrue(facts.factIds.contains("fact_chat"))
        assertTrue(fence.isRequired())

        assertThrows(IllegalStateException::class.java) { store.includePackage(CHAT) }
        assertEquals(NotificationCaptureDecision.UserExcludedPackage, repository().captureDecision(CHAT))

        facts.beginSucceeds = true
        store.includePackage(CHAT)
        assertEquals(NotificationCaptureDecision.Allowed, repository().captureDecision(CHAT))
        assertEquals(setOf("fact_other"), facts.factIds)
        assertEquals(setOf("fact_other"), facts.candidateFactIds)
        assertTrue(facts.packageGenerations.getValue(CHAT) > 0L)
        assertFalse(fence.isRequired())
    }

    @Test
    fun startupRepairsPersistedExclusionEvenWithoutInboxRowsOrArchiveIntent() {
        seedArchive()
        repository().excludePackage(CHAT)
        assertTrue(fence.markRequired())
        assertTrue(facts.pending().isEmpty())

        assertTrue(store().recoverFactPrivacyMutations())

        assertEquals(setOf("fact_other"), facts.factIds)
        assertEquals(setOf("fact_other"), facts.candidateFactIds)
        assertTrue(facts.begunScopes.contains(NotificationFactPrivacyScope.Package(CHAT)))
        assertFalse(fence.isRequired())
    }

    @Test
    fun importedPolicyCannotReincludeUnfinishedExclusionBeforeArchivePurge() {
        val store = store()
        seedArchive()
        facts.beginSucceeds = false
        assertThrows(IllegalStateException::class.java) { store.excludePackage(CHAT) }
        facts.beginSucceeds = true

        val result = store.importPrivacySettings(
            NotificationPrivacyRecoveryCodec.encode(NotificationPrivacySettings()),
        )

        assertTrue(result.settings.excludedPackages.isEmpty())
        assertEquals(NotificationCaptureDecision.Allowed, repository().captureDecision(CHAT))
        assertEquals(setOf("fact_other"), facts.factIds)
        assertEquals(setOf("fact_other"), facts.candidateFactIds)
        assertFalse(fence.isRequired())
    }

    @Test
    fun factOnlyRecoveryDoesNotClearRawInboxOrRaiseRawFence() {
        assertArchiveOnlyRecovery(NotificationFactPrivacyScope.Fact("fact_chat", expectedRevision = 1))
    }

    @Test
    fun sourceOnlyRecoveryDoesNotClearRawInboxOrRaiseRawFence() {
        assertArchiveOnlyRecovery(NotificationFactPrivacyScope.Source(CHAT, "source_chat"))
    }

    @Test
    fun unknownPolicyDoesNotBecomeImplicitAllDataForget() {
        val store = store()
        store.accept(signal(CHAT, "raw event", now))
        seedArchive()
        repository().setRetention(100, 24)
        context.noBackupFilesDir.resolve(settingsName).writeText("{broken")
        assertTrue(fence.markRequired())

        assertFalse(store.recoverFactPrivacyMutations())

        assertTrue(facts.begunScopes.isEmpty())
        assertEquals(setOf("fact_chat", "fact_other"), facts.factIds)
        assertEquals(setOf("fact_chat", "fact_other"), facts.candidateFactIds)
        assertTrue(fence.isRequired())
    }

    @Test
    fun failedAckNeverFinishesAllDataPurge() {
        val store = store()
        seedArchive()
        facts.ackSucceeds = false

        assertThrows(IllegalStateException::class.java) { store.clearAllNotificationData() }

        assertTrue(facts.factIds.isEmpty())
        assertTrue(facts.candidateFactIds.isEmpty())
        assertEquals(1, facts.pending().size)
        assertTrue(fence.isRequired())
    }

    @Test
    fun successfulAckWithoutDurableRemovalNeverFinishesAllDataPurge() {
        val store = store()
        seedArchive()
        facts.ackRemovesIntent = false

        assertThrows(IllegalStateException::class.java) { store.clearAllNotificationData() }

        assertEquals(1, facts.pending().size)
        assertTrue(fence.isRequired())
    }

    @Test
    fun unreadableArchiveIntentStateCannotBeTreatedAsAnEmptyCompletedPurge() {
        val store = store()
        seedArchive()
        facts.pendingReadable = false

        assertThrows(IllegalStateException::class.java) { store.clearAllNotificationData() }

        assertTrue(fence.isRequired())
        assertEquals(setOf("fact_chat", "fact_other"), facts.factIds)
    }

    private fun assertExplicitAllRepairsUnknownQueue(rawFenceAlreadyRequired: Boolean) {
        val store = store()
        store.accept(signal(CHAT, "raw event awaiting explicit forget", now))
        seedArchive()
        if (rawFenceAlreadyRequired) assertTrue(fence.markRequired())
        makeQueueUnreadableUntilExplicitAllPurge()
        facts.onBegin = {
            assertEquals(NotificationFactPrivacyScope.All, it.scope)
            assertTrue(fence.isRequired())
            assertEquals(0, triage.clearCalls)
            assertFalse(triage.queueReadable)
        }

        val result = store.clearAllNotificationData()

        assertTrue(result.completed)
        assertEquals(1, result.removedInboxEvents)
        assertTrue(triage.queueReadable)
        assertTrue(store.queryPage().events.isEmpty())
        assertTrue(facts.factIds.isEmpty())
        assertTrue(facts.candidateFactIds.isEmpty())
        assertTrue(facts.pending().isEmpty())
        assertFalse(fence.isRequired())
        assertEquals(listOf(NotificationFactPrivacyScope.All), facts.begunScopes)
        assertTrue(facts.actions.indexOf("archive.begin.all") <
            facts.actions.indexOf("candidate.purge.all"))
        assertTrue(facts.actions.indexOf("candidate.purge.all") <
            facts.actions.indexOf("triage.clear"))
        assertTrue(facts.actions.indexOf("triage.clear") <
            facts.actions.indexOf("archive.ack.all"))
    }

    private fun assertUnknownQueueDoesNotWidenScope(scope: NotificationFactPrivacyScope) {
        val store = store()
        seedArchive()
        facts.begin(NotificationFactPrivacyRequest(UUID.randomUUID().toString(), scope))
        if (scope is NotificationFactPrivacyScope.Package) assertTrue(fence.markRequired())
        makeQueueUnreadableUntilExplicitAllPurge()

        assertFalse(store.recoverFactPrivacyMutations())

        assertFalse(triage.queueReadable)
        assertEquals(listOf(scope), facts.begunScopes)
        assertEquals(1, facts.pending().size)
        assertEquals(setOf("fact_other"), facts.factIds)
        assertEquals(setOf("fact_chat", "fact_other"), facts.candidateFactIds)
        assertFalse(facts.actions.contains("candidate.purge.all"))
        assertTrue(facts.actions.none { it.startsWith("archive.ack.") })
        if (scope !is NotificationFactPrivacyScope.Package) {
            assertEquals(0, triage.clearCalls)
            assertFalse(fence.isRequired())
        }
    }

    /** Models Queue-v4's strict read failure: only an already durable All intent may repair it. */
    private fun makeQueueUnreadableUntilExplicitAllPurge() {
        triage.queueReadable = false
        facts.candidatePurgeAllowed = { intent ->
            if (intent.scope == NotificationFactPrivacyScope.All) {
                assertTrue(facts.pending().contains(intent))
                assertTrue(facts.factIds.isEmpty())
                assertTrue(fence.isRequired())
                triage.queueReadable = true
            }
            triage.queueReadable
        }
    }

    private fun assertArchiveOnlyRecovery(scope: NotificationFactPrivacyScope) {
        val store = store()
        store.accept(signal(CHAT, "raw event stays", now))
        seedArchive()
        facts.begin(NotificationFactPrivacyRequest(UUID.randomUUID().toString(), scope))
        val clearCallsBefore = triage.clearCalls
        facts.onCandidatePurge = {
            assertFalse(fence.isRequired())
            assertEquals(clearCallsBefore, triage.clearCalls)
        }

        assertTrue(store.recoverFactPrivacyMutations())

        assertEquals(listOf("raw event stays"), store.queryPage().events.map { it.snapshot.text })
        assertEquals(clearCallsBefore, triage.clearCalls)
        assertFalse(fence.isRequired())
        assertEquals(setOf("fact_other"), facts.factIds)
        assertEquals(setOf("fact_other"), facts.candidateFactIds)
        assertTrue(facts.pending().isEmpty())
        assertEquals(listOf(scope), facts.begunScopes)
    }

    private fun seedArchive() {
        facts.seed(CHAT, "fact_chat", "source_chat")
        facts.seed(OTHER, "fact_other", "source_other")
    }

    private fun repository() = NotificationPrivacyRepository(
        storage = AtomicFileNotificationPrivacySettingsStorage(context, settingsName),
        ownPackageName = context.packageName,
    )

    private fun store(retentionLimit: Int? = null) = NotificationInboxStore(
        context = context,
        databaseName = databaseName,
        retentionLimit = retentionLimit,
        privacyRepository = repository(),
        triageDataPurger = triage,
        privacyPurgeFence = fence,
        factPrivacy = facts,
        clock = { now },
    ).also(stores::add)

    private fun signal(packageName: String, text: String, observedAt: Long) =
        NotificationNormalizer.upsert(
            RawNotificationSnapshot(
                packageName = packageName,
                androidKey = "key-" + observedAt,
                postTimeEpochMillis = observedAt,
                notificationWhenEpochMillis = observedAt,
                title = "Event",
                text = text,
                subtext = null,
                category = "message",
                channelId = "messages",
                ongoing = false,
                clearable = true,
                actions = emptyList(),
            ),
            observedAtEpochMillis = observedAt,
        )

    private class RecordingTransientPurger(private val actions: MutableList<String>) :
        NotificationTriageDataPurger {
        var clearCalls = 0
        var queueReadable = true
        var clearSucceeds = true

        override fun clearAll(): Boolean {
            clearCalls += 1
            actions += "triage.clear"
            return queueReadable && clearSucceeds
        }

        override fun purgeExcluded(): Boolean {
            actions += "triage.purgeExcluded"
            return queueReadable
        }
    }

    private companion object {
        const val CHAT = "com.example.chat"
        const val OTHER = "com.example.other"
    }
}

/**
 * Explicit test double: inbox tests do not silently access the production archive database.
 * The same instance may survive reopening inbox/policy/fence fixtures to model a pending journal.
 */
internal class RecordingNotificationFactPrivacyPort : NotificationFactPrivacyPort {
    private data class Entry(val packageName: String, val factId: String, val sourceRef: String)
    private val facts = mutableListOf<Entry>()
    private val candidates = mutableListOf<Entry>()
    private val intents = linkedMapOf<String, NotificationFactPrivacyIntent>()
    private val completed = linkedMapOf<String, NotificationFactPrivacyIntent>()
    private val verifiedCandidatePurges = mutableSetOf<String>()
    private val storeEpoch = UUID.randomUUID().toString()
    private var allGeneration = 0L
    val packageGenerations = mutableMapOf<String, Long>()
    val actions = mutableListOf<String>()
    val begunScopes = mutableListOf<NotificationFactPrivacyScope>()
    var beginSucceeds = true
    var pendingReadable = true
    var candidatePurgeSucceeds = true
    var ackSucceeds = true
    var ackRemovesIntent = true
    var onBegin: (NotificationFactPrivacyRequest) -> Unit = {}
    var onCandidatePurge: (NotificationFactPrivacyIntent) -> Unit = {}
    var candidatePurgeAllowed: (NotificationFactPrivacyIntent) -> Boolean = { true }
    val factIds: Set<String> get() = facts.map { it.factId }.toSet()
    val candidateFactIds: Set<String> get() = candidates.map { it.factId }.toSet()

    fun seed(packageName: String, factId: String, sourceRef: String) {
        val entry = Entry(packageName, factId, sourceRef)
        facts += entry
        candidates += entry
    }

    override fun begin(request: NotificationFactPrivacyRequest): NotificationFactPrivacyBeginResult {
        actions += "archive.begin." + label(request.scope)
        begunScopes += request.scope
        onBegin(request)
        if (!beginSucceeds) {
            return NotificationFactPrivacyBeginResult.Unavailable(NotificationFactUnavailableReason.IO_FAILURE)
        }
        intents[request.mutationId]?.let {
            return if (it.scope == request.scope) NotificationFactPrivacyBeginResult.Pending(it, replay = true)
                else NotificationFactPrivacyBeginResult.Conflict(NotificationFactPrivacyBeginResult.Reason.ID_CONFLICT)
        }
        completed[request.mutationId]?.let {
            return if (it.scope == request.scope) NotificationFactPrivacyBeginResult.Completed(it)
                else NotificationFactPrivacyBeginResult.Conflict(NotificationFactPrivacyBeginResult.Reason.ID_CONFLICT)
        }
        val affected = when (val scope = request.scope) {
            is NotificationFactPrivacyScope.Fact -> facts.firstOrNull { it.factId == scope.factId }
                ?: return NotificationFactPrivacyBeginResult.Conflict(NotificationFactPrivacyBeginResult.Reason.NOT_FOUND)
            else -> null
        }
        val packageName = when (val scope = request.scope) {
            is NotificationFactPrivacyScope.Package -> scope.packageName
            is NotificationFactPrivacyScope.Source -> scope.packageName
            is NotificationFactPrivacyScope.Fact -> affected?.packageName
            NotificationFactPrivacyScope.All -> null
        }
        val sourceRef = when (val scope = request.scope) {
            is NotificationFactPrivacyScope.Source -> scope.sourceRef
            is NotificationFactPrivacyScope.Fact -> affected?.sourceRef
            else -> null
        }
        if (request.scope == NotificationFactPrivacyScope.All) allGeneration += 1
        if (request.scope is NotificationFactPrivacyScope.Package) {
            packageName?.let { packageGenerations[it] = (packageGenerations[it] ?: 0) + 1 }
        }
        val before = facts.size
        facts.removeAll { matches(request.scope, it) }
        val intent = NotificationFactPrivacyIntent(
            storeEpoch = storeEpoch,
            mutationId = request.mutationId,
            scope = request.scope,
            affectedPackageName = packageName,
            affectedSourceRef = sourceRef,
            allGeneration = allGeneration,
            packageGeneration = packageName?.let { packageGenerations[it] ?: 0L },
            removedFacts = before - facts.size,
        )
        intents[request.mutationId] = intent
        return NotificationFactPrivacyBeginResult.Pending(intent)
    }

    override fun pending(): List<NotificationFactPrivacyIntent> {
        check(pendingReadable) { "test_archive_pending_unavailable" }
        return intents.values.toList()
    }

    override fun purgeCandidateOutbox(intent: NotificationFactPrivacyIntent): Boolean {
        actions += "candidate.purge." + label(intent.scope)
        onCandidatePurge(intent)
        if (!candidatePurgeSucceeds || !candidatePurgeAllowed(intent)) return false
        candidates.removeAll { matches(intent.scope, it) }
        verifiedCandidatePurges += intent.mutationId
        return true
    }

    override fun ack(intent: NotificationFactPrivacyIntent): Boolean {
        actions += "archive.ack." + label(intent.scope)
        if (!ackSucceeds || intent.mutationId !in verifiedCandidatePurges) return false
        if (completed[intent.mutationId] == intent) return true
        if (intents[intent.mutationId] != intent) return false
        if (ackRemovesIntent) {
            intents.remove(intent.mutationId)
            completed[intent.mutationId] = intent
        }
        return true
    }

    private fun matches(scope: NotificationFactPrivacyScope, entry: Entry): Boolean = when (scope) {
        NotificationFactPrivacyScope.All -> true
        is NotificationFactPrivacyScope.Package -> entry.packageName == scope.packageName
        is NotificationFactPrivacyScope.Source ->
            entry.packageName == scope.packageName && entry.sourceRef == scope.sourceRef
        is NotificationFactPrivacyScope.Fact -> entry.factId == scope.factId
    }

    private fun label(scope: NotificationFactPrivacyScope): String = when (scope) {
        NotificationFactPrivacyScope.All -> "all"
        is NotificationFactPrivacyScope.Package -> "package"
        is NotificationFactPrivacyScope.Source -> "source"
        is NotificationFactPrivacyScope.Fact -> "fact"
    }
}
