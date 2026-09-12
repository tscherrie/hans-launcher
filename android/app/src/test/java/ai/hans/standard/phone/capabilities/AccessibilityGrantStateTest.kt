package ai.hans.standard.phone.capabilities

import android.database.Cursor
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityGrantStateTest {
    @Test
    fun fullAndShortFrameworkComponentsProveDurableGrantWithoutABoundService() {
        listOf(FULL_COMPONENT, SHORT_COMPONENT).forEach { component ->
            assertEquals(AccessibilityGrantState.GRANTED, probe(component))
            assertEquals(
                AccessibilityGrantState.GRANTED,
                probe("other.app/.Reader:$component:another.app/.Reader"),
            )
        }
    }

    @Test
    fun anotherServiceOrLookalikeComponentNeverGrantsHansAccess() {
        listOf(
            "other.app/.Reader",
            "ai.hans.standard/.OtherService",
            "$FULL_COMPONENT.extra",
            "prefix.$FULL_COMPONENT",
            "other.app/ai.hans.standard.phone.accessibility.android.HansAccessibilityService",
            "ai.hans.standard",
            "HansAccessibilityService",
        ).forEach { setting ->
            assertEquals(setting, AccessibilityGrantState.NOT_GRANTED, probe(setting))
        }
    }

    @Test
    fun absentOrEmptyGrantListIsARealRevocation() {
        listOf(null, "", ":", "::").forEach { setting ->
            assertEquals(AccessibilityGrantState.NOT_GRANTED, probe(setting))
        }
    }

    @Test
    fun nullProviderCursorIsUnknownRatherThanARevocation() {
        val state = readAccessibilityGrantState(FULL_COMPONENT, SHORT_COMPONENT) {
            queryAccessibilityGrantSetting { null }
        }
        assertEquals(AccessibilityGrantState.UNKNOWN, state)
        assertFalse(state == AccessibilityGrantState.GRANTED)
    }

    @Test
    fun successfulEmptyRowsAndNullOrEmptyValuesAreRealRevocationsAndCloseCursor() {
        listOf(false to null, true to null, true to "").forEach { (hasRow, value) ->
            val cursor = ProbeCursor(hasRow, value)
            assertEquals(AccessibilityGrantState.NOT_GRANTED, probeCursor(cursor))
            assertTrue(cursor.closed)
        }
    }

    @Test
    fun successfulGrantQueryClosesCursor() {
        val cursor = ProbeCursor(hasRow = true, value = FULL_COMPONENT)
        assertEquals(AccessibilityGrantState.GRANTED, probeCursor(cursor))
        assertTrue(cursor.closed)
    }

    @Test
    fun providerAndCursorFailuresStayUnknownAndDoNotLeakCursor() {
        val failedQuery = readAccessibilityGrantState(FULL_COMPONENT, SHORT_COMPONENT) {
            queryAccessibilityGrantSetting { throw SecurityException("provider unavailable") }
        }
        assertEquals(AccessibilityGrantState.UNKNOWN, failedQuery)
        listOf("moveToFirst", "getColumnIndexOrThrow", "getString", "close").forEach { failure ->
            val cursor = ProbeCursor(hasRow = true, value = FULL_COMPONENT, failAt = failure)
            assertEquals(failure, AccessibilityGrantState.UNKNOWN, probeCursor(cursor))
            assertTrue(failure, cursor.closed)
        }
    }

    @Test
    fun unreadableSettingIsUnknownAndNeverGrantsExecutionAccess() {
        listOf(SecurityException("unavailable"), IllegalStateException("provider restarting"))
            .forEach { failure ->
                val state = readAccessibilityGrantState(FULL_COMPONENT, SHORT_COMPONENT) {
                    throw failure
                }
                assertEquals(AccessibilityGrantState.UNKNOWN, state)
                assertFalse(state == AccessibilityGrantState.GRANTED)
            }
    }

    @Test
    fun everyProbeReadsCurrentGrantInsteadOfCachingPriorConsent() {
        var setting: String? = FULL_COMPONENT
        var reads = 0
        val read = {
            reads += 1
            AccessibilityGrantSetting(setting)
        }
        assertEquals(
            AccessibilityGrantState.GRANTED,
            readAccessibilityGrantState(FULL_COMPONENT, SHORT_COMPONENT, read),
        )
        setting = "other.app/.Reader"
        assertEquals(
            AccessibilityGrantState.NOT_GRANTED,
            readAccessibilityGrantState(FULL_COMPONENT, SHORT_COMPONENT, read),
        )
        setting = SHORT_COMPONENT
        assertEquals(
            AccessibilityGrantState.GRANTED,
            readAccessibilityGrantState(FULL_COMPONENT, SHORT_COMPONENT, read),
        )
        assertEquals(3, reads)
    }

    private fun probe(setting: String?): AccessibilityGrantState =
        readAccessibilityGrantState(FULL_COMPONENT, SHORT_COMPONENT) {
            AccessibilityGrantSetting(setting)
        }

    private fun probeCursor(cursor: ProbeCursor): AccessibilityGrantState =
        readAccessibilityGrantState(FULL_COMPONENT, SHORT_COMPONENT) {
            queryAccessibilityGrantSetting { cursor.cursor }
        }

    /** Implements only the public Cursor methods used by the provider probe. */
    private class ProbeCursor(
        private val hasRow: Boolean,
        private val value: String?,
        private val failAt: String? = null,
    ) {
        var closed = false
            private set

        val cursor = Proxy.newProxyInstance(
            Cursor::class.java.classLoader,
            arrayOf(Cursor::class.java),
        ) { _, method, _ ->
            if (method.name == "close") closed = true
            if (method.name == failAt) throw IllegalStateException("provider restarting")
            when (method.name) {
                "moveToFirst" -> hasRow
                "getColumnIndexOrThrow" -> 0
                "getString" -> value
                "close" -> null
                else -> error("Unexpected Cursor method: ${method.name}")
            }
        } as Cursor
    }

    private companion object {
        const val FULL_COMPONENT =
            "ai.hans.standard/ai.hans.standard.phone.accessibility.android.HansAccessibilityService"
        const val SHORT_COMPONENT =
            "ai.hans.standard/.phone.accessibility.android.HansAccessibilityService"
    }
}
