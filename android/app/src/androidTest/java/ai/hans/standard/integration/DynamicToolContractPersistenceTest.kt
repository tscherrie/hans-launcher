package ai.hans.standard.integration

import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** UUID-prefixed preferences and synthetic cache files only; no runtime, account or user chat. */
@RunWith(AndroidJUnit4::class)
class DynamicToolContractPersistenceTest {
    @Test
    fun exactV1BootstrapThenDescriptionOnlyUpdatePreservesActualPersistedSelectionAndFiles() = withFixture { fixture ->
        val initial = listOf(namespace())
        check(fixture.preferences.edit()
            .putString("sha256", DynamicToolContractFingerprint.compute(initial)).commit())
        fixture.sessions().saveThreadId("synthetic-existing-conversation")

        assertEquals(DynamicToolMigrationResult.CURRENT, fixture.migrate(initial))
        assertEquals("synthetic-existing-conversation", fixture.sessions().readThreadId())
        val updated = listOf(namespace().copy(
            description = "Updated namespace explanation",
            tools = listOf(namespace().tools.single().copy(
                description = "Updated confirmation-policy explanation",
                inputSchemaJson = """{ "properties": {}, "type": "object" }""",
            )),
        ))
        assertEquals(DynamicToolMigrationResult.CURRENT, fixture.migrate(updated))
        assertEquals("synthetic-existing-conversation", fixture.sessions().readThreadId())
        assertEquals(DynamicToolContractFingerprint.computeStructure(updated), fixture.state().read().structureFingerprint)
        assertTrue(fixture.state().read().preservedThreads.isEmpty())
        assertNull(fixture.state().read().pendingRotation)
        fixture.assertFilesUnchanged()
    }

    @Test
    fun durablePendingReferenceRecoversOnBothSidesOfActualPointerRemoval() {
        listOf(false, true).forEach { interruptAfterClear ->
            withFixture { fixture ->
                val actualSession = fixture.sessions()
                actualSession.saveThreadId("synthetic-thread-before-interruption")
                val state = fixture.state()
                val interruptedState = object : DynamicToolContractStateStore by state {
                    override fun write(value: DynamicToolContractState) {
                        if (interruptAfterClear && value.pendingRotation == null && value.preservedThreads.isNotEmpty()) {
                            error("Synthetic interruption before final contract commit")
                        }
                        state.write(value)
                    }
                }
                val interruptedSessions = object : CodexSessionStore by actualSession {
                    override fun clearThreadId(expectedThreadId: String?) {
                        if (!interruptAfterClear) error("Synthetic interruption before pointer commit")
                        actualSession.clearThreadId(expectedThreadId)
                    }
                }
                assertTrue(runCatching {
                    DynamicToolContractMigrator(interruptedState, interruptedSessions).ensureCurrent(listOf(namespace()))
                }.isFailure)
                assertEquals(
                    "synthetic-thread-before-interruption",
                    fixture.state().read().pendingRotation?.threadId,
                )
                assertTrue(fixture.state().read().preservedThreads.isEmpty())
                assertEquals(
                    if (interruptAfterClear) null else "synthetic-thread-before-interruption",
                    fixture.sessions().readThreadId(),
                )

                assertEquals(DynamicToolMigrationResult.THREAD_ROTATED, fixture.migrate(listOf(namespace())))
                assertNull(fixture.sessions().readThreadId())
                assertNull(fixture.state().read().pendingRotation)
                val reference = fixture.state().read().preservedThreads.single()
                assertEquals("synthetic-thread-before-interruption", reference.threadId)
                assertEquals(DynamicToolRotationReason.LEGACY_CONTRACT_UNVERIFIED, reference.reason)
                assertEquals(DynamicToolMigrationResult.CURRENT, fixture.migrate(listOf(namespace())))
                assertEquals(listOf(reference), fixture.state().read().preservedThreads)
                fixture.assertFilesUnchanged()
            }
        }
    }

    @Test
    fun unknownMalformedOrConflictingV2DoesNotFallBackToLegacyOrChangeTheSelectedThread() {
        val valid = DynamicToolContractState(
            fullFingerprint = DynamicToolContractFingerprint.compute(listOf(namespace())),
            structureFingerprint = DynamicToolContractFingerprint.computeStructure(listOf(namespace())),
        )
        val json = DynamicToolContractStateCodec.encode(valid)
        val invalidStates = listOf(
            "{broken-json",
            JSONObject(json).put("version", 9).toString(),
            JSONObject(json).put("full", "f".repeat(64)).toString(),
            JSONObject(json).put("preserved", "not-an-array").toString(),
        )
        invalidStates.forEach { invalid ->
            withFixture { fixture ->
                fixture.sessions().saveThreadId("synthetic-chat-must-stay-selected")
                check(fixture.preferences.edit()
                    .putString("sha256", valid.fullFingerprint)
                    .putString("state_v2", invalid)
                    .commit())
                val before = fixture.preferences.all.toMap()
                assertTrue(runCatching { fixture.migrate(listOf(namespace())) }.isFailure)
                assertEquals("synthetic-chat-must-stay-selected", fixture.sessions().readThreadId())
                assertEquals(before, fixture.preferences.all)
                fixture.assertFilesUnchanged()
            }
        }
    }

    private fun namespace() = DynamicToolNamespaceSpec(
        "test", "Original namespace explanation",
        listOf(DynamicToolFunctionSpec("inspect", "Original tool explanation", """{"type":"object","properties":{}}""")),
    )

    private fun withFixture(action: (Fixture) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "dynamic_contract_test_${UUID.randomUUID()}_"
        val root = File(app.cacheDir, prefix)
        check(root.mkdir())
        val preferenceNames = mutableSetOf<String>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = prefix + name
                preferenceNames += isolated
                return app.getSharedPreferences(isolated, mode)
            }
        }
        try {
            action(Fixture(context, root))
        } finally {
            preferenceNames.forEach { name ->
                check(name.startsWith(prefix))
                app.deleteSharedPreferences(name)
            }
            check(root.deleteRecursively())
        }
    }

    private class Fixture(val context: Context, root: File) {
        val preferences: SharedPreferences = context.getSharedPreferences("hans_dynamic_tool_contract_v1", Context.MODE_PRIVATE)
        private val sentinels = mapOf(
            File(root, "synthetic-conversation.jsonl") to "synthetic prior conversation, not a user message",
            File(root, "synthetic-memory.txt") to "synthetic remembered preference",
            File(root, "synthetic-login-placeholder.txt") to "synthetic account marker, not a credential",
        )
        init { sentinels.forEach { (file, text) -> file.writeText(text) } }
        fun sessions() = AppPrivateCodexSessionStore(context)
        fun state() = AppPrivateDynamicToolContractStateStore(context)
        fun migrate(specs: List<DynamicToolNamespaceSpec>) = DynamicToolContractMigrator(state(), sessions()).ensureCurrent(specs)
        fun assertFilesUnchanged() = sentinels.forEach { (file, text) -> assertEquals(text, file.readText()) }
    }
}
