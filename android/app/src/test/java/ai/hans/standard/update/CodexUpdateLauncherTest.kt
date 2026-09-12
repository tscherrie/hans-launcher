package ai.hans.standard.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodexUpdateLauncherTest {
    @Test
    fun blankStagingUrlExplainsBundledUpdateWithoutTouchingPlatform() {
        val platform = FakePlatform()

        val result = CodexUpdateLauncher("", platform).launch()

        assertEquals(CodexUpdateLaunchResult.BUNDLED_WITH_HANS, result)
        assertEquals(0, platform.internetChecks)
        assertEquals(0, platform.handlerChecks)
        assertEquals(0, platform.openCalls)
    }

    @Test
    fun invalidOrUnsafeUrlFailsBeforeNetworkAndBrowser() {
        listOf(
            "http://updates.example/hans",
            "HTTPS://updates.example/hans",
            "https://user:secret@updates.example/hans",
            "https://updates.example/hans#mutable",
            "https://updates.example:8443/hans",
            " https://updates.example/hans",
            "   ",
            "https://updates.example\\hans",
            "https://updates.example/h\u00e4ns",
        ).forEach { url ->
            val platform = FakePlatform()

            assertEquals(
                url,
                CodexUpdateLaunchResult.INVALID_CONFIGURATION,
                CodexUpdateLauncher(url, platform).launch(),
            )
            assertEquals(0, platform.internetChecks)
            assertEquals(0, platform.handlerChecks)
            assertEquals(0, platform.openCalls)
        }
        assertNull(validatedFixedHttpsUrlOrNull("https://updates.example/hans#mutable"))
    }

    @Test
    fun offlineAndMissingBrowserNeverAttemptToOpen() {
        val offline = FakePlatform(internet = false)
        assertEquals(
            CodexUpdateLaunchResult.OFFLINE,
            CodexUpdateLauncher(UPDATE_URL, offline).launch(),
        )
        assertEquals(1, offline.internetChecks)
        assertEquals(0, offline.handlerChecks)
        assertEquals(0, offline.openCalls)

        val missing = FakePlatform(handler = false)
        assertEquals(
            CodexUpdateLaunchResult.NO_BROWSER,
            CodexUpdateLauncher(UPDATE_URL, missing).launch(),
        )
        assertEquals(1, missing.internetChecks)
        assertEquals(1, missing.handlerChecks)
        assertEquals(0, missing.openCalls)
    }

    @Test
    fun validFixedUrlOpensExactlyOnce() {
        val platform = FakePlatform()

        assertEquals(
            CodexUpdateLaunchResult.OPENED,
            CodexUpdateLauncher(UPDATE_URL, platform).launch(),
        )
        assertEquals(1, platform.internetChecks)
        assertEquals(1, platform.handlerChecks)
        assertEquals(1, platform.openCalls)
        assertEquals(UPDATE_URL, platform.lastOpenedUrl)
    }

    @Test
    fun browserFailureIsReportedWithoutRetrying() {
        val platform = FakePlatform(throwOnOpen = true)

        assertEquals(
            CodexUpdateLaunchResult.OPEN_FAILED,
            CodexUpdateLauncher(UPDATE_URL, platform).launch(),
        )
        assertEquals(1, platform.openCalls)
    }

    private class FakePlatform(
        private val internet: Boolean = true,
        private val handler: Boolean = true,
        private val throwOnOpen: Boolean = false,
    ) : CodexUpdatePlatform {
        var internetChecks = 0
        var handlerChecks = 0
        var openCalls = 0
        var lastOpenedUrl: String? = null

        override fun hasValidatedInternet(): Boolean {
            internetChecks += 1
            return internet
        }

        override fun canOpen(url: String): Boolean {
            handlerChecks += 1
            return handler
        }

        override fun open(url: String) {
            openCalls += 1
            lastOpenedUrl = url
            if (throwOnOpen) error("browser_failed")
        }
    }

    private companion object {
        const val UPDATE_URL = "https://updates.example/hans"
    }
}
