package ai.hans.standard.phone.capabilities

import android.content.ComponentName
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import ai.hans.standard.phone.notifications.HansNotificationListenerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPublicCapabilityAdapterSettingsRouteTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val adapter = AndroidPublicCapabilityAdapter(context)

    @Test
    fun notificationAccessUsesHansDetailWhenSupportedAndKeepsGenericFallback() {
        val routes = adapter.settingsIntentCandidates(
            SettingsDestination.NOTIFICATION_LISTENER,
            context.packageName,
        )

        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, routes.last().action)
        assertTrue(routes.all { it.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK != 0 })
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, routes.first().action)
            assertEquals(
                ComponentName(context, HansNotificationListenerService::class.java)
                    .flattenToString(),
                routes.first().getStringExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ),
            )
            assertEquals(2, routes.size)
        } else {
            assertEquals(1, routes.size)
        }
    }

    @Test
    fun accessibilityUsesDocumentedGenericSettingsRoute() {
        val routes = adapter.settingsIntentCandidates(
            SettingsDestination.ACCESSIBILITY,
            context.packageName,
        )

        assertEquals(1, routes.size)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, routes.single().action)
    }

    @Test
    fun exactAlarmUsesDocumentedAppSpecificSettingsRoute() {
        val routes = adapter.settingsIntentCandidates(
            SettingsDestination.EXACT_ALARM,
            context.packageName,
        )

        assertEquals(1, routes.size)
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, routes.single().action)
        assertEquals("package:${context.packageName}", routes.single().dataString)
    }
}
