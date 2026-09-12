package ai.hans.standard.phone.keys

import ai.hans.standard.voice.android.DictationUiPhase
import ai.hans.standard.voice.android.HansDictationRuntime
import ai.hans.standard.voice.android.HansDictationService
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock

fun interface GlobalActionKeyCommandExecutor {
    /** True means Android accepted the requested app-side transition. */
    fun execute(command: ActionKeyCommand): Boolean
}

/**
 * Bounded one-shot gate preventing a HOLD_TO_TALK release from racing a
 * translucent permission/launch activity that has not started recording yet.
 */
class DictationStartGate(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    init {
        require(timeoutMillis > 0)
    }

    private var nextToken = 0L
    private var pending: PendingStart? = null

    @Synchronized
    fun prepare(nowMillis: Long): Long {
        require(nowMillis >= 0)
        expire(nowMillis)
        nextToken = if (nextToken == Long.MAX_VALUE) 1 else nextToken + 1
        return nextToken.also { token -> pending = PendingStart(token, nowMillis) }
    }

    @Synchronized
    fun consume(token: Long, nowMillis: Long): Boolean {
        require(token > 0)
        require(nowMillis >= 0)
        expire(nowMillis)
        if (pending?.token != token) return false
        pending = null
        return true
    }

    @Synchronized
    fun cancel(token: Long): Boolean {
        if (pending?.token != token) return false
        pending = null
        return true
    }

    @Synchronized
    fun cancelAll(): Boolean = (pending != null).also { pending = null }

    @Synchronized
    fun hasPending(nowMillis: Long): Boolean {
        require(nowMillis >= 0)
        expire(nowMillis)
        return pending != null
    }

    private fun expire(nowMillis: Long) {
        val prepared = pending ?: return
        if (nowMillis < prepared.preparedAtMillis || nowMillis - prepared.preparedAtMillis >= timeoutMillis) {
            pending = null
        }
    }

    private data class PendingStart(val token: Long, val preparedAtMillis: Long)

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}

data class PreparedDictationLaunch(
    val token: Long,
    val intent: Intent,
)

/**
 * Process-local launch coordinator. Microphone capture itself remains owned by
 * HansDictationService, so switching apps never terminates an active recording.
 */
object RootFreeDictationLaunchCoordinator {
    private val gate = DictationStartGate()

    @Synchronized
    fun prepareStart(context: Context): PreparedDictationLaunch? {
        if (isRecordingActive()) return null
        val token = gate.prepare(SystemClock.elapsedRealtime())
        return PreparedDictationLaunch(
            token = token,
            intent = RootFreeDictationEntryActivity.intent(context, token),
        )
    }

    @Synchronized
    fun cancelPreparedStart(token: Long) {
        gate.cancel(token)
    }

    @Synchronized
    fun startPrepared(context: Context, token: Long): Boolean {
        if (!gate.consume(token, SystemClock.elapsedRealtime())) return false
        return runCatching { HansDictationService.start(context.applicationContext) }.isSuccess
    }

    @Synchronized
    fun requestStop(context: Context): Boolean {
        gate.cancelAll()
        return runCatching { HansDictationService.stop(context.applicationContext) }.isSuccess
    }

    @Synchronized
    fun requestToggleFromBackground(context: Context): Boolean {
        if (isRecordingActive() || gate.hasPending(SystemClock.elapsedRealtime())) {
            return requestStop(context)
        }
        val launch = prepareStart(context) ?: return true
        return runCatching {
            context.startActivity(
                launch.intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                ),
            )
        }.fold(
            onSuccess = { true },
            onFailure = {
                cancelPreparedStart(launch.token)
                false
            },
        )
    }

    @Synchronized
    fun requestStartFromBackground(context: Context): Boolean {
        if (isRecordingActive()) return true
        val launch = prepareStart(context) ?: return true
        return runCatching {
            context.startActivity(
                launch.intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                ),
            )
        }.fold(
            onSuccess = { true },
            onFailure = {
                cancelPreparedStart(launch.token)
                false
            },
        )
    }

    @Synchronized
    fun hasPendingStart(): Boolean = gate.hasPending(SystemClock.elapsedRealtime())

    private fun isRecordingActive(): Boolean = HansDictationRuntime.snapshotUi().phase in setOf(
        DictationUiPhase.PREPARING,
        DictationUiPhase.LISTENING,
        DictationUiPhase.FINALIZING,
    )
}

class AndroidGlobalActionKeyCommandExecutor(
    private val context: Context,
) : GlobalActionKeyCommandExecutor {
    override fun execute(command: ActionKeyCommand): Boolean = when (command) {
        ActionKeyCommand.StartDictation ->
            RootFreeDictationLaunchCoordinator.requestStartFromBackground(context)
        ActionKeyCommand.StopDictation ->
            RootFreeDictationLaunchCoordinator.requestStop(context)
        ActionKeyCommand.ToggleDictation ->
            RootFreeDictationLaunchCoordinator.requestToggleFromBackground(context)
        // Global filtering deliberately does not own the launcher-only model shortcut.
        ActionKeyCommand.ToggleLunaMaxSolUltra -> false
    }
}

/**
 * Short, translucent public-API bridge that obtains visible-activity status
 * before starting a microphone foreground service on Android 12+. It is
 * exported=false and accepts only a process-local one-shot token.
 */
class RootFreeDictationEntryActivity : Activity() {
    private var token: Long = 0
    private var permissionRequested = false
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = savedInstanceState?.getLong(STATE_TOKEN)
            ?: intent.getLongExtra(EXTRA_TOKEN, 0)
        if (token <= 0) finishQuietly()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_TOKEN, token)
        super.onSaveInstanceState(outState)
    }

    override fun onPostResume() {
        super.onPostResume()
        continueWhenVisible()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        val granted = grantResults.singleOrNull() == PackageManager.PERMISSION_GRANTED &&
            permissions.singleOrNull() == Manifest.permission.RECORD_AUDIO
        if (granted) {
            continueWhenVisible()
        } else {
            RootFreeDictationLaunchCoordinator.cancelPreparedStart(token)
            finishQuietly()
        }
    }

    private fun continueWhenVisible() {
        if (completed || token <= 0) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!permissionRequested) {
                permissionRequested = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            }
            return
        }
        completed = true
        RootFreeDictationLaunchCoordinator.startPrepared(this, token)
        finishQuietly()
    }

    private fun finishQuietly() {
        finish()
    }

    companion object {
        private const val EXTRA_TOKEN = "ai.hans.standard.extra.DICTATION_START_TOKEN"
        private const val STATE_TOKEN = "dictation_start_token"
        private const val REQUEST_RECORD_AUDIO = 7_612

        fun intent(context: Context, token: Long): Intent {
            require(token > 0)
            return Intent(context, RootFreeDictationEntryActivity::class.java)
                .putExtra(EXTRA_TOKEN, token)
        }
    }
}
