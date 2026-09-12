package ai.hans.standard.integration

import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicToolContractMigrationTest {
    @Test
    fun exactLegacyFingerprintBootstrapsStructureWithoutRotatingTheThread() {
        val specs = listOf(namespace())
        val state = FakeState(DynamicToolContractState(fullFingerprint = full(specs)))
        val sessions = FakeSessions("existing-conversation")

        assertEquals(DynamicToolMigrationResult.CURRENT, migrate(state, sessions, specs))
        assertEquals("existing-conversation", sessions.threadId)
        assertEquals(0, sessions.clearCount)
        assertEquals(structure(specs), state.value.structureFingerprint)
        assertTrue(state.value.preservedThreads.isEmpty())
        assertNull(state.value.pendingRotation)
        assertEquals(1, state.writeCount)
        assertEquals(DynamicToolMigrationResult.CURRENT, migrate(state, sessions, specs))
        assertEquals(1, state.writeCount)
    }

    @Test
    fun descriptionsAndNamespaceToolAndSchemaObjectOrderPreserveAnExistingV2Thread() {
        val initial = listOf(
            namespace().copy(tools = listOf(tool("inspect"), tool("click"))),
            namespace().copy(name = "second"),
        )
        val updated = initial.reversed().map { namespace ->
            namespace.copy(
                description = "User-authorized full access; Android permission gates still apply",
                tools = namespace.tools.reversed().map {
                    it.copy(description = "New explanation", inputSchemaJson = """{ "properties": {}, "type": "object" }""")
                },
            )
        }
        val state = FakeState(current(initial))
        val sessions = FakeSessions("existing-conversation")

        assertNotEquals(full(initial), full(updated))
        assertEquals(structure(initial), structure(updated))
        assertEquals(DynamicToolMigrationResult.CURRENT, migrate(state, sessions, updated))
        assertEquals("existing-conversation", sessions.threadId)
        assertEquals(0, sessions.clearCount)
        assertTrue(state.value.preservedThreads.isEmpty())
        assertEquals(full(updated), state.value.fullFingerprint)
    }

    @Test
    fun exactRevisionMigrationRotatesEvenWhenOnlyDescriptionsChanged() {
        val initial = listOf(namespace())
        val updated = listOf(namespace().copy(description = "Updated contract description"))
        val state = FakeState(current(initial))
        val sessions = FakeSessions("must-not-resume-with-changed-specs")

        assertEquals(
            DynamicToolMigrationResult.THREAD_ROTATED,
            DynamicToolContractMigrator(state, sessions).ensureExactCurrent(updated),
        )
        assertNull(sessions.threadId)
        val preserved = state.value.preservedThreads.single()
        assertEquals(
            DynamicToolRotationReason.FULL_CONTRACT_CHANGED,
            preserved.reason,
        )
        assertEquals(structure(initial), preserved.targetStructureFingerprint)
        assertEquals(full(updated), preserved.targetFullFingerprint)
    }

    @Test
    fun unknowableLegacyHashIsNotAssumedCompatibleEvenWhenTheTestKnowsOnlyDescriptionsChanged() {
        val original = listOf(namespace())
        val updated = listOf(namespace().copy(description = "Revised description"))
        val state = FakeState(DynamicToolContractState(fullFingerprint = full(original)))
        val sessions = FakeSessions("legacy-conversation")

        assertEquals(structure(original), structure(updated))
        assertEquals(DynamicToolMigrationResult.THREAD_ROTATED, migrate(state, sessions, updated))
        assertNull(sessions.threadId)
        val reference = state.value.preservedThreads.single()
        assertEquals("legacy-conversation", reference.threadId)
        assertEquals(DynamicToolRotationReason.LEGACY_CONTRACT_UNVERIFIED, reference.reason)
        assertEquals(full(original), reference.previousFullFingerprint)
        assertNull(reference.previousStructureFingerprint)
        assertEquals(structure(updated), reference.targetStructureFingerprint)
    }

    @Test
    fun absentLegacyEvidencePreservesReferenceBeforeRotatingButAFreshInstallHasNoReference() {
        val state = FakeState()
        val sessions = FakeSessions("unverified-thread")
        sessions.beforeClear = {
            assertEquals("unverified-thread", state.value.pendingRotation?.threadId)
            assertTrue(state.value.preservedThreads.isEmpty())
            assertNull(state.value.structureFingerprint)
        }
        assertEquals(DynamicToolMigrationResult.THREAD_ROTATED, migrate(state, sessions))
        assertEquals("unverified-thread", state.value.preservedThreads.single().threadId)

        val fresh = FakeState()
        assertEquals(DynamicToolMigrationResult.INITIALIZED, migrate(fresh, FakeSessions(null)))
        assertTrue(fresh.value.preservedThreads.isEmpty())
        assertNotNull(fresh.value.structureFingerprint)
    }

    @Test
    fun actualStructureChangesRotateWithThePriorThreadAndBothContractsPreserved() {
        val original = listOf(namespace())
        val changes = listOf(
            listOf(namespace().copy(name = "renamed")),
            listOf(namespace().copy(tools = listOf(tool("renamed")))),
            listOf(namespace().copy(tools = listOf(tool("inspect").copy(deferLoading = true)))),
            listOf(namespace().copy(tools = listOf(tool("inspect").copy(
                inputSchemaJson = """{"type":"object","properties":{"x":{"type":"string"}},"required":["x"]}""",
            )))),
            listOf(namespace(), namespace().copy(name = "added")),
            emptyList(),
        )
        changes.forEach { updated ->
            val state = FakeState(current(original))
            val sessions = FakeSessions("conversation-before-structure-change")
            assertEquals(DynamicToolMigrationResult.THREAD_ROTATED, migrate(state, sessions, updated))
            val reference = state.value.preservedThreads.single()
            assertEquals(DynamicToolRotationReason.STRUCTURE_CHANGED, reference.reason)
            assertEquals(structure(original), reference.previousStructureFingerprint)
            assertEquals(structure(updated), reference.targetStructureFingerprint)
            assertEquals("conversation-before-structure-change", reference.threadId)
            assertNull(sessions.threadId)
        }
    }

    @Test
    fun canonicalizationNeverDropsSchemaValuesOrReordersArrays() {
        fun schema(raw: String) = listOf(namespace().copy(tools = listOf(tool("inspect").copy(inputSchemaJson = raw))))
        assertEquals(
            structure(schema("""{"type":"object","properties":{"x":{"type":"string","enum":["a","b"]}}}""")),
            structure(schema("""{"properties":{"x":{"enum":["a","b"],"type":"string"}},"type":"object"}""")),
        )
        assertNotEquals(
            structure(schema("""{"type":"object","properties":{"description":{"type":"string"}}}""")),
            structure(schema("""{"type":"object","properties":{"description":{"type":"boolean"}}}""")),
        )
        assertNotEquals(
            structure(schema("""{"type":"object","properties":{"x":{"prefixItems":[{"type":"string"},{"type":"boolean"}]}}}""")),
            structure(schema("""{"type":"object","properties":{"x":{"prefixItems":[{"type":"boolean"},{"type":"string"}]}}}""")),
        )
        assertNotEquals(
            structure(schema("""{"type":"object","properties":{},"additionalProperties":true}""")),
            structure(schema("""{"type":"object","properties":{},"additionalProperties":false}""")),
        )
        assertTrue(runCatching { structure(listOf(namespace(), namespace())) }.isFailure)
    }

    @Test
    fun failedBootstrapOrStagingCommitNeverRemovesTheSelectedThread() {
        listOf(DynamicToolContractState(), DynamicToolContractState(fullFingerprint = full(listOf(namespace()))))
            .forEach { initial ->
                val state = FakeState(initial).apply { failWriteNumber = 1 }
                val sessions = FakeSessions("must-remain-selected")
                assertTrue(runCatching { migrate(state, sessions) }.isFailure)
                assertEquals("must-remain-selected", sessions.threadId)
                assertEquals(0, sessions.clearCount)
                assertEquals(initial, state.value)
            }
    }

    @Test
    fun failureToClearLeavesDurablePendingReferenceAndAReopenedMigratorCompletesExactlyOnce() {
        val state = FakeState()
        val sessions = FakeSessions("recoverable-conversation").apply { failClear = true }
        assertTrue(runCatching { migrate(state, sessions) }.isFailure)
        assertEquals("recoverable-conversation", state.value.pendingRotation?.threadId)
        assertTrue(state.value.preservedThreads.isEmpty())
        assertEquals("recoverable-conversation", sessions.threadId)
        sessions.failClear = false

        assertEquals(DynamicToolMigrationResult.THREAD_ROTATED, migrate(state, sessions))
        assertNull(sessions.threadId)
        assertNull(state.value.pendingRotation)
        assertEquals("recoverable-conversation", state.value.preservedThreads.single().threadId)
        assertEquals(1, sessions.clearCount)
        assertEquals(DynamicToolMigrationResult.CURRENT, migrate(state, sessions))
        assertEquals(1, sessions.clearCount)
        assertEquals(1, state.value.preservedThreads.size)
    }

    @Test
    fun failureAfterPointerRemovalRetainsReferenceAndRestartDoesNotLoseOrDuplicateIt() {
        val state = FakeState().apply { failWriteNumber = 2 }
        val sessions = FakeSessions("recoverable-conversation")
        assertTrue(runCatching { migrate(state, sessions) }.isFailure)
        assertNull(sessions.threadId)
        assertEquals("recoverable-conversation", state.value.pendingRotation?.threadId)
        assertNull(state.value.structureFingerprint)

        state.failWriteNumber = null
        assertEquals(DynamicToolMigrationResult.THREAD_ROTATED, migrate(state, sessions))
        assertEquals("recoverable-conversation", state.value.preservedThreads.single().threadId)
        assertEquals(1, sessions.clearCount)
        assertNull(state.value.pendingRotation)
        assertEquals(DynamicToolMigrationResult.CURRENT, migrate(state, sessions))
        assertEquals(1, state.value.preservedThreads.size)
    }

    @Test
    fun restartWithAnotherCatalogueFinishesTheRecordedTransitionBeforeAdoptingNewStructure() {
        val first = listOf(namespace())
        val next = listOf(namespace().copy(tools = listOf(tool("different"))))
        val state = FakeState().apply { failWriteNumber = 2 }
        val sessions = FakeSessions("original-conversation")
        assertTrue(runCatching { migrate(state, sessions, first) }.isFailure)
        state.failWriteNumber = null
        assertEquals(DynamicToolMigrationResult.THREAD_ROTATED, migrate(state, sessions, next))
        assertEquals(structure(first), state.value.preservedThreads.single().targetStructureFingerprint)
        assertEquals(structure(next), state.value.structureFingerprint)
        assertNull(sessions.threadId)
    }

    @Test
    fun concurrentReplacementPointerIsNotClearedOrClaimedCompatible() {
        val state = FakeState()
        val sessions = FakeSessions("original-thread")
        sessions.beforeClear = { sessions.threadId = "different-thread" }
        assertTrue(runCatching { migrate(state, sessions) }.isFailure)
        assertEquals("different-thread", sessions.threadId)
        assertEquals(0, sessions.clearCount)
        assertEquals("original-thread", state.value.pendingRotation?.threadId)
        assertNull(state.value.structureFingerprint)
        assertTrue(runCatching { migrate(state, sessions) }.isFailure)
        assertEquals("different-thread", sessions.threadId)
        assertEquals(1, state.writeCount)
    }

    @Test
    fun retainedReferenceLimitFailsBeforeStagingOrClearingAndNeverEvictsHistory() {
        val refs = (0 until MAX_PRESERVED_DYNAMIC_TOOL_THREADS).map { reference("earlier-$it") }
        val initial = DynamicToolContractState(preservedThreads = refs)
        val state = FakeState(initial)
        val sessions = FakeSessions("current-conversation")
        assertTrue(runCatching { migrate(state, sessions) }.isFailure)
        assertEquals("current-conversation", sessions.threadId)
        assertEquals(initial, state.value)
        assertEquals(0, state.writeCount)
        assertEquals(0, sessions.clearCount)
    }

    @Test
    fun codecRoundTripsPendingAndCompletedReferencesAndRejectsUnknownOrMalformedState() {
        val state = DynamicToolContractState(
            pendingRotation = reference("pending"),
            preservedThreads = listOf(reference("completed")),
        )
        assertEquals(state, DynamicToolContractStateCodec.decode(DynamicToolContractStateCodec.encode(state)))
        val encoded = DynamicToolContractStateCodec.encode(state)
        val invalid = listOf(
            "{",
            JSONObject(encoded).put("version", 3).toString(),
            JSONObject(encoded).put("unexpected", true).toString(),
            JSONObject(encoded).put("full", "invalid").toString(),
            JSONObject(encoded).put("structure", "a".repeat(64)).toString(),
            JSONObject(encoded).put("pending", JSONObject(JSONObject(encoded).getJSONObject("pending").toString())
                .put("previousFull", "b".repeat(64))).toString(),
            JSONObject(encoded).put("preserved", JSONArray().put(referenceJson("duplicate")).put(referenceJson("duplicate"))).toString(),
        )
        invalid.forEach { assertTrue(it, runCatching { DynamicToolContractStateCodec.decode(it) }.isFailure) }
    }

    @Test
    fun sharedPreferencesFailedCommitCannotBeMistakenForADurableJournalByAnotherHost() {
        val preferences = FailingCommitPreferences()
        val first = AppPrivateDynamicToolContractStateStore(preferences.proxy)
        val sessions = FakeSessions("must-remain-selected")
        assertTrue(runCatching { migrate(first, sessions) }.isFailure)
        assertEquals("must-remain-selected", sessions.threadId)
        assertEquals(0, sessions.clearCount)
        // Android can have the pending value in memory even though it was never persisted.
        assertTrue(preferences.values.containsKey("state_v2"))
        assertFalse(preferences.values["state_v2"].isNullOrBlank())
        val reopened = AppPrivateDynamicToolContractStateStore(preferences.proxy)
        assertTrue(runCatching { migrate(reopened, sessions) }.isFailure)
        assertEquals("must-remain-selected", sessions.threadId)
        assertEquals(1, preferences.commits)
    }

    @Test
    fun failedRealSessionClearCannotFinalizeFromRamAndDiskRestartStillRecoversTheOldThread() {
        val preferences = FailingCommitPreferences().apply { seed("thread_id", "disk-original-thread") }
        val sessions = AppPrivateCodexSessionStore(preferences.proxy, "/synthetic/workspace")
        val state = FakeState()
        assertTrue(runCatching { migrate(state, sessions) }.isFailure)
        assertNull(preferences.values["thread_id"])
        assertEquals("disk-original-thread", preferences.diskValues["thread_id"])
        assertEquals("disk-original-thread", state.value.pendingRotation?.threadId)
        assertNull(state.value.structureFingerprint)

        val anotherHost = AppPrivateCodexSessionStore(preferences.proxy, "/synthetic/workspace")
        assertTrue(runCatching { anotherHost.readThreadId() }.isFailure)
        assertTrue(runCatching { migrate(state, anotherHost) }.isFailure)
        assertTrue(runCatching { anotherHost.saveThreadId("must-not-obscure-failed-clear") }.isFailure)
        assertTrue(runCatching { anotherHost.clearThreadId() }.isFailure)
        assertEquals(1, preferences.commits)
        assertEquals(1, state.writeCount)

        // A new process loads the unchanged DISK pointer, not the failed process's RAM map.
        val restartedPreferences = FailingCommitPreferences().apply {
            failCommits = false
            preferences.diskValues.forEach { (key, value) -> seed(key, value) }
        }
        val restarted = AppPrivateCodexSessionStore(restartedPreferences.proxy, "/synthetic/workspace")
        assertEquals("disk-original-thread", restarted.readThreadId())
        assertEquals(DynamicToolMigrationResult.THREAD_ROTATED, migrate(state, restarted))
        assertNull(restarted.readThreadId())
        assertNull(restartedPreferences.diskValues["thread_id"])
        assertEquals("disk-original-thread", state.value.preservedThreads.single().threadId)
        assertNull(state.value.pendingRotation)
    }

    @Test
    fun realSessionStoreStillValidatesIdsAndUsesExpectedPointerWithoutExtraWrites() {
        val preferences = FailingCommitPreferences().apply {
            failCommits = false
            seed("thread_id", "original")
        }
        val sessions = AppPrivateCodexSessionStore(preferences.proxy, "/synthetic/workspace")
        sessions.clearThreadId("not-the-selected-thread")
        assertEquals("original", sessions.readThreadId())
        assertEquals(0, preferences.commits)
        assertTrue(runCatching { sessions.saveThreadId("bad\nidentifier") }.isFailure)
        assertEquals("original", sessions.readThreadId())
        sessions.saveThreadId("new-selected-thread")
        assertEquals("new-selected-thread", preferences.diskValues["thread_id"])
        sessions.clearThreadId("new-selected-thread")
        assertNull(sessions.readThreadId())
        assertNull(preferences.diskValues["thread_id"])
        assertEquals(2, preferences.commits)
    }

    private fun migrate(
        state: DynamicToolContractStateStore,
        sessions: CodexSessionStore,
        specs: List<DynamicToolNamespaceSpec> = listOf(namespace()),
    ) = DynamicToolContractMigrator(state, sessions).ensureCurrent(specs)

    private fun namespace() = DynamicToolNamespaceSpec("test", "Test namespace", listOf(tool("inspect")))
    private fun tool(name: String) = DynamicToolFunctionSpec(name, "Test tool", """{"type":"object","properties":{}}""")
    private fun full(specs: List<DynamicToolNamespaceSpec>) = DynamicToolContractFingerprint.compute(specs)
    private fun structure(specs: List<DynamicToolNamespaceSpec>) = DynamicToolContractFingerprint.computeStructure(specs)
    private fun current(specs: List<DynamicToolNamespaceSpec>) = DynamicToolContractState(full(specs), structure(specs))
    private fun reference(thread: String) = PreservedDynamicToolThread(
        thread, DynamicToolRotationReason.LEGACY_CONTRACT_UNVERIFIED, null, null,
        full(listOf(namespace())), structure(listOf(namespace())),
    )
    private fun referenceJson(thread: String): JSONObject = JSONObject(
        DynamicToolContractStateCodec.encode(DynamicToolContractState(pendingRotation = reference(thread))),
    ).getJSONObject("pending")

    private class FakeState(var value: DynamicToolContractState = DynamicToolContractState()) : DynamicToolContractStateStore {
        var writeCount = 0
        var failWriteNumber: Int? = null
        override fun read(): DynamicToolContractState = value
        override fun write(value: DynamicToolContractState) {
            writeCount += 1
            check(writeCount != failWriteNumber) { "Synthetic persistence interruption" }
            this.value = DynamicToolContractStateCodec.decode(DynamicToolContractStateCodec.encode(value))
        }
    }

    private class FakeSessions(var threadId: String?) : CodexSessionStore {
        override val workspacePath = "/synthetic/codex-workspace"
        var clearCount = 0
        var failClear = false
        var beforeClear: () -> Unit = {}
        override fun readThreadId(): String? = threadId
        override fun saveThreadId(threadId: String) { this.threadId = threadId }
        override fun clearThreadId(expectedThreadId: String?) {
            beforeClear()
            check(!failClear) { "Synthetic pointer persistence interruption" }
            if (expectedThreadId == null || expectedThreadId == threadId) {
                threadId = null
                clearCount += 1
            }
        }
    }

    /** Only the SharedPreferences surface used by the real store; no Android framework stubs. */
    private class FailingCommitPreferences {
        val values = mutableMapOf<String, String?>()
        val diskValues = mutableMapOf<String, String?>()
        var failCommits = true
        var commits = 0
        fun seed(key: String, value: String?) {
            values[key] = value
            diskValues[key] = value
        }
        private val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { self, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1] as String?; self }
                "remove" -> { values.remove(args!![0] as String); self }
                "commit" -> {
                    commits += 1
                    if (failCommits) false else {
                        diskValues.clear()
                        diskValues.putAll(values)
                        true
                    }
                }
                else -> error("Unexpected editor operation: ${method.name}")
            }
        } as SharedPreferences.Editor
        val proxy = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { self, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1]
                "contains" -> values.containsKey(args!![0] as String)
                "edit" -> editor
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args!![0]
                "toString" -> "Synthetic failing SharedPreferences"
                else -> error("Unexpected preferences operation: ${method.name}")
            }
        } as SharedPreferences
    }
}
