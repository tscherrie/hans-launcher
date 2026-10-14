package ai.hans.standard.phone.accessibility.android

import android.accessibilityservice.AccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityScreenshotFailureTest {
    @Test
    fun secureAndInvalidWindowRejectionsAreDistinctPermanentPlatformErrors() {
        assertEquals("visual_capture_secure_window", AccessibilityScreenshotFailure.code(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW,
        ))
        assertEquals("visual_capture_invalid_window", AccessibilityScreenshotFailure.code(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW,
        ))
    }

    @Test
    fun transientAndAuthorizationFailuresKeepDistinctNonprivateCodes() {
        assertEquals("visual_capture_access_revoked", AccessibilityScreenshotFailure.code(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS,
        ))
        assertEquals("visual_capture_rate_limited", AccessibilityScreenshotFailure.code(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,
        ))
        assertEquals("visual_capture_invalid_display", AccessibilityScreenshotFailure.code(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY,
        ))
        assertEquals("visual_capture_internal_error", AccessibilityScreenshotFailure.code(
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
        ))
        assertEquals("visual_capture_platform_rejected", AccessibilityScreenshotFailure.code(-987))
    }
}
