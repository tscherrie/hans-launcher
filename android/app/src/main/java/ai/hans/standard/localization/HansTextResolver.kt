package ai.hans.standard.localization

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Presentation-only boundary for pure projectors and code-to-copy mappings.
 *
 * Callers pass resource identifiers and explicit display arguments. This is not a translator:
 * user text, assistant messages, file names and protocol values must never be looked up here.
 * No implementation may change the process locale or persist a selected language.
 */
interface HansTextResolver {
    /** Effective formatting locale for this presentation, not a protocol serialization locale. */
    val locale: Locale

    fun text(@StringRes resourceId: Int, vararg formatArgs: Any): String

    /** [quantity] selects the grammatical form; include it in [formatArgs] when it is displayed. */
    fun quantity(@PluralsRes resourceId: Int, quantity: Int, vararg formatArgs: Any): String
}
