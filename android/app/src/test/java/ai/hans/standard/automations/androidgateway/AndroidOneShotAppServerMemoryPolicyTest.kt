package ai.hans.standard.automations.androidgateway

import ai.hans.standard.runtime.CodexAppServerV1Policy
import ai.hans.standard.runtime.CodexAssistantProfile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidOneShotAppServerMemoryPolicyTest {
    @Test
    fun independentPersonalAutomationUsesTheSameMemoryPolicyAsMainHans() {
        val policy = CodexAssistantProfile.strictArguments()
        assertEquals(
            policy,
            CodexAppServerV1Policy.strictPersonalAssistantArguments(),
        )
        assertEquals(
            listOf("/data/app/hans/lib/arm64/libcodex_app_server.so") + policy,
            AndroidOneShotAppServerProcessContract.command(
                File("/data/app/hans/lib/arm64/libcodex_app_server.so"),
            ),
        )
    }
}
