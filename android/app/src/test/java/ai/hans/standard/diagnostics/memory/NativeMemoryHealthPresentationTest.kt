package ai.hans.standard.diagnostics.memory

import ai.hans.standard.localization.TestResourceTextResolver

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class NativeMemoryHealthPresentationTest {
    private fun resolve(config: String, env: String? = null) = NativeMemorySqliteHome.resolve(
        "{\"result\":{\"config\":$config}}", "/owned/home", env, "/owned/work")
    @Test fun absentAndNullUseControlledFallback() {
        assertEquals(File("/owned/home"), resolve("{}"))
        assertEquals(File("/owned/home"), resolve("{\"sqlite_home\":null}"))
    }
    @Test fun effectiveRequirementsProjectionWinsOverEnvironment() {
        assertEquals(File("/owned/managed"), resolve("{\"sqlite_home\":\"/owned/managed\"}", "/owned/env"))
        assertEquals(File("/owned/work/relative"), resolve("{}", " relative "))
    }
    @Test fun malformedPathIsUnavailableNotFallback() {
        assertNull(resolve("{\"sqlite_home\":42}"))
        assertNull(resolve("{\"sqlite_home\":\"relative\"}"))
        assertNull(NativeMemorySqliteHome.resolve("{}", "/owned/home", null, "/owned/work"))
    }
    @Test fun metadataRoundTripPreservesCountsAndGenerationIsRequired() {
        val result = NativeMemoryHealthResult.Available(NativeMemoryHealthSnapshot(2,1,3,100,200,emptyList()))
        val wire = NativeMemoryHealthWire.encode(result, 9)
        assertEquals(result, NativeMemoryHealthWire.decode(wire))
        assertTrue(wire.contains("\"generation\":9"))
        assertThrows(IllegalArgumentException::class.java) { NativeMemoryHealthWire.decode(NativeMemoryHealthWire.encode(result)) }
    }
    @Test fun strictWireRejectsUnknownFieldsDuplicateKeysAndNumericCoercion() {
        val wire = NativeMemoryHealthWire.encode(NativeMemoryHealthResult.Available(NativeMemoryHealthSnapshot(2,1,3,null,null,emptyList())),1)
        for (bad in listOf(wire.replace("\"count\":2", "\"count\":\"2\""),
            wire.replace("\"count\":2", "\"count\":2,\"count\":3"), wire.dropLast(1)+",\"private\":\"text\"}")) {
            assertThrows(Exception::class.java) { NativeMemoryHealthWire.decode(bad) }
        }
    }
    @Test fun allTypedFailuresRoundTripWithoutRawDetails() {
        NativeMemoryUnavailableReason.entries.forEach { reason ->
            val result = NativeMemoryHealthResult.Unavailable(reason)
            assertEquals(result, NativeMemoryHealthWire.decode(NativeMemoryHealthWire.encode(result)))
            assertFalse(NativeMemoryHealthPresentation.describe(result, text = TestResourceTextResolver(java.util.Locale.GERMAN)).contains(reason.name))
        }
    }
    @Test fun emptyHealthDoesNotClaimLearningOrUniversalConsumption() {
        val text = NativeMemoryHealthPresentation.describe(NativeMemoryHealthResult.Available(
            NativeMemoryHealthSnapshot(0,0,0,null,null,emptyList())), text = TestResourceTextResolver(java.util.Locale.GERMAN))
        assertTrue(text.contains("Registrierte Nutzungen: 0"))
        assertTrue(text.contains("noch nicht belegt"))
        assertTrue(NativeMemoryHealthPresentation.disclosure(TestResourceTextResolver(java.util.Locale.GERMAN)).contains("nicht, dass jede Antwort"))
    }
    @Test fun extremeTimestampsCannotCrashStatusScreen() {
        val text = NativeMemoryHealthPresentation.describe(NativeMemoryHealthResult.Available(
            NativeMemoryHealthSnapshot(1,0,0,Long.MAX_VALUE,null,emptyList())), text = TestResourceTextResolver(java.util.Locale.GERMAN))
        assertTrue(text.contains("Zeitwert nicht darstellbar"))
    }
}
