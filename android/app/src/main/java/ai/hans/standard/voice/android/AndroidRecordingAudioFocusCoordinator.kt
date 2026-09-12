package ai.hans.standard.voice.android

import ai.hans.standard.voice.AudioFocusRequestResult
import ai.hans.standard.voice.MonotonicClock
import ai.hans.standard.voice.RecordingAudioFocusChange
import ai.hans.standard.voice.RecordingAudioFocusCoordinator
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

object AndroidMonotonicClock : MonotonicClock {
    override fun nowMillis(): Long = SystemClock.elapsedRealtime()
}

class AndroidRecordingAudioFocusCoordinator(context: Context) : RecordingAudioFocusCoordinator {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var activeRequest: AudioFocusRequest? = null

    override fun request(
        onChange: (RecordingAudioFocusChange) -> Unit,
    ): AudioFocusRequestResult = synchronized(lock) {
        abandonLocked()
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            val mapped = when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> RecordingAudioFocusChange.GAINED
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                    RecordingAudioFocusChange.LOST_TRANSIENT
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    RecordingAudioFocusChange.LOST_TRANSIENT_CAN_DUCK
                AudioManager.AUDIOFOCUS_LOSS ->
                    RecordingAudioFocusChange.LOST_PERMANENTLY
                else -> return@OnAudioFocusChangeListener
            }
            onChange(mapped)
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(listener, handler)
            .build()
        return@synchronized if (
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        ) {
            activeRequest = request
            AudioFocusRequestResult.GRANTED
        } else {
            AudioFocusRequestResult.DENIED
        }
    }

    override fun abandon() = synchronized(lock) {
        abandonLocked()
    }

    private fun abandonLocked() {
        activeRequest?.let { request -> runCatching { audioManager.abandonAudioFocusRequest(request) } }
        activeRequest = null
    }
}
