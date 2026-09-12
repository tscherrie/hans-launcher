package ai.hans.standard.phone.publicapi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPublicPhonePlatformProbeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun probesReflectLiveGrantsAndNeverClaimRootOrAutomaticAccess() {
        val probes = AndroidPublicPhonePlatform(
            context,
            ActiveNotificationReplyRegistry.forTest(ByteArray(32) { 1 }),
        ).probeCapabilities().associateBy(PublicPhoneCapabilityProbe::capability)

        val contacts = probes.getValue("contacts.read")
        val expectedContacts = if (
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            PublicPhoneCapabilityState.AVAILABLE
        } else {
            PublicPhoneCapabilityState.PERMISSION_REQUIRED
        }
        assertEquals(expectedContacts, contacts.state)
        assertEquals(
            PublicPhoneCapabilityState.SPECIAL_ACCESS_REQUIRED,
            probes.getValue("notifications.reply").state,
        )
        assertFalse(probes.keys.any { it.contains("root", ignoreCase = true) })
        assertTrue(probes.size >= 10)
    }

    @Test
    fun providerReadFailsClosedWhenContactsGrantIsAbsent() {
        if (
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val platform = AndroidPublicPhonePlatform(context)

        val result = platform.searchContacts("Ada", 5)

        assertTrue(result is PublicPhonePlatformResult.Failure)
        assertEquals(
            "permission_required_read_contacts",
            (result as PublicPhonePlatformResult.Failure).code,
        )
    }
}
