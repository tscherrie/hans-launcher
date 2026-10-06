package ai.hans.standard.localization

import android.content.res.Configuration
import androidx.activity.ComponentActivity

/** Shared real Activity callback path for locale-only presentation changes. */
abstract class HansLocaleAwareActivity : ComponentActivity() {
    final override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLocalizedPresentation(newConfig)
    }

    /** Must preserve runtime owners and unsent state; do not dispatch user work here. */
    protected abstract fun refreshLocalizedPresentation(newConfig: Configuration)
}
