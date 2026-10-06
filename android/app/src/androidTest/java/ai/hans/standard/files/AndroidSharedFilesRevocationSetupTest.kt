package ai.hans.standard.files

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSharedFilesRevocationSetupTest {
    @Test fun persistsGrantedFixtureAcrossExternalRevocation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = AndroidSharedFilesTestFixture.prepare(context)
        assertTrue(AndroidSharedFiles.store(context).listRecovery().any { it.handle == fixture.recovery.handle })
    }
}
