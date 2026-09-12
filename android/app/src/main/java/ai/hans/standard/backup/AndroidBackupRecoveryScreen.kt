package ai.hans.standard.backup

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import ai.hans.standard.R

/** A static, account-free fallback: no runtime, polling, profile reads or app inventory needed. */
internal fun backupRecoveryScreen(
    context: Context,
    onRetry: (Button) -> Unit,
    onAndroidSettings: () -> Unit,
    onHomeSettings: () -> Unit,
): View = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    val padding = (24 * context.resources.displayMetrics.density).toInt()
    setPadding(padding, padding, padding, padding)
    addView(TextView(context).apply {
        setText(R.string.backup_recovery_title)
        textSize = 24f
    })
    addView(TextView(context).apply {
        setText(R.string.backup_recovery_message)
        textSize = 18f
    })
    addView(Button(context).apply {
        setText(R.string.backup_recovery_retry)
        setOnClickListener { onRetry(this) }
    })
    addView(Button(context).apply {
        setText(R.string.backup_recovery_android_settings)
        setOnClickListener { onAndroidSettings() }
    })
    addView(Button(context).apply {
        setText(R.string.backup_recovery_home_settings)
        setOnClickListener { onHomeSettings() }
    })
}
