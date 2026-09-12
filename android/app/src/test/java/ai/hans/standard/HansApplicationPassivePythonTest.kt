package ai.hans.standard

import org.junit.Assert.assertNull
import org.junit.Test

class HansApplicationPassivePythonTest {
    @Test
    fun workbenchSnapshotDoesNotConstructPythonForAnUninitializedApplication() {
        // HansApplication is intentionally unattached here. If the passive accessor touched the
        // lazy supervisor it would immediately need an Android application context and fail.
        val application = HansApplication()

        assertNull(application.passiveInitializedPythonRuntimeSnapshot())
        assertNull(application.passiveInitializedPythonRuntimeSnapshot())
    }
}
