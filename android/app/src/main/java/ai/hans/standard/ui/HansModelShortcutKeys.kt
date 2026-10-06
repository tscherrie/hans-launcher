package ai.hans.standard.ui

import android.view.KeyEvent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import ai.hans.standard.codex.CodexModel
import ai.hans.standard.phone.keys.HansModelPreset

/** Foreground-only hook; the owner consumes only an empirically configured model key. */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.hansModelShortcutKeys(onKey: (KeyEvent) -> Boolean): Modifier =
    onPreInterceptKeyBeforeSoftKeyboard { event -> onKey(event.nativeKeyEvent) }

internal data class ModelShortcutPresetTarget(val model: String, val effort: String)

/** Exact named preset only. Never turn Luna Max/Astra Ultra into an advertised default effort. */
internal fun resolveModelShortcutPreset(preset: HansModelPreset, models: List<CodexModel>): ModelShortcutPresetTarget? {
    val candidates = when (preset) {
        HansModelPreset.LUNA_MAX -> listOf("gpt-6-luna", "gpt-5.6-luna")
        HansModelPreset.ASTRA_ULTRA -> listOf("gpt-6-astra")
    }
    val available = candidates.firstOrNull { candidate -> models.any { model ->
        model.wireModel == candidate && !model.hidden && model.supportedEfforts.any { it.wireValue == preset.effort }
    } } ?: return null
    return ModelShortcutPresetTarget(available, preset.effort)
}
