package ai.hans.standard.voice.tts.android

import ai.hans.standard.voice.tts.TtsAudioFocusChange
import ai.hans.standard.voice.tts.TtsAudioFocusCoordinator
import ai.hans.standard.voice.tts.TtsAudioFocusRequestResult
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Process-owned audio focus for assistant speech; it is independent of any Activity. */
class AndroidTtsAudioFocusCoordinator(context: Context) : TtsAudioFocusCoordinator {
    private val audioManager = context.applicationContext
        .getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var activeRequest: AudioFocusRequest? = null

    override fun request(
        onChange: (TtsAudioFocusChange) -> Unit,
    ): TtsAudioFocusRequestResult = synchronized(lock) {
        abandonLocked()
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            val mapped = when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> TtsAudioFocusChange.GAINED
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                    TtsAudioFocusChange.LOST_TRANSIENT
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                    TtsAudioFocusChange.LOST_TRANSIENT_CAN_DUCK
                AudioManager.AUDIOFOCUS_LOSS -> TtsAudioFocusChange.LOST_PERMANENTLY
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
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(listener, handler)
            .build()
        return@synchronized if (
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        ) {
            activeRequest = request
            TtsAudioFocusRequestResult.GRANTED
        } else {
            TtsAudioFocusRequestResult.DENIED
        }
    }

    override fun abandon() = synchronized(lock) {
        abandonLocked()
    }

    private fun abandonLocked() {
        activeRequest?.let { request ->
            runCatching { audioManager.abandonAudioFocusRequest(request) }
        }
        activeRequest = null
    }
}
