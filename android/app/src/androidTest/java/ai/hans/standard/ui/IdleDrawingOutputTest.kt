package ai.hans.standard.ui

import org.junit.Assert.*
import org.junit.Test

class IdleDrawingOutputTest {
    @Test fun disabledDrawingIsEnabledThenRestoredExactly() {
        var current = false
        val writes = mutableListOf<Boolean>()
        val controller = IdleDrawingOutput(true, { current }, { writes += it; current = it })
        controller.prepare()
        assertEquals(true, controller.requireEnabled())
        controller.observationFinished()
        controller.restore()
        assertEquals(listOf(true, false), writes)
        val json = controller.json()
        assertEquals(7, json.length())
        assertFalse(json.getBoolean("initialEnabled"))
        assertTrue(json.getBoolean("enabledForProbe"))
        assertTrue(json.getBoolean("effectiveBeforeActivity"))
        assertTrue(json.getBoolean("effectiveAfterObservation"))
        assertFalse(json.getBoolean("restoredEnabled"))
        assertTrue(json.getBoolean("restorationComplete"))
    }

    @Test fun enabledDrawingNeedsNoSetter() {
        val controller = IdleDrawingOutput(true, { true }, { error("unexpected write") })
        controller.prepare()
        controller.observationFinished()
        controller.restore()
        assertFalse(controller.json().getBoolean("enabledForProbe"))
        assertTrue(controller.json().getBoolean("restoredEnabled"))
    }

    @Test fun unsupportedApiNeverInvokesPublicGetterOrSetter() {
        val controller = IdleDrawingOutput(false, { error("getter") }, { error("setter") })
        controller.prepare()
        assertNull(controller.requireEnabled())
        controller.observationFinished()
        controller.restore()
        val json = controller.json()
        assertEquals(7, json.length())
        for (key in listOf("initialEnabled", "effectiveBeforeActivity", "effectiveAfterObservation", "restoredEnabled")) {
            assertTrue(json.isNull(key))
        }
        assertFalse(json.getBoolean("apiSupported"))
        assertFalse(json.getBoolean("enabledForProbe"))
        assertTrue(json.getBoolean("restorationComplete"))
    }

    @Test fun ineffectiveEnableFailsAndStillRestoresOriginal() {
        val controller = IdleDrawingOutput(true, { false }, {})
        assertThrows(IllegalStateException::class.java) { controller.prepare() }
        controller.restore()
        assertFalse(controller.json().getBoolean("effectiveBeforeActivity"))
        assertTrue(controller.json().getBoolean("restorationComplete"))
    }

    @Test fun failedRestoreNeverClaimsCompletionAndPreservesPrimary() {
        var current = false
        val controller = IdleDrawingOutput(true, { current }, { if (it) current = true })
        controller.prepare()
        val primary = AssertionError("measurement failed")
        retainIdleFailure(primary) { controller.restore() }
        assertEquals(1, primary.suppressed.size)
        assertFalse(controller.json().getBoolean("restorationComplete"))
    }

    @Test fun midObservationDisableFailsRatherThanReportingEnabled() {
        var current = true
        val controller = IdleDrawingOutput(true, { current }, { current = it })
        controller.prepare()
        current = false
        assertThrows(IllegalStateException::class.java) { controller.requireEnabled() }
        assertThrows(IllegalStateException::class.java) { controller.observationFinished() }
        controller.restore()
        assertTrue(current)
        assertFalse(controller.json().getBoolean("effectiveAfterObservation"))
    }
}
