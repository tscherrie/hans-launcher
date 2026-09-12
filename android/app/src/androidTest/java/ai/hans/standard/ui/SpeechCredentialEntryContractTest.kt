package ai.hans.standard.ui

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechCredentialEntryContractTest {
    @Test
    fun editorIsMaskedNonSaveableNonAutofillAndWipesItsDraft() {
        val editor = SpeechCredentialEntryContract.createEditor(
            ApplicationProvider.getApplicationContext(),
        )
        val synthetic = "sk-test-${"x".repeat(40)}"
        editor.setText(synthetic)

        val copied = SpeechCredentialEntryContract.copyCredential(editor.editableText)
        assertEquals(synthetic, copied.concatToString())
        assertTrue(editor.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0)
        assertTrue(editor.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertFalse(editor.isSaveEnabled)
        assertEquals(
            View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,
            editor.importantForAutofill,
        )

        SpeechCredentialEntryContract.wipe(editor)
        copied.fill('\u0000')
        assertEquals(0, editor.text.length)
        assertTrue(copied.all { it == '\u0000' })
    }
}
