package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityWindowId
import ai.hans.standard.phone.accessibility.BoundedSemanticUiSnapshotFactory
import ai.hans.standard.phone.accessibility.SemanticUiAction
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiDataTrust
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HansAccessibilityServiceContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val component = ComponentName(context, HansAccessibilityService::class.java)

    @Test
    @Suppress("DEPRECATION")
    fun serviceIsSystemBindOnlyAndHasNoSeparateProcessBoundary() {
        val info = context.packageManager.getServiceInfo(
            component,
            PackageManager.GET_META_DATA,
        )

        assertTrue(info.exported)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, info.permission)
        assertEquals(context.applicationInfo.processName, info.processName)
        assertNotNull(info.metaData)
        assertTrue(info.metaData.containsKey(AccessibilityService.SERVICE_META_DATA))

        val matches = context.packageManager.queryIntentServices(
            Intent(AccessibilityService.SERVICE_INTERFACE).setPackage(context.packageName),
            PackageManager.GET_META_DATA,
        )
        assertTrue(matches.any { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) == component })
    }

    @Test
    fun parsedServicePolicyIsEventDrivenWithExplicitVisualFallbackCapability() {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val info = manager.installedAccessibilityServiceList.single {
            val service = it.resolveInfo.serviceInfo
            ComponentName(service.packageName, service.name) == component
        }

        assertTrue(
            info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0,
        )
        assertTrue(
            info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0,
        )
        assertTrue(
            info.capabilities and
                AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0,
        )
        assertTrue(
            info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0,
        )
        assertTrue(
            info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0,
        )
        assertEquals(0, info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS)
        assertTrue(info.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS != 0)
        // The static policy grants the capability but does not request the
        // stream until a user-demonstrated dictation mapping actually exists.
        assertEquals(0, info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS)
        assertEquals(0, info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE)
        assertTrue(info.eventTypes and AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED != 0)
        assertTrue(info.eventTypes and AccessibilityEvent.TYPE_WINDOWS_CHANGED != 0)
        assertTrue(info.eventTypes and AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED != 0)
        assertTrue(info.eventTypes and AccessibilityEvent.TYPE_VIEW_SCROLLED != 0)
        assertTrue(info.eventTypes and AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED != 0)
        assertTrue(info.feedbackType and AccessibilityServiceInfo.FEEDBACK_GENERIC != 0)
        assertEquals(100L, info.notificationTimeout)
        assertFalse(info.isAccessibilityTool)
    }

    @Test
    fun backIsOwnedOnlyWhileTheTrustedConfirmationIsVisible() {
        var denied = 0
        val down = KeyEvent(10, 10, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        val repeat = KeyEvent(10, 20, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 1)
        val up = KeyEvent(10, 30, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
        val foreign = KeyEvent(40, 40, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0)

        assertFalse(
            AccessibilityConfirmationBackKeyFilter.onKeyEvent(down, false) { denied += 1 },
        )
        assertFalse(
            AccessibilityConfirmationBackKeyFilter.onKeyEvent(foreign, true) { denied += 1 },
        )
        assertTrue(
            AccessibilityConfirmationBackKeyFilter.onKeyEvent(down, true) { denied += 1 },
        )
        assertTrue(
            AccessibilityConfirmationBackKeyFilter.onKeyEvent(repeat, true) { denied += 1 },
        )
        assertTrue(
            AccessibilityConfirmationBackKeyFilter.onKeyEvent(up, true) { denied += 1 },
        )
        assertEquals(1, denied)
    }

    @Test
    @Suppress("DEPRECATION")
    fun packageRequestsNoPrivilegedUiInjectionOrCapturePermissions() {
        val requested = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toSet()

        assertFalse("android.permission.WRITE_SECURE_SETTINGS" in requested)
        assertFalse("android.permission.INJECT_EVENTS" in requested)
        assertFalse("android.permission.CAPTURE_SECURE_VIDEO_OUTPUT" in requested)
        assertFalse("android.permission.CAPTURE_VIDEO_OUTPUT" in requested)
        assertFalse("android.permission.READ_FRAME_BUFFER" in requested)
        assertFalse("android.permission.MEDIA_CONTENT_CONTROL" in requested)
        assertFalse("android.permission.SYSTEM_ALERT_WINDOW" in requested)
        assertFalse("android.permission.QUERY_ALL_PACKAGES" in requested)
    }

    @Test
    @Suppress("DEPRECATION")
    fun realAccessibilityNodeInfoIsCopiedToImmutableSemanticDataAndClosed() {
        val frameworkNode = AccessibilityNodeInfo.obtain().apply {
            packageName = "external.example"
            className = "android.widget.EditText"
            text = StringBuilder("untrusted text")
            contentDescription = "field description"
            setBoundsInScreen(Rect(10, 20, 310, 120))
            isVisibleToUser = true
            isEnabled = true
            isEditable = true
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS)
        }
        val wrapper = AccessibilityNodeInfoNode(frameworkNode)
        val correlation = UiSnapshotCorrelation(
            AccessibilitySessionId("instrumented-session-0001"),
            AccessibilityWindowId(0),
            AccessibilitySnapshotId(1),
        )

        val projected = AndroidSemanticTreeProjector().project(
            root = wrapper,
            correlation = correlation,
            displayId = 0,
            displayBounds = UiBounds(0, 0, 1_080, 2_400),
            capturedAtElapsedMillis = 1,
        )
        val snapshot = BoundedSemanticUiSnapshotFactory()
            .build(checkNotNull(projected.rawSnapshot))
        val node = snapshot.nodes.single()

        assertFalse(projected.readFailed)
        assertEquals("external.example", node.packageName?.value)
        assertEquals("untrusted text", node.text?.value)
        assertEquals("field description", node.contentDescription?.value)
        assertEquals(SemanticUiRole.EDIT_TEXT, node.role)
        assertEquals(UiBounds(10, 20, 310, 120), node.bounds)
        assertEquals(setOf(SemanticUiAction.SET_TEXT, SemanticUiAction.FOCUS), node.actions)
        assertEquals(UiDataTrust.UNTRUSTED_EXTERNAL, node.trust)
        assertThrows(IllegalStateException::class.java) { wrapper.text }
    }
}
