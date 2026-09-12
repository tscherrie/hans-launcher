package ai.hans.standard.phone.notifications

import ai.hans.standard.codex.CompositeDynamicToolExecutor
import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import ai.hans.standard.phone.notifications.facts.*
import ai.hans.standard.phone.tools.DynamicToolConfirmationProvider
import ai.hans.standard.phone.tools.SwappableDynamicToolConfirmationProvider
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises both routes together: denying fact.forget must not leave inbox.clear_history open. */
class NotificationToolInteractionBoundaryTest {
    @Test
    fun independentCatalogContainsOnlyReadsWhileOwnerCatalogKeepsBothFullNamespaces() {
        val fixture = Fixture()
        val background = fixture.background.specs.associate { it.name to it.tools.map { tool -> tool.name } }
        assertEquals(INBOX_READS, background.getValue(NotificationInboxDynamicToolCatalog.NAMESPACE))
        assertEquals(listOf("query", "status"), background.getValue(NotificationFactDynamicToolCatalog.NAMESPACE))
        assertEquals(
            NotificationInboxDynamicToolCatalog.namespace.tools.map { it.name },
            fixture.interactive.specs.single { it.name == NotificationInboxDynamicToolCatalog.NAMESPACE }
                .tools.map { it.name },
        )
        assertEquals(listOf("query", "status", "correct", "forget"),
            fixture.interactive.specs.single { it.name == NotificationFactDynamicToolCatalog.NAMESPACE }
                .tools.map { it.name })
        assertEquals(
            NotificationInboxDynamicToolCatalog.namespace.tools.map { it.name }.toSet() - INBOX_READS.toSet(),
            MUTATIONS.filter { it.namespace == NotificationInboxDynamicToolCatalog.NAMESPACE }.map { it.tool }.toSet(),
        )
    }

    @Test
    fun independentJobsCannotGuessAnyMutationEvenWithAutomaticFullAccessConfirmations() {
        val fixture = Fixture()
        for (cancellable in listOf(false, true)) {
            MUTATIONS.forEach { request ->
                assertFalse(request.label, call(fixture.background, request, cancellable).success)
            }
        }
        fixture.assertNoMutationsOrConfirmations()
        assertEquals(0, fixture.inbox.readCalls)
        assertEquals(0, fixture.facts.readCalls)
        assertEquals(0, fixture.originChecks)
    }

    @Test
    fun reservedHeartbeatCannotMutateEitherNamespaceBeforeOrAfterTurnAcknowledgement() {
        val fixture = Fixture()
        fixture.reservedThread = "thread"
        for (acknowledgedTurn in listOf(null, "turn")) {
            fixture.reservedTurn = acknowledgedTurn
            for (cancellable in listOf(false, true)) {
                MUTATIONS.forEach { request ->
                    assertFalse(request.label, call(fixture.interactive, request, cancellable).success)
                }
            }
        }
        fixture.assertNoMutationsOrConfirmations()
        assertEquals(0, fixture.inbox.readCalls)
        assertEquals(0, fixture.facts.readCalls)
        assertTrue(fixture.originChecks > 0)
    }

    @Test
    fun independentAndReservedHeartbeatRoutesKeepAllInboxAndArchiveReads() {
        val fixture = Fixture()
        fixture.reservedThread = "thread"
        for (route in listOf(fixture.background, fixture.interactive)) {
            for (cancellable in listOf(false, true)) {
                READS.forEach { request ->
                    assertTrue(request.label, call(route, request, cancellable).success)
                }
            }
        }
        fixture.assertNoMutationsOrConfirmations()
        assertTrue(fixture.inbox.readCalls > 0)
        assertTrue(fixture.facts.readCalls > 0)
        assertEquals("Reads need no mutation authority", 0, fixture.originChecks)
    }

    @Test
    fun explicitOwnerPathKeepsExistingAutomaticFullAccessForEveryMutation() {
        for (cancellable in listOf(false, true)) {
            val fixture = Fixture()
            MUTATIONS.forEach { request ->
                assertTrue(request.label, call(fixture.interactive, request, cancellable).success)
            }
            assertEquals(MUTATIONS.size, fixture.confirmations)
            assertEquals(listOf("exclude_package", "include_package", "set_retention", "clear_history",
                "import_privacy_settings"), fixture.inbox.mutations)
            assertEquals(1, fixture.facts.corrections)
            assertEquals(2, fixture.forgets)
            assertEquals("The owner tool uses durable all-data clear, not transient recovery", 0,
                fixture.inbox.transientClears)
        }
    }

    @Test
    fun currentOriginIsRecheckedForEachCallInsteadOfCapturedWhenTheHostStarts() {
        val fixture = Fixture()
        val clear = MUTATIONS.single { it.tool == "clear_history" }
        val correct = MUTATIONS.single { it.tool == "correct" }
        fixture.reservedThread = "thread"
        assertFalse(call(fixture.interactive, clear).success)
        assertFalse(call(fixture.interactive, correct).success)
        fixture.assertNoMutationsOrConfirmations()

        fixture.reservedThread = null
        assertTrue(call(fixture.interactive, clear).success)
        assertTrue(call(fixture.interactive, correct).success)
        fixture.reservedThread = "thread"
        fixture.reservedTurn = "turn"
        assertFalse(call(fixture.interactive, clear, cancellable = true).success)
        assertFalse(call(fixture.interactive, correct, cancellable = true).success)
        assertEquals(2, fixture.confirmations)
        assertEquals(listOf("clear_history"), fixture.inbox.mutations)
        assertEquals(1, fixture.facts.corrections)
    }

    @Test
    fun unknownInboxVerbFailsClosedBeforeConfirmationOrSourceAccess() {
        val fixture = Fixture()
        fixture.reservedThread = "thread"
        val request = CallCase(NotificationInboxDynamicToolCatalog.NAMESPACE, "future_management", "{}")
        for (route in listOf(fixture.background, fixture.interactive)) {
            for (cancellable in listOf(false, true)) {
                val result = call(route, request, cancellable)
                assertFalse(result.success)
                assertTrue(result.contentText.contains("background_notification_management_forbidden"))
            }
        }
        fixture.assertNoMutationsOrConfirmations()
        assertEquals(0, fixture.inbox.readCalls)
    }

    private class Fixture {
        val inbox = Inbox()
        val facts = Facts()
        var confirmations = 0
        var forgets = 0
        var originChecks = 0
        var reservedThread: String? = null
        var reservedTurn: String? = null
        private val fullAccess = SwappableDynamicToolConfirmationProvider(
            actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
        )
        private val confirmation = DynamicToolConfirmationProvider {
            confirmations++
            fullAccess.confirmedGrant(it)
        }
        private val isInteractive: (DynamicToolCallParams) -> Boolean = { call ->
            originChecks++
            reservedThread != call.threadId || (reservedTurn != null && reservedTurn != call.turnId)
        }
        private val inboxTools = NotificationInboxToolExecutors(
            inbox, Executor { it.run() }, confirmation, isInteractive,
        )
        private val factTools = NotificationFactToolExecutors(
            repository = facts,
            executor = Executor { it.run() },
            forget = { request ->
                forgets++
                val source = request.scope as? NotificationFactPrivacyScope.Source
                NotificationFactPrivacyBeginResult.Completed(NotificationFactPrivacyIntent(
                    storeEpoch = "00000000-0000-0000-0000-000000000001",
                    mutationId = request.mutationId,
                    scope = request.scope,
                    affectedPackageName = source?.packageName ?: "org.example.chat",
                    affectedSourceRef = source?.sourceRef ?: "source_one",
                    allGeneration = 0,
                    packageGeneration = 0,
                    removedFacts = 1,
                ))
            },
            confirmation = confirmation,
            isInteractive = isInteractive,
        )
        val background = CompositeDynamicToolExecutor(listOf(inboxTools.background, factTools.background))
        val interactive = CompositeDynamicToolExecutor(listOf(inboxTools.interactive, factTools.interactive))

        fun assertNoMutationsOrConfirmations() {
            assertEquals(0, confirmations)
            assertTrue(inbox.mutations.isEmpty())
            assertEquals(0, inbox.transientClears)
            assertEquals(0, facts.corrections)
            assertEquals(0, forgets)
        }
    }

    private class Inbox : NotificationInboxQuerySource, NotificationInboxManagementSource {
        private var settings = NotificationPrivacySettings()
        val mutations = mutableListOf<String>()
        var readCalls = 0
        var transientClears = 0
        override fun queryPage(afterSequenceExclusive: Long, limit: Int): NotificationPage {
            readCalls++
            return NotificationPage(emptyList(), afterSequenceExclusive, false)
        }
        override fun queryDigest(afterSequenceExclusive: Long, maxEvents: Int, maxUtf8Bytes: Int): NotificationDigest {
            readCalls++
            return NotificationDigest("", 0, 0, afterSequenceExclusive, false)
        }
        override fun privacyStatus(): NotificationPrivacyStatus {
            readCalls++
            return NotificationPrivacyStatus(true, setOf("ai.hans.standard"), settings.excludedPackages,
                settings.retention)
        }
        override fun excludePackage(packageName: String): NotificationPrivacyMutation {
            mutations += "exclude_package"
            settings = settings.copy(excludedPackages = settings.excludedPackages + packageName)
            return NotificationPrivacyMutation(settings, 0)
        }
        override fun includePackage(packageName: String): NotificationPrivacyMutation {
            mutations += "include_package"
            settings = settings.copy(excludedPackages = settings.excludedPackages - packageName)
            return NotificationPrivacyMutation(settings, 0)
        }
        override fun setRetention(maxEvents: Int, maxAgeHours: Int): NotificationPrivacyMutation {
            mutations += "set_retention"
            settings = settings.copy(retention = NotificationRetentionPolicy(maxEvents, maxAgeHours))
            return NotificationPrivacyMutation(settings, 0)
        }
        override fun clearHistory(): NotificationHistoryClearResult {
            transientClears++
            return NotificationHistoryClearResult(0, true)
        }
        override fun clearAllNotificationData(): NotificationAllDataClearResult {
            mutations += "clear_history"
            return NotificationAllDataClearResult(0, true, true, true, true)
        }
        override fun exportPrivacySettings(): String {
            readCalls++
            return NotificationPrivacyRecoveryCodec.encode(settings)
        }
        override fun importPrivacySettings(document: String): NotificationPrivacyMutation {
            mutations += "import_privacy_settings"
            settings = NotificationPrivacyRecoveryCodec.decode(document)
            return NotificationPrivacyMutation(settings, 0)
        }
    }

    private class Facts : NotificationFactRepository {
        var corrections = 0
        var readCalls = 0
        override fun captureToken(packageName: String): NotificationArchiveCaptureToken? = error("not exposed")
        override fun canStage(batch: NotificationFactBatch): Boolean = error("not exposed")
        override fun commit(batch: NotificationFactBatch): NotificationFactCommitResult = error("not exposed")
        override fun query(query: NotificationFactQuery): NotificationFactQueryResult {
            readCalls++
            return NotificationFactQueryResult(emptyList(), false)
        }
        override fun correct(correction: NotificationFactCorrection): NotificationFactCorrectionResult {
            corrections++
            return NotificationFactCorrectionResult.Applied(correction.factId, correction.expectedRevision + 1)
        }
        override fun beginPrivacy(request: NotificationFactPrivacyRequest): NotificationFactPrivacyBeginResult =
            error("only coordinated external purge may forget")
        override fun pendingIntents(): List<NotificationFactPrivacyIntent> = error("not exposed")
        override fun acknowledgePrivacy(intent: NotificationFactPrivacyIntent): Boolean = error("not exposed")
        override fun health(): NotificationFactArchiveHealth {
            readCalls++
            return NotificationFactArchiveHealth(true, null, 0, 0, 0, false, NotificationFactCapacity())
        }
        override fun close() = Unit
    }

    private data class CallCase(val namespace: String, val tool: String, val json: String) {
        val label: String get() = "$namespace.$tool"
    }

    private fun call(executor: DynamicToolExecutor, request: CallCase, cancellable: Boolean = false):
        DynamicToolExecutionResult {
        val results = mutableListOf<DynamicToolExecutionResult>()
        val params = DynamicToolCallParams("thread", "turn", "call", request.namespace, request.tool, request.json)
        if (cancellable) executor.executeCancellable(params, DynamicToolCancellation.NONE, results::add)
        else executor.execute(params, results::add)
        assertEquals(1, results.size)
        return results.single()
    }

    private companion object {
        val INBOX_READS = listOf("recent", "relevant", "privacy_status", "export_privacy_settings")
        val READS = listOf(
            CallCase("android_notifications", "recent", """{"sinceEpochMillis":0}"""),
            CallCase("android_notifications", "relevant", """{"sinceEpochMillis":0,"terms":["Garten"]}"""),
            CallCase("android_notifications", "privacy_status", "{}"),
            CallCase("android_notifications", "export_privacy_settings", "{}"),
            CallCase("android_notification_memory", "query", "{}"),
            CallCase("android_notification_memory", "status", "{}"),
        )
        val MUTATIONS = listOf(
            CallCase("android_notifications", "exclude_package", """{"packageName":"org.example.chat"}"""),
            CallCase("android_notifications", "include_package", """{"packageName":"org.example.chat"}"""),
            CallCase("android_notifications", "set_retention", """{"maxEvents":500,"maxAgeHours":168}"""),
            CallCase("android_notifications", "clear_history", "{}"),
            CallCase("android_notifications", "import_privacy_settings", JSONObject().put("document",
                JSONObject(NotificationPrivacyRecoveryCodec.encode(NotificationPrivacySettings()))).toString()),
            CallCase("android_notification_memory", "correct", """{"factId":"fact_one","expectedRevision":7,"mutationId":"edit_one","kind":"event_detail","text":"Samstag im Garten"}"""),
            CallCase("android_notification_memory", "forget", """{"scope":"fact","factId":"fact_one","expectedRevision":7,"mutationId":"forget_one"}"""),
            CallCase("android_notification_memory", "forget", """{"scope":"source","packageName":"org.example.chat","sourceRef":"source_one","mutationId":"forget_source"}"""),
        )
    }
}
