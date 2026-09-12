package ai.hans.standard.phone.notifications.facts

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.phone.capabilities.CapabilityConfirmation
import ai.hans.standard.phone.tools.DynamicToolConfirmationProvider
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFactToolExecutorsTest {
    @Test
    fun independentJobsAdvertiseReadsOnlyButInteractiveCatalogIncludesOwnerEdits() {
        val fixture = Fixture()
        assertEquals(listOf("query", "status"), fixture.tools.background.specs.single().tools.map { it.name })
        assertEquals(listOf("query", "status", "correct", "forget"),
            fixture.tools.interactive.specs.single().tools.map { it.name })
    }

    @Test
    fun threadBoundHeartbeatCanReadButCannotCorrectOrForgetEvenWithFullAccessPolicy() {
        val fixture = Fixture(interactive = false)
        assertTrue(call(fixture.tools.interactive, "status", "{}").success)
        assertTrue(call(fixture.tools.interactive, "query", "{}").success)
        assertFalse(call(fixture.tools.interactive, "correct", CORRECTION).success)
        val forgotten = call(fixture.tools.interactive, "forget", FORGET)
        assertFalse(forgotten.success)
        assertTrue(forgotten.contentText.contains("background_notification_memory_mutation_forbidden"))
        assertEquals(0, fixture.grants)
        assertEquals(0, fixture.repo.corrections)
        assertEquals(0, fixture.forgetCalls)
    }

    @Test
    fun independentJobCannotGuessMutationNameOutsideItsReadOnlyCatalog() {
        val fixture = Fixture()
        assertFalse(call(fixture.tools.background, "correct", CORRECTION).success)
        assertFalse(call(fixture.tools.background, "forget", FORGET).success)
        assertEquals(0, fixture.grants)
        assertEquals(0, fixture.repo.corrections)
        assertEquals(0, fixture.forgetCalls)
    }

    @Test
    fun userCorrectionUsesTrustedGrantAndForgetStillNeedsDurableCompletion() {
        val fixture = Fixture()
        assertTrue(call(fixture.tools.interactive, "correct", CORRECTION).success)
        assertEquals(1, fixture.repo.corrections)
        assertFalse(call(fixture.tools.interactive, "forget", FORGET).success)
        assertEquals(2, fixture.grants)
        assertEquals(1, fixture.forgetCalls)
    }

    @Test
    fun interactionAuthorityIsCheckedPerCallNotCapturedAtHostConstruction() {
        val fixture = Fixture()
        fixture.interactive = false
        assertFalse(call(fixture.tools.interactive, "correct", CORRECTION).success)
        fixture.interactive = true
        assertTrue(call(fixture.tools.interactive, "correct", CORRECTION).success)
        assertEquals(1, fixture.grants)
        assertEquals(1, fixture.repo.corrections)
    }

    private class Fixture(var interactive: Boolean = true) {
        val repo = Repo()
        var grants = 0
        var forgetCalls = 0
        val tools = NotificationFactToolExecutors(
            repository = repo,
            executor = Executor { it.run() },
            forget = {
                forgetCalls++
                NotificationFactPrivacyBeginResult.Unavailable(
                    NotificationFactUnavailableReason.PRIVACY_RECOVERY_REQUIRED,
                )
            },
            confirmation = DynamicToolConfirmationProvider {
                grants++
                CapabilityConfirmation(it.capabilityId, it.idempotencyKey, it.risk)
            },
            isInteractive = { interactive },
        )
    }

    private class Repo : NotificationFactRepository {
        var corrections = 0
        override fun captureToken(packageName: String): NotificationArchiveCaptureToken? = error("not exposed")
        override fun canStage(batch: NotificationFactBatch): Boolean = error("not exposed")
        override fun commit(batch: NotificationFactBatch): NotificationFactCommitResult = error("not exposed")
        override fun query(query: NotificationFactQuery) = NotificationFactQueryResult(emptyList(), false)
        override fun correct(correction: NotificationFactCorrection): NotificationFactCorrectionResult {
            corrections++
            return NotificationFactCorrectionResult.Applied(correction.factId, correction.expectedRevision + 1)
        }
        override fun beginPrivacy(request: NotificationFactPrivacyRequest): NotificationFactPrivacyBeginResult =
            error("only coordinated external purge may forget")
        override fun pendingIntents(): List<NotificationFactPrivacyIntent> = error("not exposed")
        override fun acknowledgePrivacy(intent: NotificationFactPrivacyIntent): Boolean = error("not exposed")
        override fun health() = NotificationFactArchiveHealth(true, null, 0, 0, 0, false, NotificationFactCapacity())
        override fun close() = Unit
    }

    private fun call(executor: DynamicToolExecutor, name: String, json: String): DynamicToolExecutionResult {
        val results = mutableListOf<DynamicToolExecutionResult>()
        executor.execute(DynamicToolCallParams("thread", "turn", "call",
            NotificationFactDynamicToolCatalog.NAMESPACE, name, json), results::add)
        assertEquals(1, results.size)
        return results.single()
    }

    private companion object {
        const val CORRECTION = """{"factId":"fact_one","expectedRevision":7,"mutationId":"edit_one","kind":"event_detail","text":"Samstag im Garten"}"""
        const val FORGET = """{"scope":"fact","factId":"fact_one","expectedRevision":7,"mutationId":"forget_one"}"""
    }
}
