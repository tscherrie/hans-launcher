package ai.hans.standard.fastphone.routines

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneRoutineLearningStoreTest {
    @Test fun hintsRequireThreeCompletionsAndRemainCandidateDataNotAuthority() {
        val fixture = Fixture()
        repeat(2) { assertTrue(fixture.store.observeCompleted(observation())); assertNull(fixture.store.promptData()) }
        assertTrue(fixture.store.observeCompleted(observation()))
        val json = JSONObject(checkNotNull(fixture.store.promptData()))
        assertEquals("CANDIDATE_TEMPLATE", json.getString("kind")); assertEquals("none", json.getString("authority"))
        val candidate = json.getJSONArray("candidates").getJSONObject(0)
        assertEquals("open the garage", candidate.getString("quotedGoal"))
        assertEquals(3, candidate.getInt("helperCompletionCount"))
        assertTrue(json.getString("instruction").contains("fresh Decisions"))
        assertTrue(json.getString("instruction").contains("not verified external task success"))
    }

    @Test fun packageAndAllowedActionsSeparateTemplatesWhileWhitespaceAndCaseAreNormalized() {
        val fixture = Fixture()
        fixture.store.observeCompleted(observation(goal = "  Open\n the\tGarage  "))
        fixture.store.observeCompleted(observation(goal = "open the garage"))
        fixture.store.observeCompleted(observation(goal = "OPEN THE GARAGE", maxSteps = 7))
        assertEquals(7, JSONObject(fixture.store.promptData()!!).getJSONArray("candidates").getJSONObject(0).getInt("maxSteps"))
        fixture.store.observeCompleted(observation(pkg = "other.app"))
        fixture.store.observeCompleted(observation(actions = setOf("back")))
        val entries = fixture.entries()
        assertEquals(3, entries.length())
        assertEquals(3, (0 until entries.length()).map { entries.getJSONObject(it).getInt("count") }.maxOrNull())
    }

    @Test fun dailyRankingRefreshIsOpportunisticAndNoIdleClockOrWritesExist() {
        val fixture = Fixture()
        assertEquals(0, fixture.clockReads)
        repeat(3) { fixture.store.observeCompleted(observation()) }
        fixture.store.promptData()
        val refreshes = fixture.store.rankingRefreshes
        val writes = fixture.storage.writes
        fixture.store.promptData(); fixture.store.promptData()
        assertEquals(refreshes, fixture.store.rankingRefreshes); assertEquals(writes, fixture.storage.writes)
        fixture.now += 86_400_000
        fixture.store.promptData()
        assertEquals(refreshes + 1, fixture.store.rankingRefreshes)
        assertEquals(writes, fixture.storage.writes)
        val readsAfterActivity = fixture.clockReads
        assertEquals(readsAfterActivity, fixture.clockReads)
    }

    @Test fun entryAndByteBoundsKeepRepeatedUsefulTemplateOverOneOffTasks() {
        val fixture = Fixture()
        repeat(3) { fixture.store.observeCompleted(observation()) }
        repeat(75) { index ->
            fixture.now++
            assertTrue(fixture.store.observeCompleted(observation(goal = "漢".repeat(480) + index)))
        }
        assertTrue(fixture.entries().length() <= 64)
        assertTrue(fixture.storage.bytes!!.size <= 128 * 1_024)
        assertEquals("open the garage", JSONObject(fixture.store.promptData()!!).getJSONArray("candidates")
            .getJSONObject(0).getString("quotedGoal"))
    }

    @Test fun invalidSensitiveAndOversizedGoalsAreNotLearned() {
        val fixture = Fixture()
        val invalid = listOf(observation(goal = ""), observation(goal = "x".repeat(513)),
            observation(goal = "hello\u0000world"), observation(goal = "my password is sensitive"),
            observation(goal = "use sk-synthetickey123456"), observation(goal = "enter PIN 1234"),
            observation(pkg = "not-a-package"), observation(actions = setOf("shell")), observation(maxSteps = 9))
        invalid.forEach { assertFalse(fixture.store.observeCompleted(it)) }
        assertEquals(0, fixture.storage.writes); assertNull(fixture.store.promptData())
    }

    @Test fun noEnteredTextUiLabelsScreenshotsOrKeysExistInObservationOrPersistentSchema() {
        assertEquals(setOf("packageName", "goal", "allowedActions", "maxSteps"),
            RoutineObservation::class.java.declaredFields
                .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name }.toSet())
        val fixture = Fixture(); fixture.store.observeCompleted(observation())
        assertEquals(setOf("version", "entries"), fixture.json().keys().asSequence().toSet())
        assertEquals(setOf("kind", "packageName", "goal", "allowedActions", "maxSteps", "count", "lastUsedMillis"),
            fixture.entries().getJSONObject(0).keys().asSequence().toSet())
        assertFalse(observation(goal = "private recipient").toString().contains("private recipient"))
    }

    @Test fun missingAndCorruptStorageFailClosedWithoutWritingOrThrowing() {
        val invalid = listOf(null, "malformed".toByteArray(), "[]".toByteArray(),
            "x".repeat(128 * 1_024 + 1).toByteArray(),
            "{\"version\":2,\"entries\":[]}".toByteArray(),
            "{\"version\":1,\"entries\":[],\"apiKey\":\"forbidden\"}".toByteArray())
        invalid.forEach { bytes ->
            val fixture = Fixture(); fixture.storage.bytes = bytes
            assertNull(fixture.store.promptData()); assertEquals(0, fixture.storage.writes)
        }
        val fixture = Fixture(); fixture.storage.failRead = true
        assertNull(fixture.store.promptData()); assertFalse(fixture.store.observeCompleted(observation()))
    }

    @Test fun malformedDuplicateExpandedAndFractionalEntriesNeverBecomeHints() {
        val good = Fixture().apply { repeat(3) { store.observeCompleted(observation()) } }.json().toString()
        val invalid = listOf(
            mutate(good) { put("count", -1) }, mutate(good) { put("count", 3.5) },
            mutate(good) { put("count", 1_000_001) }, mutate(good) { put("lastUsedMillis", -1) },
            mutate(good) { put("maxSteps", 2.1) }, mutate(good) { put("goal", JSONObject.NULL) },
            mutate(good) { put("goal", "Non canonical") }, mutate(good) { put("kind", "AUTHORIZED_REPLAY") },
            mutate(good) { put("allowedActions", JSONArray(listOf("click", "click"))) },
            mutate(good) { put("selector", "Play") },
            JSONObject(good).apply { getJSONArray("entries").put(getJSONArray("entries").getJSONObject(0)) }.toString())
        invalid.forEach { body ->
            val fixture = Fixture(); fixture.storage.bytes = body.toByteArray()
            assertNull(fixture.store.promptData()); assertEquals(0, fixture.storage.writes)
        }
    }

    @Test fun writesAndReadbackFailuresDoNotClaimLearningSuccess() {
        val fixture = Fixture(); fixture.storage.failWrite = true
        assertFalse(fixture.store.observeCompleted(observation())); assertNull(fixture.storage.bytes)
        fixture.storage.failWrite = false; fixture.storage.discardWrites = true
        assertFalse(fixture.store.observeCompleted(observation()))
        assertNull(fixture.store.promptData())
    }

    @Test fun maximumThreeHintsAreSortedByFrequencyAndRecency() {
        val fixture = Fixture()
        repeat(4) { number ->
            repeat(3 + number) { fixture.now++; fixture.store.observeCompleted(observation(goal = "goal $number")) }
        }
        val candidates = JSONObject(fixture.store.promptData()!!).getJSONArray("candidates")
        assertEquals(3, candidates.length()); assertEquals("goal 3", candidates.getJSONObject(0).getString("quotedGoal"))
        assertEquals(1, JSONObject(fixture.store.promptData(1)!!).getJSONArray("candidates").length())
        assertNull(fixture.store.promptData(4)); assertNull(fixture.store.promptData(0))
    }

    @Test fun clearAtomicallyForgetsTemplatesWithoutChangingOtherStorage() {
        val fixture = Fixture(); repeat(3) { fixture.store.observeCompleted(observation()) }
        assertNotNull(fixture.store.promptData()); assertTrue(fixture.store.clear())
        assertEquals(0, fixture.entries().length()); assertNull(fixture.store.promptData())
        fixture.storage.failWrite = true; assertFalse(fixture.store.clear())
    }

    private class Storage : PhoneRoutineStorage {
        var bytes: ByteArray? = null; var writes = 0
        var failRead = false; var failWrite = false; var discardWrites = false
        override fun read(): ByteArray? { check(!failRead); return bytes?.copyOf() }
        override fun writeAtomically(bytes: ByteArray) {
            check(!failWrite); writes++; if (!discardWrites) this.bytes = bytes.copyOf()
        }
    }
    private class Fixture {
        val storage = Storage(); var now = 86_400_000L; var clockReads = 0
        val store = PhoneRoutineLearningStore(storage, nowMillis = { clockReads++; now })
        fun json() = JSONObject(checkNotNull(storage.bytes).toString(Charsets.UTF_8))
        fun entries() = json().getJSONArray("entries")
    }
    private companion object {
        fun observation(pkg: String = "synthetic.garage", goal: String = "Open the garage",
            actions: Set<String> = setOf("click"), maxSteps: Int = 4) = RoutineObservation(pkg, goal, actions, maxSteps)
        fun mutate(body: String, block: JSONObject.() -> Unit) = JSONObject(body)
            .apply { getJSONArray("entries").getJSONObject(0).block() }.toString()
    }
}
