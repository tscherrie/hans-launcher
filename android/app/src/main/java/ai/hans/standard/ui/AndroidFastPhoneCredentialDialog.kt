package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.AndroidHansTextResolver
import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView

internal enum class FastPhoneCredentialDialogOutcome { SAVED, CANCELLED }

/** Activity-owned secret editor; its draft never enters Compose, saved state, a chat or a log. */
internal class AndroidFastPhoneCredentialDialog(
    private val activity: Activity,
    private val saveCredential: (CharArray) -> Boolean,
) : AutoCloseable {
    private var visibleDialog: AlertDialog? = null

    fun show(onComplete: (FastPhoneCredentialDialogOutcome) -> Unit): Boolean {
        if (visibleDialog != null || activity.isFinishing || activity.isDestroyed) return false
        val text = AndroidHansTextResolver(activity)
        // Reuse the tested masked, non-saveable, non-autofill, non-learning input contract.
        val editor = SpeechCredentialEntryContract.createEditor(activity)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val horizontal = SpeechCredentialEntryContract.dialogHorizontalPadding(activity)
            val vertical = SpeechCredentialEntryContract.dialogVerticalPadding(activity)
            setPadding(horizontal, vertical, horizontal, 0)
            addView(TextView(activity).apply { this.text = text.text(R.string.fast_phone_key_help) })
            addView(editor, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = vertical })
        }
        var completed = false
        val alreadySecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(text.text(R.string.fast_phone_key_title))
            .setView(content)
            .setPositiveButton(text.text(R.string.ui_save_securely_40c6b8), null)
            .setNegativeButton(text.text(R.string.ui_cancel_f7ff11), null)
            .create()
        visibleDialog = dialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            editor.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            val submit = View.OnClickListener {
                val key = SpeechCredentialEntryContract.copyCredential(editor.editableText)
                val saved = try {
                    key.isNotEmpty() && runCatching { saveCredential(key) }.getOrDefault(false)
                } finally {
                    key.fill('\u0000')
                }
                if (saved) {
                    completed = true
                    SpeechCredentialEntryContract.wipe(editor)
                    onComplete(FastPhoneCredentialDialogOutcome.SAVED)
                    dialog.dismiss()
                } else {
                    editor.error = text.text(R.string.fast_phone_key_save_failed)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(submit)
            editor.setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_DONE) { submit.onClick(editor); true } else false
            }
        }
        dialog.setOnDismissListener {
            SpeechCredentialEntryContract.wipe(editor)
            visibleDialog = null
            if (!alreadySecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            if (!completed) {
                completed = true
                onComplete(FastPhoneCredentialDialogOutcome.CANCELLED)
            }
        }
        dialog.show()
        return true
    }

    override fun close() { visibleDialog?.dismiss(); visibleDialog = null }
}
