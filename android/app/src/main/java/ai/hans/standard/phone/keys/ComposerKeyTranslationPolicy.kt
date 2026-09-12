package ai.hans.standard.phone.keys

enum class ComposerKeyPassReason {
    NOT_KEY_DOWN,
    NO_PRINTABLE_ANDROID_UNICODE,
    ANDROID_COMBINING_ACCENT,
}

sealed interface ComposerKeyTranslation {
    /** Exact printable code point Android derived from key and meta state. */
    data class InsertAndroidUnicode(
        val rawUnicodeChar: Int,
        val text: String,
        val metaState: Int,
    ) : ComposerKeyTranslation

    data class PassToAndroid(val reason: ComposerKeyPassReason) : ComposerKeyTranslation
}

/**
 * Hans-composer-only policy. In particular, Alt is not remapped from keyCode:
 * Android's unicodeChar is preserved verbatim. This cannot and does not alter
 * keyboard behavior in any other app.
 */
object ComposerKeyTranslationPolicy {
    const val CAN_REMAP_OTHER_APPS_GLOBALLY: Boolean = false

    fun translate(event: ObservableAndroidKeyEvent): ComposerKeyTranslation {
        if (event.phase != ObservableKeyPhase.DOWN) {
            return ComposerKeyTranslation.PassToAndroid(ComposerKeyPassReason.NOT_KEY_DOWN)
        }
        val rawUnicode = event.unicodeChar
        if (rawUnicode and COMBINING_ACCENT_FLAG != 0) {
            return ComposerKeyTranslation.PassToAndroid(
                ComposerKeyPassReason.ANDROID_COMBINING_ACCENT,
            )
        }
        if (
            rawUnicode <= 0 ||
            !Character.isValidCodePoint(rawUnicode) ||
            Character.isISOControl(rawUnicode)
        ) {
            return ComposerKeyTranslation.PassToAndroid(
                ComposerKeyPassReason.NO_PRINTABLE_ANDROID_UNICODE,
            )
        }
        return ComposerKeyTranslation.InsertAndroidUnicode(
            rawUnicodeChar = rawUnicode,
            text = String(Character.toChars(rawUnicode)),
            metaState = event.metaState,
        )
    }

    // android.view.KeyCharacterMap.COMBINING_ACCENT is a public compile-time
    // value. Keeping it local leaves this reducer JVM-testable.
    private const val COMBINING_ACCENT_FLAG: Int = -0x80000000
}
