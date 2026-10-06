package ai.hans.standard.voice.realtime

import java.util.Locale

/** Trusted local UI-language choice; never inferred from user or tool text. */
enum class LiveVoiceLanguage(private val spokenName: String, private val welcome: String) {
    ENGLISH("English", "Hi, hello?"),
    GERMAN("German", "Ja, hallo?");

    val conversationInstructions: String
        get() = "The effective interface language for this session is $spokenName. " +
            "Use it for the initial welcome only and as a fallback before the user speaks. " +
            "Afterwards follow the user's actual language, even when different or mixed. " +
            "The language of these instructions does not override the user's language. " +
            "Do not translate dictated user text to match the interface."

    val greetingInstructions: String
        get() = "If you have not welcomed this call yet, start proactively in $spokenName " +
            "by saying: $welcome Then pause and listen. Welcome exactly once; if already " +
            "greeted, do not repeat it. Do not wait for the user to speak first. Afterwards " +
            "follow the user's actual language."

    companion object {
        fun forLanguageTag(languageTag: String?): LiveVoiceLanguage =
            if (languageTag != null && Locale.forLanguageTag(languageTag).language == "de") {
                GERMAN
            } else {
                ENGLISH
            }
    }
}
