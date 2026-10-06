package ai.hans.standard.settings

import android.content.Context
import android.content.SharedPreferences
import ai.hans.standard.backup.HansBackupMaintenance
import ai.hans.standard.backup.HansBackupProcessState
import ai.hans.standard.codex.CodexServiceTier
import ai.hans.standard.phone.keys.ActionKeyTrigger
import ai.hans.standard.voice.realtime.LiveVoiceApiVoiceResolver
import ai.hans.standard.voice.realtime.CodexLiveVoiceVoiceResolver

enum class ActiveTurnInputMode(val wireValue: String) {
    STEER("steer"),
}

enum class ReadAloudMode(val wireValue: String) {
    FINAL_ONLY("finalOnly"),
    ALL_VISIBLE_ASSISTANT_MESSAGES("allVisibleAssistantMessages");

    companion object {
        fun fromWire(value: String): ReadAloudMode? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class HansSettings(
    val model: String = DEFAULT_MODEL,
    val reasoningEffort: String = DEFAULT_REASONING_EFFORT,
    /** App Server-confirmed tier: explicit `default` for standard or `priority` for Fast. */
    val serviceTier: String = DEFAULT_SERVICE_TIER,
    val activeTurnInputMode: ActiveTurnInputMode = ActiveTurnInputMode.STEER,
    /** Speech/TTS-only preference, independent from the voice of a Live conversation. */
    val voice: String = DEFAULT_VOICE,
    val speechRate: Float = DEFAULT_SPEECH_RATE,
    val readAloudMode: ReadAloudMode = ReadAloudMode.ALL_VISIBLE_ASSISTANT_MESSAGES,
    /** Gesture used by the user-selected physical dictation key. */
    val dictationKeyTrigger: ActionKeyTrigger = ActionKeyTrigger.PRESS,
    /** Foreground-only fallback: hold the camera affordance to dictate. */
    val cameraHoldToTalkEnabled: Boolean = false,
    /** Captured once when the next Live conversation starts; never changes an active call. */
    val liveVoice: String = DEFAULT_LIVE_VOICE,
    /** Codex/ChatGPT has a separate voice catalogue; preserve the old API preference. */
    val codexLiveVoice: String = DEFAULT_CODEX_LIVE_VOICE,
) {
    init {
        require(model in SUPPORTED_MODELS) { "Unsupported Hans model" }
        require(reasoningEffort in SUPPORTED_REASONING_EFFORTS) {
            "Unsupported reasoning effort"
        }
        require(serviceTier in SUPPORTED_SERVICE_TIERS) {
            "Unsupported service tier"
        }
        require(voice in SUPPORTED_VOICES) { "Unsupported voice id" }
        require(liveVoice in SUPPORTED_LIVE_VOICES) { "Unsupported Live voice id" }
        require(codexLiveVoice in SUPPORTED_CODEX_LIVE_VOICES) { "Unsupported Codex Live voice id" }
        require(speechRate in MIN_SPEECH_RATE..MAX_SPEECH_RATE) {
            "Speech rate is outside the supported range"
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gpt-6-astra"
        const val DEFAULT_REASONING_EFFORT = "medium"
        const val DEFAULT_SERVICE_TIER = CodexServiceTier.STANDARD
        const val FAST_SERVICE_TIER = CodexServiceTier.FAST
        const val DEFAULT_VOICE = "fable"
        const val DEFAULT_LIVE_VOICE = LiveVoiceApiVoiceResolver.DEFAULT_VOICE
        const val DEFAULT_CODEX_LIVE_VOICE = CodexLiveVoiceVoiceResolver.DEFAULT_VOICE
        const val DEFAULT_SPEECH_RATE = 1.25f
        const val MIN_SPEECH_RATE = 0.5f
        const val MAX_SPEECH_RATE = 2.0f

        /** Current product choices first; runtime model/list still decides actual availability. */
        val CURRENT_MODEL_ORDER: List<String> = listOf(
            "gpt-6-luna",
            "gpt-5.6-terra",
            "gpt-6.1-sol",
            "gpt-6-astra",
        )
        /** Keep valid saved selections and backup compatibility; never silently migrate them. */
        val LEGACY_MODEL_ORDER: List<String> = listOf("gpt-5.6-luna", "gpt-5.6-sol", "gpt-6-sol")
        val MODEL_ORDER: List<String> = CURRENT_MODEL_ORDER + LEGACY_MODEL_ORDER
        val SUPPORTED_MODELS: Set<String> = MODEL_ORDER.toSet()

        val REASONING_EFFORT_ORDER: List<String> = listOf(
            "low",
            "medium",
            "high",
            "xhigh",
            "max",
            "ultra",
        )
        val SUPPORTED_REASONING_EFFORTS: Set<String> = REASONING_EFFORT_ORDER.toSet()
        val SUPPORTED_SERVICE_TIERS: Set<String> = setOf(
            DEFAULT_SERVICE_TIER,
            FAST_SERVICE_TIER,
        )

        /** Current public OpenAI Speech API voices accepted by Hans. */
        val SUPPORTED_VOICES: Set<String> = linkedSetOf(
            "alloy",
            "ash",
            "ballad",
            "coral",
            "echo",
            "fable",
            "nova",
            "onyx",
            "sage",
            "shimmer",
            "verse",
            "marin",
            "cedar",
        )

        /** Exact Live wire catalogue, deliberately separate from the Speech/TTS list above. */
        val SUPPORTED_LIVE_VOICES: Set<String>
            get() = LiveVoiceApiVoiceResolver.supportedVoices
        val SUPPORTED_CODEX_LIVE_VOICES: Set<String>
            get() = CodexLiveVoiceVoiceResolver.supportedVoices
    }
}

interface HansSettingsStore {
    fun read(): HansSettings

    /** Persist only after App Server has accepted the requested turn settings. */
    fun saveConfirmedDispatch(
        model: String,
        reasoningEffort: String,
        serviceTier: String,
    ): HansSettings

    fun saveVoice(
        voice: String,
        speechRate: Float,
        readAloudMode: ReadAloudMode,
    ): HansSettings

    /** Persist a preference for future Live calls without touching Speech/TTS or active audio. */
    fun saveLiveVoice(voice: String): HansSettings

    fun saveCodexLiveVoice(voice: String): HansSettings

    fun saveInputControls(
        dictationKeyTrigger: ActionKeyTrigger,
        cameraHoldToTalkEnabled: Boolean,
    ): HansSettings

    /**
     * Backup restore intentionally cannot claim a model/effort change. Those values stay at the
     * last App Server-confirmed selection while all other portable preferences commit together.
     */
    fun saveRestoredNonDispatchPreferences(restored: HansSettings): HansSettings {
        saveVoice(restored.voice, restored.speechRate, restored.readAloudMode)
        saveLiveVoice(restored.liveVoice)
        saveCodexLiveVoice(restored.codexLiveVoice)
        return saveInputControls(
            restored.dictationKeyTrigger,
            restored.cameraHoldToTalkEnabled,
        )
    }
}

class SharedPreferencesHansSettingsStore(
    context: Context,
    private val maintenance: HansBackupMaintenance = HansBackupProcessState.maintenance,
) : HansSettingsStore {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(): HansSettings = maintenance.withStateAccess { decode(preferences) }

    override fun saveConfirmedDispatch(
        model: String,
        reasoningEffort: String,
        serviceTier: String,
    ): HansSettings = maintenance.withStateAccess {
        val updated = read().copy(
            model = model,
            reasoningEffort = reasoningEffort,
            serviceTier = serviceTier,
        )
        check(
            preferences.edit()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .putString(KEY_MODEL, updated.model)
                .putString(KEY_REASONING_EFFORT, updated.reasoningEffort)
                .putString(KEY_SERVICE_TIER, updated.serviceTier)
                .commit(),
        ) { "Could not persist confirmed Codex settings" }
        updated
    }

    override fun saveVoice(
        voice: String,
        speechRate: Float,
        readAloudMode: ReadAloudMode,
    ): HansSettings = maintenance.withStateAccess {
        val updated = read().copy(
            voice = voice,
            speechRate = speechRate,
            readAloudMode = readAloudMode,
        )
        check(
            preferences.edit()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .putString(KEY_VOICE, updated.voice)
                .putFloat(KEY_SPEECH_RATE, updated.speechRate)
                .putString(KEY_READ_ALOUD_MODE, updated.readAloudMode.wireValue)
                .commit(),
        ) { "Could not persist voice settings" }
        updated
    }

    override fun saveInputControls(
        dictationKeyTrigger: ActionKeyTrigger,
        cameraHoldToTalkEnabled: Boolean,
    ): HansSettings = maintenance.withStateAccess {
        val updated = read().copy(
            dictationKeyTrigger = dictationKeyTrigger,
            cameraHoldToTalkEnabled = cameraHoldToTalkEnabled,
        )
        check(
            preferences.edit()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .putString(KEY_DICTATION_KEY_TRIGGER, updated.dictationKeyTrigger.name)
                .putBoolean(KEY_CAMERA_HOLD_TO_TALK, updated.cameraHoldToTalkEnabled)
                .commit(),
        ) { "Could not persist input controls" }
        updated
    }

    override fun saveLiveVoice(voice: String): HansSettings = maintenance.withStateAccess {
        val updated = read().copy(liveVoice = voice)
        check(
            preferences.edit()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .putString(KEY_LIVE_VOICE, updated.liveVoice)
                .commit(),
        ) { "Could not persist Live voice settings" }
        updated
    }

    override fun saveCodexLiveVoice(voice: String): HansSettings = maintenance.withStateAccess {
        val updated = read().copy(codexLiveVoice = voice)
        check(
            preferences.edit()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .putString(KEY_CODEX_LIVE_VOICE, updated.codexLiveVoice)
                .commit(),
        ) { "Could not persist Codex Live voice settings" }
        updated
    }

    override fun saveRestoredNonDispatchPreferences(restored: HansSettings): HansSettings =
        maintenance.withStateAccess {
        val current = read()
        val updated = restored.copy(
            model = current.model,
            reasoningEffort = current.reasoningEffort,
            serviceTier = current.serviceTier,
        )
        check(
            preferences.edit()
                .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
                .putString(KEY_MODEL, current.model)
                .putString(KEY_REASONING_EFFORT, current.reasoningEffort)
                .putString(KEY_SERVICE_TIER, current.serviceTier)
                .putString(KEY_VOICE, updated.voice)
                .putString(KEY_LIVE_VOICE, updated.liveVoice)
                .putString(KEY_CODEX_LIVE_VOICE, updated.codexLiveVoice)
                .putFloat(KEY_SPEECH_RATE, updated.speechRate)
                .putString(KEY_READ_ALOUD_MODE, updated.readAloudMode.wireValue)
                .putString(KEY_DICTATION_KEY_TRIGGER, updated.dictationKeyTrigger.name)
                .putBoolean(KEY_CAMERA_HOLD_TO_TALK, updated.cameraHoldToTalkEnabled)
                .commit(),
        ) { "Could not persist restored Hans preferences" }
        updated
    }

    private fun decode(preferences: SharedPreferences): HansSettings {
        if (preferences.getInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION) != SCHEMA_VERSION) {
            return HansSettings()
        }
        return runCatching {
            HansSettings(
                model = preferences.getString(KEY_MODEL, null) ?: HansSettings.DEFAULT_MODEL,
                reasoningEffort = preferences.getString(KEY_REASONING_EFFORT, null)
                    ?: HansSettings.DEFAULT_REASONING_EFFORT,
                serviceTier = preferences.getString(KEY_SERVICE_TIER, null)
                    ?: HansSettings.DEFAULT_SERVICE_TIER,
                voice = preferences.getString(KEY_VOICE, null) ?: HansSettings.DEFAULT_VOICE,
                // A newly added, absent, invalid or wrongly typed Live value must not reset
                // unrelated existing preferences, especially the user's TTS voice.
                liveVoice = runCatching { preferences.getString(KEY_LIVE_VOICE, null) }
                    .getOrNull()
                    ?.takeIf { it in HansSettings.SUPPORTED_LIVE_VOICES }
                    ?: HansSettings.DEFAULT_LIVE_VOICE,
                codexLiveVoice = runCatching { preferences.getString(KEY_CODEX_LIVE_VOICE, null) }
                    .getOrNull()
                    ?.takeIf { it in HansSettings.SUPPORTED_CODEX_LIVE_VOICES }
                    ?: HansSettings.DEFAULT_CODEX_LIVE_VOICE,
                speechRate = preferences.getFloat(
                    KEY_SPEECH_RATE,
                    HansSettings.DEFAULT_SPEECH_RATE,
                ),
                readAloudMode = preferences.getString(KEY_READ_ALOUD_MODE, null)
                    ?.let(ReadAloudMode::fromWire)
                    ?: ReadAloudMode.ALL_VISIBLE_ASSISTANT_MESSAGES,
                dictationKeyTrigger = preferences.getString(KEY_DICTATION_KEY_TRIGGER, null)
                    ?.let { stored -> ActionKeyTrigger.entries.firstOrNull { it.name == stored } }
                    ?: ActionKeyTrigger.PRESS,
                cameraHoldToTalkEnabled = preferences.getBoolean(
                    KEY_CAMERA_HOLD_TO_TALK,
                    false,
                ),
            )
        }.getOrElse { HansSettings() }
    }

    private companion object {
        const val PREFERENCES_NAME = "hans_settings_v1"
        const val SCHEMA_VERSION = 1
        const val KEY_SCHEMA_VERSION = "schema_version"
        const val KEY_MODEL = "model"
        const val KEY_REASONING_EFFORT = "reasoning_effort"
        const val KEY_SERVICE_TIER = "service_tier"
        const val KEY_VOICE = "voice"
        const val KEY_LIVE_VOICE = "live_voice"
        const val KEY_CODEX_LIVE_VOICE = "codex_live_voice"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_READ_ALOUD_MODE = "read_aloud_mode"
        const val KEY_DICTATION_KEY_TRIGGER = "dictation_key_trigger"
        const val KEY_CAMERA_HOLD_TO_TALK = "camera_hold_to_talk"
    }
}
