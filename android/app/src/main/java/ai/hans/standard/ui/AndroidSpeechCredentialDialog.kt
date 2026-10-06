package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.localization.rememberHansTextResolver
import androidx.compose.ui.res.stringResource

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Result of the local, non-chat Speech API credential surface. */
internal enum class SpeechCredentialDialogOutcome {
    SAVED,
    CANCELLED,
}

/**
 * Activity-owned credential entry. It is deliberately not Compose state: the draft is never
 * saveable, never enters an Activity Bundle and is wiped before the surface goes away.
 */
internal class AndroidSpeechCredentialDialog(
    private val activity: Activity,
    private val saveCredential: (CharArray) -> Boolean,
) : AutoCloseable {
    private var visibleDialog: AlertDialog? = null

    fun show(onComplete: (SpeechCredentialDialogOutcome) -> Unit): Boolean {
        if (visibleDialog != null || activity.isFinishing || activity.isDestroyed) return false
        val uiText = AndroidHansTextResolver(activity)

        val editor = SpeechCredentialEntryContract.createEditor(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = SpeechCredentialEntryContract.dialogHorizontalPadding(activity)
            val vertical = SpeechCredentialEntryContract.dialogVerticalPadding(activity)
            setPadding(horizontal, vertical, horizontal, 0)
            addView(
                TextView(activity).apply {
                    text =
                        uiText.text(R.string.ui_paste_your_openai_api_key_here_on_this_device_ha_c81f9e) +
                            uiText.text(R.string.ui_encrypted_using_android_keystore_and_will_not_di_c73d31) +
                            uiText.text(R.string.ui_again_never_send_it_in_chat_df661a)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                editor,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = vertical },
            )
        }

        var completionDelivered = false
        val activityAlreadySecure =
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(uiText.text(R.string.ui_openai_voice_access_88d085))
            .setView(content)
            .setPositiveButton(uiText.text(R.string.ui_save_securely_40c6b8), null)
            .setNegativeButton(uiText.text(R.string.ui_cancel_f7ff11), null)
            .create()
        visibleDialog = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            editor.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            val submit = View.OnClickListener {
                val credential = SpeechCredentialEntryContract.copyCredential(editor.text)
                val saved = try {
                    credential.isNotEmpty() && runCatching {
                        saveCredential(credential)
                    }.getOrDefault(false)
                } finally {
                    credential.fill('\u0000')
                }
                if (saved) {
                    completionDelivered = true
                    SpeechCredentialEntryContract.wipe(editor)
                    onComplete(SpeechCredentialDialogOutcome.SAVED)
                    dialog.dismiss()
                } else {
                    editor.error =
                        uiText.text(R.string.ui_the_key_could_not_be_saved_check_it_and_try_agai_587c3b)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(submit)
            editor.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit.onClick(editor)
                    true
                } else {
                    false
                }
            }
        }
        dialog.setOnDismissListener {
            SpeechCredentialEntryContract.wipe(editor)
            visibleDialog = null
            if (!activityAlreadySecure) {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            if (!completionDelivered) {
                completionDelivered = true
                onComplete(SpeechCredentialDialogOutcome.CANCELLED)
            }
        }
        dialog.show()
        return true
    }

    override fun close() {
        visibleDialog?.dismiss()
        visibleDialog = null
    }
}

/** Testable security contract for the transient Android editor. */
internal object SpeechCredentialEntryContract {
    private const val MAX_CREDENTIAL_CHARACTERS = 1_024

    fun createEditor(context: Context): EditText = EditText(context).apply {
        val uiText = AndroidHansTextResolver(context)
        hint = uiText.text(R.string.ui_paste_openai_api_key_387cef)
        contentDescription = uiText.text(R.string.ui_openai_api_key_f2c981)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        transformationMethod = PasswordTransformationMethod.getInstance()
        isSingleLine = true
        filters = arrayOf(InputFilter.LengthFilter(MAX_CREDENTIAL_CHARACTERS))
        imeOptions = EditorInfo.IME_ACTION_DONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        setAutofillHints(null)
    }

    fun copyCredential(editable: Editable): CharArray =
        CharArray(editable.length) { index -> editable[index] }

    fun wipe(editor: EditText) {
        val editable = editor.editableText
        for (index in 0 until editable.length) editable.replace(index, index + 1, "\u0000")
        editable.clear()
        editor.setText("")
        editor.error = null
    }

    fun dialogHorizontalPadding(context: Context): Int =
        (24 * context.resources.displayMetrics.density).toInt()

    fun dialogVerticalPadding(context: Context): Int =
        (12 * context.resources.displayMetrics.density).toInt()
}
