package ai.hans.standard.ui

import android.view.KeyEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard

/** Foreground-only hook; the owner consumes only an empirically configured model key. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.hansModelShortcutKeys(onKey: (KeyEvent) -> Boolean): Modifier =
    onPreInterceptKeyBeforeSoftKeyboard { event -> onKey(event.nativeKeyEvent) }
