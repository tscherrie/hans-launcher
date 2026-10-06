package ai.hans.standard.integration

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Xml
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Disposable emulator preferences only: no runtime, accounts, actual conversations or network. */
@RunWith(AndroidJUnit4::class)
class ExplicitThreadRecoveryPersistenceAndroidTest {
    @Test
    fun recoveryCommitsOldReferenceAndUnchangedReceiptBytesToDisk() = withPreferences { fixture ->
        val receipt = " \nopaque receipt: \\u0020 — must remain byte-for-byte unchanged\t "
        check(fixture.preferences.edit()
            .putString(THREAD_KEY, "synthetic-selected-thread")
            .putString(RECOVERY_KEY, "[\"synthetic-earlier-thread\"]")
            .putString(RECEIPTS_KEY, receipt)
            .putString("unrelated-string", "synthetic preference")
            .putBoolean("unrelated-boolean", true)
            .putInt("unrelated-integer", 42)
            .commit())
        val before = fixture.preferences.all.toMap()

        assertTrue(fixture.store().preserveSelectedThreadForExplicitRecovery("synthetic-selected-thread"))

        assertNull(fixture.store().readThreadId())
        val expectedReferences = JSONArray(listOf("synthetic-earlier-thread", "synthetic-selected-thread")).toString()
        assertEquals(
            before.minus(THREAD_KEY) + (RECOVERY_KEY to expectedReferences),
            fixture.preferences.all,
        )
        // Read the actual committed XML, not merely Android's cached SharedPreferences object.
        // This is the durable state a new process loads; no app/process restart is claimed here.
        val diskStrings = fixture.committedStringValues()
        assertFalse(THREAD_KEY in diskStrings)
        assertEquals(expectedReferences, diskStrings[RECOVERY_KEY])
        assertArrayEquals(receipt.toByteArray(), checkNotNull(diskStrings[RECEIPTS_KEY]).toByteArray())
        assertEquals("synthetic preference", diskStrings["unrelated-string"])
    }

    @Test
    fun mismatchedSelectionCorruptJournalAndFullJournalLeaveDiskUntouched() = withPreferences { fixture ->
        val full = JSONArray(List(64) { "synthetic-thread-$it" }).toString()
        listOf(
            "synthetic-other-thread" to "not-json",
            "synthetic-selected-thread" to "[\"synthetic-reference\",null]",
            "synthetic-selected-thread" to "[\"synthetic-reference\"] trailing",
            "synthetic-selected-thread" to full,
        ).forEach { (selected, journal) ->
            check(fixture.preferences.edit()
                .putString(THREAD_KEY, selected)
                .putString(RECOVERY_KEY, journal)
                .putString(RECEIPTS_KEY, "opaque synthetic receipt")
                .commit())
            val before = fixture.preferences.all.toMap()
            val diskBefore = fixture.file.readBytes()

            assertFalse(fixture.store().preserveSelectedThreadForExplicitRecovery("synthetic-selected-thread"))

            assertEquals(before, fixture.preferences.all)
            assertArrayEquals(diskBefore, fixture.file.readBytes())
        }
    }

    @Test
    fun wrongTypedJournalFailsClosedAndDuplicatePreservationDoesNotAppend() = withPreferences { fixture ->
        check(fixture.preferences.edit()
            .putString(THREAD_KEY, "synthetic-selected-thread")
            .putInt(RECOVERY_KEY, 7)
            .commit())
        val before = fixture.preferences.all.toMap()
        val diskBefore = fixture.file.readBytes()
        assertFalse(fixture.store().preserveSelectedThreadForExplicitRecovery("synthetic-selected-thread"))
        assertEquals(before, fixture.preferences.all)
        assertArrayEquals(diskBefore, fixture.file.readBytes())

        val journal = "[\"synthetic-selected-thread\"]"
        check(fixture.preferences.edit().putString(RECOVERY_KEY, journal).commit())
        assertTrue(fixture.store().preserveSelectedThreadForExplicitRecovery("synthetic-selected-thread"))
        assertEquals(journal, fixture.committedStringValues()[RECOVERY_KEY])
        assertFalse(fixture.store().preserveSelectedThreadForExplicitRecovery("synthetic-selected-thread"))
        assertNull(fixture.store().readThreadId())
    }

    private fun withPreferences(action: (Fixture) -> Unit) {
        assumeTrue("Disposable Android emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = PREFERENCES_PREFIX + UUID.randomUUID()
        try {
            action(Fixture(context, name))
        } finally {
            check(name.startsWith(PREFERENCES_PREFIX))
            context.deleteSharedPreferences(name)
        }
    }

    private class Fixture(context: Context, name: String) {
        val preferences: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val file = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")

        fun store() = AppPrivateCodexSessionStore(preferences, "/synthetic/unused-workspace")

        fun committedStringValues(): Map<String, String> = file.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, "UTF-8")
            buildMap {
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && parser.name == "string") {
                        val key = checkNotNull(parser.getAttributeValue(null, "name"))
                        put(key, parser.nextText())
                    }
                    event = parser.next()
                }
            }
        }
    }

    private companion object {
        const val PREFERENCES_PREFIX = "explicit_recovery_persistence_test_"
        const val THREAD_KEY = "thread_id"
        const val RECOVERY_KEY = "explicit_recovery_thread_ids"
        const val RECEIPTS_KEY = "visible_input_receipts"
    }
}
