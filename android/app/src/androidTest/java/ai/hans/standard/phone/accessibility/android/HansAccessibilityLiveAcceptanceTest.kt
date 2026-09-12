package ai.hans.standard.phone.accessibility.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.devicecontrol.tools.AccessibilitySpecialAccessProbe
import ai.hans.standard.devicecontrol.tools.AndroidAccessibilityDynamicToolCatalog
import ai.hans.standard.devicecontrol.tools.AndroidAccessibilityDynamicToolExecutor
import ai.hans.standard.phone.capabilities.AndroidCapabilityEnvironment
import ai.hans.standard.phone.capabilities.AndroidSpecialAccess
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read-only, opt-in acceptance against a device on which the user already enabled Hans app
 * control. It never changes secure settings or Accessibility consent.
 */
@RunWith(AndroidJUnit4::class)
class HansAccessibilityLiveAcceptanceTest {
    @Test
    fun enabledBoundServicePublishesSessionAndRefreshesActiveWindow() {
        assumeTrue(
            InstrumentationRegistry.getArguments()
                .getString(ARG_RUN_LIVE_ACCESSIBILITY) == "true",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, HansAccessibilityService::class.java)
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + SERVICE_REBIND_WAIT_MILLIS
        var platformAccessEnabled = isPlatformAccessEnabled(manager, component)
        var session = HansAccessibilitySessions.current()
        while (
            SystemClock.elapsedRealtime() < deadline &&
            (
                !platformAccessEnabled ||
                    !HansAccessibilitySessions.isServiceConnected() ||
                    session == null
            )
        ) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(SERVICE_REBIND_RETRY_MILLIS)
            platformAccessEnabled = isPlatformAccessEnabled(manager, component)
            session = HansAccessibilitySessions.current()
        }
        assumeTrue("Hans Accessibility special access is not enabled by the user", platformAccessEnabled)
        assertTrue(
            "Android reports special access, but the Hans service connection is not registered",
            HansAccessibilitySessions.isServiceConnected(),
        )
        assertNotNull("The bound Hans service did not publish its command session", session)

        val boundSession = checkNotNull(session)
        val refreshed = boundSession.refreshSnapshot()
        assertNotNull("The bound Hans service could not refresh the active Android window", refreshed)
        val correlation = checkNotNull(refreshed).correlation
        assertEquals(boundSession.sessionId, correlation.sessionId)
        assertTrue(correlation.windowId.value >= 0)
        assertTrue(correlation.snapshotId.value > 0)

        val environment = AndroidCapabilityEnvironment(context)
        assertTrue(
            "The live capability probe did not recognize the exact Hans Accessibility service",
            environment.hasSpecialAccess(AndroidSpecialAccess.ACCESSIBILITY_SERVICE),
        )
        val executor = AndroidAccessibilityDynamicToolExecutor(
            backgroundExecutor = Executor { command -> command.run() },
            specialAccess = AccessibilitySpecialAccessProbe {
                environment.hasSpecialAccess(AndroidSpecialAccess.ACCESSIBILITY_SERVICE)
            },
            uiAvailability = AndroidUiInteractionAvailabilityProbe(context),
        )
        lateinit var receipt: DynamicToolExecutionResult
        executor.execute(
            DynamicToolCallParams(
                threadId = "thread-live-accessibility",
                turnId = "turn-live-accessibility",
                callId = "call-live-inspect-ui",
                namespace = AndroidAccessibilityDynamicToolCatalog.NAMESPACE,
                tool = "inspect_ui",
                argumentsJson = "{}",
            ),
        ) { receipt = it }
        assertTrue("The real inspect_ui tool receipt was not successful", receipt.success)
        assertTrue(receipt.contentText.contains("\"snapshotId\""))
        assertTrue(!receipt.contentText.contains("accessibility_permission_required"))
    }

    private fun isPlatformAccessEnabled(
        manager: AccessibilityManager,
        component: ComponentName,
    ): Boolean = manager
        .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { info ->
            val service = info.resolveInfo.serviceInfo
            ComponentName(service.packageName, service.name) == component
        }

    private companion object {
        const val ARG_RUN_LIVE_ACCESSIBILITY = "runLiveAccessibilityAcceptance"
        const val SERVICE_REBIND_WAIT_MILLIS = 5_000L
        const val SERVICE_REBIND_RETRY_MILLIS = 50L
    }
}
