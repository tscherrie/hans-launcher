package ai.hans.standard.automations

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAutomationUserPresentReceiverTest {
    @Test
    fun receiverAcceptsOnlyTheProtectedUserPresentAction() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val actions = mutableListOf<String>()
        val receiver = HansAutomationUserPresentReceiver { actions += "dispatch" }

        receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        receiver.onReceive(context, null)
        receiver.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))

        assertEquals(listOf("dispatch"), actions)
    }

    @Test
    fun persistedJobExtrasDecodeBackToUserPresentTrigger() {
        val delivery = Intent(AndroidAutomationDispatch.ACTION_DISPATCH_RETRY)
            .putExtra("trigger", "user_present")
            .putExtra("retry_attempt", 1)

        assertEquals(
            AutomationRuntimeTrigger.UserPresent,
            AndroidAutomationDispatch.triggerFrom(delivery),
        )
        assertEquals(1, AndroidAutomationDispatch.retryAttemptFrom(delivery))
    }
}
