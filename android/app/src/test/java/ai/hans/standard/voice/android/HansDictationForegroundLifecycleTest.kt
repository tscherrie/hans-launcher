package ai.hans.standard.voice.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HansDictationForegroundLifecycleTest {
    @Test
    fun serviceTeardownWaitsForCoreCleanupBeforeClosingProviderAndDispatcher() {
        val base = source("voice/android/BaseDictationForegroundService.kt")
        val destroy = base.substringAfter("final override fun onDestroy()")
            .substringBefore("private fun stopCoreAndReleaseResources()")
        val teardown = base.substringAfter("private fun stopCoreAndReleaseResources()")
            .substringBefore("protected open fun releaseRecordingResources()")
        val initializer = base.substringAfter("private fun requireServiceCore()")

        assertTrue(destroy.contains("stopCoreAndReleaseResources()"))
        assertFalse(destroy.contains("releaseRecordingResources()"))
        assertTrue(teardown.contains("initializedCore.onProcessStopping {"))
        assertTrue(teardown.contains("lifecycleHandler.post { release() }"))
        assertTrue(teardown.contains("if (teardownStarted) return"))
        assertTrue(initializer.contains("check(!teardownStarted)"))
        assertFalse(teardown.contains(".await("))
        assertFalse(teardown.contains(".join("))
    }

    @Test
    fun newServiceCannotOverlapOldCleanupOrPublishWithReusedRecordingIds() {
        val service = source("voice/android/HansDictationService.kt")
        val factory = service.substringAfter("override fun createServiceCore(")
            .substringBefore("override fun onRecordingStartCommand(")
        val listener = service.substringAfter("override fun onRecordingStateChanged(")
            .substringBefore("private fun requestRecording()")
        val release = service.substringAfter("override fun releaseRecordingResources()")
            .substringBefore("companion object")

        assertTrue(factory.indexOf("serviceOwners.acquire(this)") in 0 until factory.indexOf("ExecutorRecordingDeadlineScheduler()"))
        assertTrue(factory.contains("nextRecordingId = processRecordingIds::next"))
        assertEquals(2, listener.windowed("serviceOwners.runIfOwner(this)".length)
            .count { it == "serviceOwners.runIfOwner(this)" })
        assertTrue(release.contains("serviceOwners.release(this)"))
        assertTrue(release.indexOf("serviceOwners.release(this)") > release.indexOf("dispatcher?.close()"))
    }

    @Test
    fun coldForegroundStartAcknowledgesBeforeLazyCoreOrCredentialAndNetworkChecks() {
        val base = source("voice/android/BaseDictationForegroundService.kt")
        val service = source("voice/android/HansDictationService.kt")
        val onCreate = base.substringAfter("final override fun onCreate()")
            .substringBefore("protected abstract fun createServiceCore")
        val onStart = base.substringAfter("final override fun onStartCommand(")
            .substringBefore("protected open fun requiresForegroundAcknowledgement")
        val lazyInitializer = base.substringAfter("private fun requireServiceCore()")
        val prerequisites = service.substringAfter("private fun requestRecording()")
            .substringBefore("override fun onStartRejected")

        assertFalse(onCreate.contains("createServiceCore(recordingForegroundHost)"))
        assertTrue(onStart.indexOf("recordingForegroundHost::acknowledgeForegroundServiceStart") in
            0 until onStart.indexOf("onRecordingStartCommand(intent, flags, startId)"))
        assertTrue(lazyInitializer.contains("createServiceCore(recordingForegroundHost)"))
        assertTrue(service.contains("requiresForegroundStart(intent?.action)"))
        assertTrue(prerequisites.contains("SpeechCredentialStatus.MISSING"))
        assertTrue(prerequisites.contains("SpeechCredentialStatus.TEMPORARILY_UNAVAILABLE"))
        assertTrue(prerequisites.contains("!internet.permitsExplicitRequest"))
        assertTrue(prerequisites.windowed("stopSelf()".length).count { it == "stopSelf()" } >= 3)
    }

    @Test
    fun bothStartAndToggleAreForegroundEntrypointsButStopIsNot() {
        assertTrue(
            HansDictationService.requiresForegroundStart(
                "ai.hans.standard.action.START_DICTATION",
            ),
        )
        assertTrue(
            HansDictationService.requiresForegroundStart(
                "ai.hans.standard.action.TOGGLE_DICTATION",
            ),
        )
        assertFalse(
            HansDictationService.requiresForegroundStart(
                "ai.hans.standard.action.STOP_DICTATION",
            ),
        )
        assertFalse(HansDictationService.requiresForegroundStart(null))
    }

    @Test
    fun toggleCanStillStopAnActiveRecordingAfterMicrophonePermissionIsRevoked() {
        assertTrue(DictationUiPhase.PREPARING.isActiveRecordingPhase())
        assertTrue(DictationUiPhase.LISTENING.isActiveRecordingPhase())
        assertTrue(DictationUiPhase.FINALIZING.isActiveRecordingPhase())
        assertFalse(DictationUiPhase.IDLE.isActiveRecordingPhase())
        assertFalse(DictationUiPhase.FAILED.isActiveRecordingPhase())
    }

    @Test
    fun callerDispatchRejectionIsReportedWithoutEscaping() {
        var failureCount = 0

        val accepted = dispatchDictationServiceCommand(
            dispatch = { throw SecurityException("background start rejected") },
            onRejected = { failureCount += 1 },
        )

        assertFalse(accepted)
        assertEquals(1, failureCount)
    }

    @Test
    fun promotionAndLazyCoreFailuresStopAtTheServiceBoundary() {
        var promotionFailureCount = 0
        val promotionResult = runDictationForegroundEntry(
            acknowledgeForeground = { throw IllegalStateException("promotion rejected") },
            runCommand = { throw AssertionError("command ran after rejected promotion") },
            onRuntimeFailure = { promotionFailureCount += 1 },
        )
        var coreFailureCount = 0
        val coreResult = runDictationForegroundEntry(
            acknowledgeForeground = {},
            runCommand = { throw IllegalStateException("core init rejected") },
            onRuntimeFailure = { coreFailureCount += 1 },
        )

        assertEquals(android.app.Service.START_NOT_STICKY, promotionResult)
        assertEquals(android.app.Service.START_NOT_STICKY, coreResult)
        assertEquals(1, promotionFailureCount)
        assertEquals(1, coreFailureCount)
    }

    @Test
    fun processSurvivalBoundariesNeverSwallowErrors() {
        try {
            dispatchDictationServiceCommand(
                dispatch = { throw AssertionError("fatal") },
                onRejected = {},
            )
            fail("Error must escape caller boundary")
        } catch (_: AssertionError) {
            // Expected.
        }
        try {
            runDictationForegroundEntry(
                acknowledgeForeground = { throw AssertionError("fatal") },
                runCommand = { android.app.Service.START_NOT_STICKY },
                onRuntimeFailure = {},
            )
            fail("Error must escape service boundary")
        } catch (_: AssertionError) {
            // Expected.
        }
    }

    private fun source(relative: String): String = listOf(
        File("src/main/java/ai/hans/standard/$relative"),
        File("android/app/src/main/java/ai/hans/standard/$relative"),
    ).first(File::isFile).readText()
}
