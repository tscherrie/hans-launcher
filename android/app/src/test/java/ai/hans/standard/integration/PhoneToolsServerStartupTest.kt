package ai.hans.standard.integration

import java.io.IOException
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneToolsServerStartupTest {
    @Test fun optionalListenerFailureDoesNotThrowIntoLocalChatStartup() {
        assertNull(startPhoneToolsServerOrNull { throw IOException("private endpoint detail") })
    }
}
