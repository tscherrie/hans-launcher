package ai.hans.standard.ui

import android.view.KeyEvent
import android.view.View
import ai.hans.standard.codex.CodexModel
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.phone.keys.HansModelPreset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HansModelShortcutKeysTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun focusedEditorOffersConfiguredKeyToParentBeforeImeAndPassesOtherKeys() {
        lateinit var view: View
        val observed = mutableListOf<Int>()
        compose.setContent {
            view = LocalView.current
            val focus = remember { FocusRequester() }
            Box(Modifier.hansModelShortcutKeys { event ->
                if (event.keyCode == KeyEvent.KEYCODE_SYM) {
                    observed += event.action
                    true
                } else false
            }) {
                BasicTextField(
                    value = "unchanged", onValueChange = {},
                    modifier = Modifier.focusRequester(focus).testTag("editor"),
                )
            }
        }
        compose.onNodeWithTag("editor").requestFocus()
        compose.waitForIdle()
        compose.runOnUiThread {
            assertTrue(view.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM)))
            assertTrue(view.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SYM)))
            assertFalse(view.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)))
            assertFalse(view.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A)))
        }
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), observed)
    }

    @Test
    fun focusedPhysicalShortcutUsesAdvertisedCurrentLunaAndExactLegacyFallback() {
        lateinit var view: View
        var catalog = listOf(model("gpt-6-luna", ReasoningEffort.MAX), model("gpt-5.6-luna", ReasoningEffort.MAX))
        val requests = mutableListOf<ModelShortcutPresetTarget>()
        compose.setContent {
            view = LocalView.current
            val focus = remember { FocusRequester() }
            Box(Modifier.hansModelShortcutKeys { event ->
                if (event.keyCode != KeyEvent.KEYCODE_SYM) false else {
                    if (event.action == KeyEvent.ACTION_UP) resolveModelShortcutPreset(HansModelPreset.LUNA_MAX, catalog)?.let(requests::add)
                    true
                }
            }) {
                BasicTextField(value = "unchanged", onValueChange = {}, modifier = Modifier.focusRequester(focus).testTag("editor"))
            }
        }
        compose.onNodeWithTag("editor").requestFocus()
        compose.waitForIdle()
        compose.runOnUiThread {
            view.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM))
            view.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SYM))
            catalog = listOf(model("gpt-5.6-luna", ReasoningEffort.MAX))
            view.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM))
            view.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SYM))
            catalog = listOf(model("gpt-6-luna", ReasoningEffort.MEDIUM))
            view.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SYM))
            view.dispatchKeyEventPreIme(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SYM))
        }
        assertEquals(listOf(ModelShortcutPresetTarget("gpt-6-luna", "max"), ModelShortcutPresetTarget("gpt-5.6-luna", "max")), requests)
    }

    private fun model(id: String, effort: ReasoningEffort) = CodexModel(id, id, id, "fixture", false, false,
        effort, setOf(effort), null, emptyList())
}
