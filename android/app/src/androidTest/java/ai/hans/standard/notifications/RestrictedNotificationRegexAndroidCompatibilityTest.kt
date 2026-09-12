package ai.hans.standard.notifications

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Prevents host-JVM-only regex flags from crashing notification triage on Android. */
@RunWith(AndroidJUnit4::class)
class RestrictedNotificationRegexAndroidCompatibilityTest {
    @Test
    fun unicodeAuthenticationAndBenignNotificationPatternsInitializeOnAndroid() {
        val authentication = sanitizeRestrictedNotificationText(
            title = "Sicherheitswarnung",
            text = "Neuer Login in Łódź. Dein Bestätigungscode ist 123456.",
            subtext = "Kontoüberprüfung",
        )

        assertNotNull(authentication)
        assertTrue(checkNotNull(authentication).redactionApplied)
        assertFalse(authentication.text.contains("123456"))

        val benign = sanitizeRestrictedNotificationText(
            title = "Termin",
            text = "Das Teamtreffen in Łódź wurde auf morgen verschoben.",
            subtext = "Heute",
        )
        assertNotNull(benign)
        assertFalse(checkNotNull(benign).redactionApplied)
    }
}
