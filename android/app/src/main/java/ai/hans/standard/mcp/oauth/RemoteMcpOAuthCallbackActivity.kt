package ai.hans.standard.mcp.oauth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Browser-only callback surface. The manifest narrows the URI and this activity validates every
 * component again before a background exchange. It exports no Binder/service API.
 */
internal class RemoteMcpOAuthCallbackActivity : Activity() {
    private val handling = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-remote-mcp-oauth-callback").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (!handling.compareAndSet(false, true)) {
            finish()
            return
        }
        val callback = runCatching { intent?.data?.toString()?.let { URI(it) } }.getOrNull()
        if (callback == null) {
            startActivity(
                RemoteMcpOAuthCompletionResult.Failed(
                    null,
                    null,
                    RemoteMcpOAuthConnectionFailure.CALLBACK_REJECTED,
                ).safeResultIntent(this),
            )
            finish()
            return
        }
        executor.execute {
            val result = runCatching {
                AndroidRemoteMcpOAuthFlowFactory.create(applicationContext).complete(callback)
            }.getOrElse {
                RemoteMcpOAuthCompletionResult.Failed(
                    null,
                    null,
                    RemoteMcpOAuthConnectionFailure.CALLBACK_REJECTED,
                )
            }
            runOnUiThread {
                startActivity(result.safeResultIntent(this))
                finish()
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
