package ai.hans.standard.backup

import android.content.Context
import ai.hans.standard.automations.AutomationStorageSnapshot
import ai.hans.standard.profile.UserProfileDocument
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.settings.ReadAloudMode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual journal serializer only, never the application's preferences or journal. */
@RunWith(AndroidJUnit4::class)
class HansLiveVoiceBackupJournalTest {
    private lateinit var context: Context
    private lateinit var testDirectory: File
    private lateinit var journalFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDirectory = Files.createTempDirectory(
            context.cacheDir.toPath(),
            "live-voice-backup-journal-test-",
        ).toFile()
        journalFile = File(testDirectory, "rollback.json")
    }

    @After
    fun tearDown() {
        if (!::testDirectory.isInitialized) return
        check(testDirectory.parentFile == context.cacheDir)
        check(testDirectory.name.startsWith("live-voice-backup-journal-test-"))
        assertTrue(testDirectory.deleteRecursively())
    }

    @Test
    fun serializedRollbackRetainsLiveWillowIndependentlyFromTtsNova() {
        val before = rollbackState()
        journal().stage(before)

        val serializedSettings = JSONObject(
            JSONObject(journalFile.readText(Charsets.UTF_8)).getString("before"),
        ).getJSONObject("settings")
        assertEquals("willow", serializedSettings.getString("liveVoice"))
        assertEquals("nova", serializedSettings.getString("voice"))
        val reopened = journal()
        assertEquals(before, reopened.read())

        reopened.clear()
        assertNull(journal().read())
    }

    @Test
    fun missingNullAndPresentLiveVoiceAndServiceTierFieldsMigrateIndependently() {
        listOf("missing", "null", "present").forEach { liveVoiceShape ->
            listOf("missing", "null", "present").forEach { serviceTierShape ->
                val before = rollbackState()
                journal().stage(before)
                rewriteSettingsWithValidIntegrity { settings ->
                    settings.applyLegacyShape("liveVoice", liveVoiceShape)
                    settings.applyLegacyShape("serviceTier", serviceTierShape)
                }

                val expectedLiveVoice = if (liveVoiceShape == "present") "willow" else "ripple"
                val expectedServiceTier = if (serviceTierShape == "present") {
                    HansSettings.FAST_SERVICE_TIER
                } else {
                    HansSettings.DEFAULT_SERVICE_TIER
                }
                val restored = checkNotNull(journal().read())
                val case = "liveVoice=$liveVoiceShape, serviceTier=$serviceTierShape"
                assertEquals(case, expectedLiveVoice, restored.settings.liveVoice)
                assertEquals(case, "nova", restored.settings.voice)
                assertEquals(
                    case,
                    before.copy(settings = before.settings.copy(
                        liveVoice = expectedLiveVoice,
                        serviceTier = expectedServiceTier,
                    )),
                    restored,
                )
            }
        }
    }

    @Test
    fun invalidLiveVoiceWithValidJournalIntegrityIsRejectedWithoutTtsFallback() {
        listOf("nova", "Willow", "", "unknown-live-voice").forEach { invalidLiveVoice ->
            journal().stage(rollbackState())
            rewriteSettingsWithValidIntegrity { it.put("liveVoice", invalidLiveVoice) }

            val failure = assertThrows(IllegalArgumentException::class.java) {
                journal().read()
            }

            assertEquals(invalidLiveVoice, "Unsupported Live voice id", failure.message)
        }
    }

    private fun journal() = AndroidHansBackupStateGateway.AtomicBackupImportJournal(
        context,
        journalFile,
    )

    private fun rollbackState() = HansBackupRollbackState(
        settings = HansSettings(
            serviceTier = HansSettings.FAST_SERVICE_TIER,
            voice = "nova",
            speechRate = 1.5f,
            readAloudMode = ReadAloudMode.FINAL_ONLY,
            liveVoice = "willow",
        ),
        profile = UserProfileDocument(confirmedSummary = "Vorheriges Testprofil"),
        automations = AutomationStorageSnapshot(),
        pluginChoices = StoredPluginChoices(),
    )

    private fun JSONObject.applyLegacyShape(field: String, shape: String) {
        when (shape) {
            "missing" -> remove(field)
            "null" -> put(field, JSONObject.NULL)
            "present" -> check(has(field) && !isNull(field))
            else -> error("Unsupported test field shape")
        }
    }

    private fun rewriteSettingsWithValidIntegrity(edit: (JSONObject) -> Unit) {
        val envelope = JSONObject(journalFile.readText(Charsets.UTF_8))
        val before = JSONObject(envelope.getString("before"))
        edit(before.getJSONObject("settings"))
        val serializedBefore = before.toString()
        // The journal hashes its exact stored before-image string, not portable-backup canonical JSON.
        envelope.put("before", serializedBefore)
        envelope.put("sha256", sha256(serializedBefore.toByteArray(Charsets.UTF_8)))
        journalFile.writeText(envelope.toString(), Charsets.UTF_8)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
