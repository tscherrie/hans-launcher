package ai.hans.standard.phone.platform

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/**
 * Debug-only host that runs instrumentation probes inside the Hans UID and task.
 * It deliberately owns no production behavior.
 */
class AndroidPlatformCompatibilityProbeActivity : Activity() {
    lateinit var content: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = FrameLayout(this)
        setContentView(content)
    }
}
