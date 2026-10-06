package ai.hans.standard.localization

import ai.hans.standard.R
import android.content.Context

/**
 * Language actually selected for product copy, not the first requested formatting locale.
 * Android may choose a supported second preference or the English fallback. The canary
 * follows that same resource resolution; no global locale or persisted preference changes.
 * Use only for product-owned fallback copy, never to translate user or assistant content.
 */
fun Context.hansUiLanguageTag(): String =
    supportedHansUiLanguage(resources.getString(R.string.hans_resolved_ui_language))

internal fun supportedHansUiLanguage(resolvedTag: String): String =
    if (resolvedTag == "de") "de" else "en"
