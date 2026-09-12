package ai.hans.standard.automations

import android.content.Context
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAutomationNetworkRecoveryBackstopTest {
    @Test
    fun persistentOneShotRequiresValidatedInternetAndUsesTheWakeupService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val job = AndroidAutomationNetworkRecoveryBackstop.platformJob(context)

        assertEquals(AndroidAutomationNetworkRecoveryBackstop.JOB_ID, job.id)
        assertEquals(HansAutomationWakeupJobService::class.java.name, job.service.className)
        assertTrue(job.isPersisted)
        val required = checkNotNull(job.requiredNetwork)
        assertTrue(required.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertTrue(required.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        assertTrue(!required.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))
        assertTrue(!required.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED))
    }
}
