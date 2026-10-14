package ai.hans.standard.voice.tts.android

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import ai.hans.standard.voice.tts.*
import ai.hans.standard.voice.feedback.HansSpeechFailureRuntime

/** Process-scoped engine connection. No microphone, network client or OpenAI credential. */
class AndroidSystemSpeech private constructor(private val context: Context) {
    private val preferences = context.getSharedPreferences("hans_system_speech", Context.MODE_PRIVATE)
    private val audio = context.getSystemService(AudioManager::class.java)
    val enabled: Boolean get() = preferences.getBoolean("enabled", true)
    @Volatile private var enginePackage: String? = null
    val runtime = SystemSpeechRuntime(
        engineFactory = { AndroidEngine(context) { enginePackage = it } },
        optionsSource = { SystemSpeechOptions(enabled,
            preferences.getString("voice", null), preferences.getFloat("speed", 1f).coerceIn(0.5f, 2f)) },
        saveOptions = { options -> preferences.edit().putBoolean("enabled", options.enabled)
            .putString("voice", options.voiceId).putFloat("speed", options.speed).apply() },
        language = { context.resources.configuration.locales[0] ?: java.util.Locale.getDefault() },
        focus = AndroidTtsAudioFocusCoordinator(context),
        audible = { audio != null && !audio.isStreamMute(AudioManager.STREAM_MUSIC) &&
            audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0 },
        reportFailure = HansSpeechFailureRuntime::report,
    )

    /** Only show a system action if this device can actually handle it. */
    fun voiceDataIntent(): Intent? {
        val pkg = enginePackage ?: return null
        return Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).setPackage(pkg)
            .takeIf { it.resolveActivity(context.packageManager) != null }
    }

    internal class AndroidEngine(private val context: Context, private val engineName: String? = null,
        val engineReady: (String?) -> Unit) : SystemSpeechEngine {
        private var tts: TextToSpeech? = null
        private val audio = context.getSystemService(AudioManager::class.java)
        @Volatile private var utterance: String? = null
        @Volatile private var externalDevices: Set<Int> = emptySet()
        private var routeObserver: AudioDeviceCallback? = null
        override val maxInputLength: Int get() = TextToSpeech.getMaxSpeechInputLength()
        override fun initialize(events: SystemSpeechEngine.Events) {
            routeObserver = object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    if (removedDevices.any { it.id in externalDevices }) {
                        utterance?.let(events::interrupted)
                    }
                }
            }.also { audio?.registerAudioDeviceCallback(it, Handler(Looper.getMainLooper())) }
            tts = TextToSpeech(context, { status ->
                if (status == TextToSpeech.SUCCESS) engineReady(tts?.defaultEngine)
                events.initialized(status == TextToSpeech.SUCCESS)
            }, engineName)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = events.started(utteranceId)
                override fun onDone(utteranceId: String) {
                    if (utterance == utteranceId) utterance = null
                    events.done(utteranceId)
                }
                @Deprecated("Required legacy engine callback")
                override fun onError(utteranceId: String) = events.failed(utteranceId)
                override fun onError(utteranceId: String, errorCode: Int) = events.failed(utteranceId)
                override fun onStop(utteranceId: String, interrupted: Boolean) = events.stopped(utteranceId)
            })
        }
        override fun voices(): List<SystemSpeechVoice> {
            engineReady(tts?.defaultEngine)
            return tts?.voices.orEmpty().filter {
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }.map { voice -> SystemSpeechVoice(voice.name, voice.locale.toLanguageTag(),
            "${voice.locale.getDisplayName(voice.locale)} · ${voice.name}", !voice.isNetworkConnectionRequired) }
        }
        override fun configure(voiceId: String, speed: Float): Boolean {
            val engine = tts ?: return false
            val voice = engine.voices?.firstOrNull { it.name == voiceId } ?: return false
            externalDevices = privateOutputs()
            return engine.setVoice(voice) == TextToSpeech.SUCCESS &&
                engine.setSpeechRate(speed) == TextToSpeech.SUCCESS &&
                engine.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()) == TextToSpeech.SUCCESS
        }
        override fun speak(text: String, id: String): Boolean {
            // Android owns media routing. Losing a private output interrupts this answer;
            // never deliberately resume its remaining chunks through the loudspeaker.
            if (externalDevices.isNotEmpty() && privateOutputs().intersect(externalDevices).isEmpty()) return false
            utterance = id
            return tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id) == TextToSpeech.SUCCESS
        }
        private fun privateOutputs(): Set<Int> = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
                .filter { it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_HEARING_AID) }.map { it.id }.toSet()
        override fun stop(): Boolean = tts?.stop() == TextToSpeech.SUCCESS
        override fun close() {
            routeObserver?.let { audio?.unregisterAudioDeviceCallback(it) }; routeObserver = null
            tts?.shutdown(); tts = null; utterance = null
        }
    }

    companion object {
        @Volatile private var instance: AndroidSystemSpeech? = null
        fun get(context: Context): AndroidSystemSpeech = instance ?: synchronized(this) {
            instance ?: AndroidSystemSpeech(context.applicationContext).also { instance = it }
        }
    }
}
