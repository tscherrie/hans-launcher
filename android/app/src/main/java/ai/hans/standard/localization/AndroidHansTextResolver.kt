package ai.hans.standard.localization

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Resolves through the caller's effective Resources configuration on every call.
 *
 * Keep the supplied component/configuration context: replacing it with applicationContext would
 * lose an Activity's effective locale override. Owners recreate this presentation resolver with
 * their normal lifecycle; it is not a singleton and it never retains translated strings.
 */
class AndroidHansTextResolver(private val context: Context) : HansTextResolver {
    override val locale: Locale
        get() = context.resources.configuration.locales.let { locales ->
            if (locales.isEmpty) Locale.ENGLISH else locales[0]
        }

    override fun text(@StringRes resourceId: Int, vararg formatArgs: Any): String =
        if (formatArgs.isEmpty()) context.resources.getString(resourceId)
        else context.resources.getString(resourceId, *formatArgs)

    override fun quantity(
        @PluralsRes resourceId: Int,
        quantity: Int,
        vararg formatArgs: Any,
    ): String = if (formatArgs.isEmpty()) {
        context.resources.getQuantityString(resourceId, quantity)
    } else {
        context.resources.getQuantityString(resourceId, quantity, *formatArgs)
    }
}
