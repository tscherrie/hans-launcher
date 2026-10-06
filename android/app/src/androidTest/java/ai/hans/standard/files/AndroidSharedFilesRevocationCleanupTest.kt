package ai.hans.standard.files

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

class AndroidSharedFilesRevocationCleanupTest {
    @Test fun removesOnlyTheExactDisposableRevocationFixtureAfterRegrant() {
        AndroidSharedFilesTestFixture.cleanup(InstrumentationRegistry.getInstrumentation().targetContext)
    }
}
