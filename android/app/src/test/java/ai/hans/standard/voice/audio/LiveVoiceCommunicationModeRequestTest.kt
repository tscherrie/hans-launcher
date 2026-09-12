package ai.hans.standard.voice.audio

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveVoiceCommunicationModeRequestTest {
    @Test
    fun telephonyInterleaveReleasesHansRequestWithoutOverridingEffectiveCallMode() {
        var hansRequestedMode = AudioManager.MODE_IN_COMMUNICATION
        var telephonyActive = false
        fun effectiveMode(): Int = if (telephonyActive) {
            AudioManager.MODE_IN_CALL
        } else {
            hansRequestedMode
        }
        val lease = LiveVoiceCommunicationModeRequest(AudioManager.MODE_NORMAL) { restored ->
            hansRequestedMode = restored
        }

        assertEquals(AudioManager.MODE_IN_COMMUNICATION, effectiveMode())
        telephonyActive = true
        assertEquals(AudioManager.MODE_IN_CALL, effectiveMode())

        lease.close()
        lease.close()
        assertEquals(AudioManager.MODE_IN_CALL, effectiveMode())

        telephonyActive = false
        assertEquals(AudioManager.MODE_NORMAL, effectiveMode())
    }
}
