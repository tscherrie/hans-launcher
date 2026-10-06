package ai.hans.standard.integration

import ai.hans.standard.codex.CodexInput
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPrivateVisibleInputReceiptStoreTest {
    @Test
    fun receiptSurvivesFreshStoreAndPreferencesInstancesWithoutPersistingVisibleText() {
        val visibleText = "This exact visible text must never be stored"
        val receipt = receipt("thread-1", "client-1", visibleText)
        val preferences = InMemorySharedPreferences()

        AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE).record(receipt)

        val durablePayload = checkNotNull(preferences.snapshot()[RECEIPTS_KEY])
        assertFalse(durablePayload.contains(visibleText))
        assertTrue(durablePayload.contains(VisibleInputReceipt.sha256(visibleText)))

        val restartedPreferences = InMemorySharedPreferences(preferences.snapshot())
        val restartedStore = AppPrivateCodexSessionStore(restartedPreferences.proxy, WORKSPACE)
        assertEquals(receipt, restartedStore.read("thread-1", "client-1"))
    }

    @Test
    fun attachmentOnlyReceiptRoundTripsWithAnEmptyDigestList() {
        val receipt = checkNotNull(
            VisibleInputReceipt.fromInputs(
                threadId = "thread-attachment",
                clientId = "client-attachment",
                inputs = listOf(CodexInput.LocalImage("/private/image.jpg")),
            ),
        )
        val preferences = InMemorySharedPreferences()
        AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE).record(receipt)

        val restartedStore = AppPrivateCodexSessionStore(
            InMemorySharedPreferences(preferences.snapshot()).proxy,
            WORKSPACE,
        )
        assertEquals(receipt, restartedStore.read("thread-attachment", "client-attachment"))
        assertEquals(
            VisibleInputReceipt.ATTACHMENT_LABEL,
            restartedStore.read("thread-attachment", "client-attachment")
                ?.recoverDisplayText(emptyList()),
        )
    }

    @Test
    fun recordingTheSameIdentityReplacesItsDurableReceipt() {
        val preferences = InMemorySharedPreferences()
        val store = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)
        val original = receipt("thread-1", "client-1", "old visible text")
        val replacement = receipt("thread-1", "client-1", "new visible text")

        store.record(original)
        store.record(replacement)

        val root = JSONObject(checkNotNull(preferences.snapshot()[RECEIPTS_KEY]))
        assertEquals(1, root.getJSONArray("records").length())
        val restartedPreferences = InMemorySharedPreferences(preferences.snapshot())
        val restartedStore = AppPrivateCodexSessionStore(restartedPreferences.proxy, WORKSPACE)
        assertEquals(replacement, restartedStore.read("thread-1", "client-1"))
        assertFalse(root.toString().contains(VisibleInputReceipt.sha256("old visible text")))
    }

    @Test
    fun removeAndThreadScopedClearRemainEffectiveAfterRestart() {
        val preferences = InMemorySharedPreferences()
        val store = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)
        store.record(receipt("thread-1", "client-1", "one"))
        store.record(receipt("thread-1", "client-2", "two"))
        val otherThread = receipt("thread-2", "client-3", "three")
        store.record(otherThread)

        store.remove("thread-1", "client-1")
        store.clear("thread-1")

        val restartedPreferences = InMemorySharedPreferences(preferences.snapshot())
        val restartedStore = AppPrivateCodexSessionStore(restartedPreferences.proxy, WORKSPACE)
        assertNull(restartedStore.read("thread-1", "client-1"))
        assertNull(restartedStore.read("thread-1", "client-2"))
        assertEquals(otherThread, restartedStore.read("thread-2", "client-3"))

        restartedStore.clear()
        val clearedStore = AppPrivateCodexSessionStore(
            InMemorySharedPreferences(restartedPreferences.snapshot()).proxy,
            WORKSPACE,
        )
        assertNull(clearedStore.read("thread-2", "client-3"))
    }

    @Test
    fun selectedThreadClearRemovesOnlyItsReceiptsInTheSameDurableCommit() {
        val preferences = InMemorySharedPreferences()
        val store = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)
        store.saveThreadId("selected-thread")
        store.record(receipt("selected-thread", "selected-client", "selected"))
        val retained = receipt("other-thread", "other-client", "other")
        store.record(retained)
        val commitsBeforeClear = preferences.commitCount

        store.clearThreadId("selected-thread")

        assertEquals(commitsBeforeClear + 1, preferences.commitCount)
        val restartedStore = AppPrivateCodexSessionStore(
            InMemorySharedPreferences(preferences.snapshot()).proxy,
            WORKSPACE,
        )
        assertNull(restartedStore.readThreadId())
        assertNull(restartedStore.read("selected-thread", "selected-client"))
        assertEquals(retained, restartedStore.read("other-thread", "other-client"))
    }

    @Test
    fun receiptJournalRetainsOnlyTheNewestOneHundredTwentyEightRecords() {
        val preferences = InMemorySharedPreferences()
        val store = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)
        repeat(130) { index ->
            store.record(receipt("thread-$index", "client-$index", "visible-$index"))
        }

        val restartedStore = AppPrivateCodexSessionStore(
            InMemorySharedPreferences(preferences.snapshot()).proxy,
            WORKSPACE,
        )
        assertNull(restartedStore.read("thread-0", "client-0"))
        assertNull(restartedStore.read("thread-1", "client-1"))
        repeat(128) { offset ->
            val index = offset + 2
            assertEquals(
                receipt("thread-$index", "client-$index", "visible-$index"),
                restartedStore.read("thread-$index", "client-$index"),
            )
        }
        assertEquals(
            128,
            JSONObject(checkNotNull(preferences.snapshot()[RECEIPTS_KEY]))
                .getJSONArray("records")
                .length(),
        )
    }

    @Test
    fun malformedOrUnknownJournalFailsClosedAndCanBeReplacedByANewReceipt() {
        listOf(
            "not-json",
            """{"version":999,"records":[]}""",
            """{"version":1,"records":[{"threadId":"thread","clientId":"client","visibleTextDigests":["not-a-digest"],"attachmentPlaceholder":false}]}""",
        ).forEach { malformed ->
            val preferences = InMemorySharedPreferences(mapOf(RECEIPTS_KEY to malformed))
            val store = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)
            assertNull(store.read("thread", "client"))

            val replacement = receipt("replacement-thread", "replacement-client", "visible")
            store.record(replacement)
            val restartedStore = AppPrivateCodexSessionStore(
                InMemorySharedPreferences(preferences.snapshot()).proxy,
                WORKSPACE,
            )
            assertEquals(
                replacement,
                restartedStore.read("replacement-thread", "replacement-client"),
            )
        }
    }

    @Test
    fun failedCommitDoesNotCreateADurableReceiptAndPoisonsThatPreferencesInstance() {
        val preferences = InMemorySharedPreferences(failCommits = true)
        val store = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)
        val value = receipt("thread-1", "client-1", "visible")

        assertTrue(runCatching { store.record(value) }.isFailure)
        assertTrue(runCatching { store.read("thread-1", "client-1") }.isFailure)
        assertNull(preferences.snapshot()[RECEIPTS_KEY])

        val restartedStore = AppPrivateCodexSessionStore(
            InMemorySharedPreferences(preferences.snapshot()).proxy,
            WORKSPACE,
        )
        assertNull(restartedStore.read("thread-1", "client-1"))
    }

    @Test
    fun explicitRecoveryPreservesReferenceAndReceiptBytesInOneCommitAcrossRestart() {
        val retainedReceipt = receipt("selected-thread", "client-1", "visible input")
        val preferences = InMemorySharedPreferences(mapOf("unrelated" to "untouched"))
        val store = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)
        store.saveThreadId("selected-thread")
        store.record(retainedReceipt)
        val receiptBytes = checkNotNull(preferences.snapshot()[RECEIPTS_KEY]).toByteArray()
        val commitsBefore = preferences.commitCount

        assertTrue(store.preserveSelectedThreadForExplicitRecovery("selected-thread"))

        assertEquals(commitsBefore + 1, preferences.commitCount)
        assertEquals(setOf(THREAD_KEY, RECOVERY_KEY), preferences.lastCommittedKeys)
        val restartedPreferences = InMemorySharedPreferences(preferences.snapshot())
        val restartedStore = AppPrivateCodexSessionStore(restartedPreferences.proxy, WORKSPACE)
        assertNull(restartedStore.readThreadId())
        assertEquals(listOf("selected-thread"), preservedIds(restartedPreferences))
        assertEquals(retainedReceipt, restartedStore.read("selected-thread", "client-1"))
        assertArrayEquals(receiptBytes, checkNotNull(restartedPreferences.snapshot()[RECEIPTS_KEY]).toByteArray())
        assertEquals("untouched", restartedPreferences.snapshot()["unrelated"])
    }

    @Test
    fun explicitRecoveryNeverReadsOrRewritesEvenMalformedReceipts() {
        val rawReceipt = "  opaque malformed receipt bytes: \\u0020  "
        val preferences = InMemorySharedPreferences(
            mapOf(THREAD_KEY to "selected-thread", RECEIPTS_KEY to rawReceipt),
        )
        val store = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)

        assertTrue(store.preserveSelectedThreadForExplicitRecovery("selected-thread"))

        assertArrayEquals(rawReceipt.toByteArray(), checkNotNull(preferences.snapshot()[RECEIPTS_KEY]).toByteArray())
        assertFalse(RECEIPTS_KEY in preferences.readKeys)
        assertFalse(RECEIPTS_KEY in preferences.lastCommittedKeys)
    }

    @Test
    fun explicitRecoveryCompareAndSetRejectsMissingForeignOrInvalidSelectionWithoutWriting() {
        listOf(null, "another-thread", "", " ", "control\nthread", "t".repeat(257)).forEach { selected ->
            val preferences = InMemorySharedPreferences(
                mapOf(THREAD_KEY to selected, RECOVERY_KEY to "malformed", RECEIPTS_KEY to "opaque"),
            )
            val before = preferences.snapshot()
            val store = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)
            assertFalse(store.preserveSelectedThreadForExplicitRecovery("expected-thread"))
            assertEquals(before, preferences.snapshot())
            assertEquals(0, preferences.commitCount)
        }
        listOf("", " ", "control\nthread", "t".repeat(257)).forEach { invalid ->
            val preferences = InMemorySharedPreferences(mapOf(THREAD_KEY to invalid))
            val store = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)
            assertFalse(store.preserveSelectedThreadForExplicitRecovery(invalid))
            assertEquals(0, preferences.commitCount)
        }
    }

    @Test
    fun explicitRecoveryAppendsWithoutEvictionAndIsIdempotentForAnExistingReference() {
        val preferences = InMemorySharedPreferences(
            mapOf(THREAD_KEY to "selected-thread", RECOVERY_KEY to "[\"earlier-thread\"]"),
        )
        val store = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)
        assertTrue(store.preserveSelectedThreadForExplicitRecovery("selected-thread"))
        assertEquals(listOf("earlier-thread", "selected-thread"), preservedIds(preferences))
        val commitsAfter = preferences.commitCount
        assertFalse(store.preserveSelectedThreadForExplicitRecovery("selected-thread"))
        assertEquals(commitsAfter, preferences.commitCount)

        store.saveThreadId("selected-thread")
        val priorReferences = preferences.snapshot()[RECOVERY_KEY]
        assertTrue(store.preserveSelectedThreadForExplicitRecovery("selected-thread"))
        assertEquals(priorReferences, preferences.snapshot()[RECOVERY_KEY])
        assertEquals(setOf(THREAD_KEY), preferences.lastCommittedKeys)
        assertNull(store.readThreadId())
    }

    @Test
    fun malformedExplicitRecoveryReferencesFailClosedWithoutLosingSelection() {
        val malformed = listOf(
            null, "", "not-json", "{}", "null", "[null]", "[1]", "[true]", "[{}]", "[[]]",
            "[\"\"]", "[\"  \"]", "[\"bad\\nthread\"]", "[unquoted]", "['single-quotes']",
            "[\"same\",\"same\"]", "[\"ok\",]", "[\"ok\"] trailing", "[\"ok\"]\u0000",
            JSONArray(listOf("t".repeat(257))).toString(),
            JSONArray(List(65) { "thread-$it" }).toString(),
            " ".repeat(100_000) + "[]",
            "[".repeat(20_000),
        )
        malformed.forEach { raw ->
            val preferences = InMemorySharedPreferences(
                mapOf(THREAD_KEY to "selected-thread", RECOVERY_KEY to raw, RECEIPTS_KEY to "opaque"),
            )
            val before = preferences.snapshot()
            val store = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)
            assertFalse(store.preserveSelectedThreadForExplicitRecovery("selected-thread"))
            assertEquals(before, preferences.snapshot())
            assertEquals("selected-thread", store.readThreadId())
            assertEquals(0, preferences.commitCount)
        }
    }

    @Test
    fun explicitRecoveryNeverEvictsAFullReferenceListButExistingReferencesNeedNoAdditionalSlot() {
        val ids = List(64) { "thread-$it" }
        val preferences = InMemorySharedPreferences(
            mapOf(THREAD_KEY to "new-thread", RECOVERY_KEY to JSONArray(ids).toString()),
        )
        val store = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)
        val before = preferences.snapshot()
        assertFalse(store.preserveSelectedThreadForExplicitRecovery("new-thread"))
        assertEquals(before, preferences.snapshot())
        assertEquals(0, preferences.commitCount)

        store.saveThreadId(ids.last())
        assertTrue(store.preserveSelectedThreadForExplicitRecovery(ids.last()))
        assertEquals(ids, preservedIds(preferences))
        assertNull(store.readThreadId())
    }

    @Test
    fun explicitRecoveryCommitFailureOrThrowPoisonsAllStoresSharingPossiblyModifiedRam() {
        listOf(false, true).forEach { throws ->
            val original = mapOf(
                THREAD_KEY to "selected-thread",
                RECOVERY_KEY to "[\"earlier-thread\"]",
                RECEIPTS_KEY to "opaque-receipt",
                "unrelated" to "untouched",
            )
            val preferences = InMemorySharedPreferences(
                original,
                failCommits = !throws,
                throwOnCommit = throws,
                mutateMemoryOnFailedCommit = true,
            )
            val store = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)
            val otherStore = AppPrivateCodexSessionStore(preferences.proxy, WORKSPACE)
            assertTrue(runCatching { store.preserveSelectedThreadForExplicitRecovery("selected-thread") }.isFailure)
            assertEquals(original, preferences.snapshot())
            assertTrue(runCatching { store.readThreadId() }.isFailure)
            assertTrue(runCatching { otherStore.readThreadId() }.isFailure)
            assertTrue(runCatching { otherStore.preserveSelectedThreadForExplicitRecovery("selected-thread") }.isFailure)
            assertTrue(runCatching { otherStore.saveThreadId("replacement-thread") }.isFailure)
            assertEquals(1, preferences.commitCount)
            val restartedStore = AppPrivateCodexSessionStore(
                InMemorySharedPreferences(preferences.snapshot()).proxy,
                WORKSPACE,
            )
            assertEquals("selected-thread", restartedStore.readThreadId())
        }
    }

    private fun preservedIds(preferences: InMemorySharedPreferences): List<String> {
        val array = JSONArray(checkNotNull(preferences.snapshot()[RECOVERY_KEY]))
        return List(array.length()) { array.getString(it) }
    }

    private fun receipt(threadId: String, clientId: String, visibleText: String) = checkNotNull(
        VisibleInputReceipt.fromInputs(
            threadId = threadId,
            clientId = clientId,
            inputs = listOf(CodexInput.Text(visibleText)),
        ),
    )

    /** Minimal transactional SharedPreferences surface used by the real app-private store. */
    private class InMemorySharedPreferences(
        preferences: Map<String, String?> = emptyMap(),
        var failCommits: Boolean = false,
        var throwOnCommit: Boolean = false,
        var mutateMemoryOnFailedCommit: Boolean = false,
    ) {
        private val values = preferences.toMutableMap()
        private val durableValues = preferences.toMutableMap()
        val readKeys = mutableSetOf<String>()
        var commitCount: Int = 0
            private set
        var lastCommittedKeys: Set<String> = emptySet()
            private set

        val proxy: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { self, method, args ->
            when (method.name) {
                "getString" -> {
                    val key = args!![0] as String
                    readKeys += key
                    values[key] ?: args[1]
                }
                "contains" -> values.containsKey(args!![0] as String)
                "edit" -> editor()
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args!![0]
                "toString" -> "In-memory SharedPreferences"
                else -> error("Unexpected preferences operation: ${method.name}")
            }
        } as SharedPreferences

        fun snapshot(): Map<String, String?> = durableValues.toMap()

        private fun editor(): SharedPreferences.Editor {
            val puts = mutableMapOf<String, String?>()
            val removals = mutableSetOf<String>()
            return Proxy.newProxyInstance(
                SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java),
            ) { self, method, args ->
                when (method.name) {
                    "putString" -> {
                        val key = args!![0] as String
                        puts[key] = args[1] as String?
                        removals.remove(key)
                        self
                    }
                    "remove" -> {
                        val key = args!![0] as String
                        puts.remove(key)
                        removals += key
                        self
                    }
                    "commit" -> {
                        commitCount += 1
                        lastCommittedKeys = puts.keys + removals
                        if (mutateMemoryOnFailedCommit) {
                            removals.forEach(values::remove)
                            values.putAll(puts)
                        }
                        if (throwOnCommit) error("Synthetic commit failure")
                        if (failCommits) {
                            false
                        } else {
                            removals.forEach(values::remove)
                            values.putAll(puts)
                            removals.forEach(durableValues::remove)
                            durableValues.putAll(puts)
                            true
                        }
                    }
                    else -> error("Unexpected editor operation: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }

    private companion object {
        const val WORKSPACE = "/synthetic/workspace"
        const val RECEIPTS_KEY = "visible_input_receipts"
        const val THREAD_KEY = "thread_id"
        const val RECOVERY_KEY = "explicit_recovery_thread_ids"
    }
}
