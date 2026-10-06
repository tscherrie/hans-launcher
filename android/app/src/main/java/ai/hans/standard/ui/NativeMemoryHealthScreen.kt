package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.localization.rememberHansTextResolver
import androidx.compose.ui.res.stringResource

import ai.hans.standard.diagnostics.memory.NativeMemoryHealthPresentation
import ai.hans.standard.diagnostics.memory.NativeMemoryHealthResult
import ai.hans.standard.diagnostics.memory.NativeMemoryHealthWire
import ai.hans.standard.diagnostics.memory.NativeMemoryUnavailableReason
import ai.hans.standard.runtime.IRuntimeService
import ai.hans.standard.runtime.RuntimeService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

// At most one blocked Binder call and one queued request. A timeout never spawns unbounded workers.
private val nativeMemoryHealthExecutor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
    ArrayBlockingQueue<Runnable>(1), { task -> Thread(task, "hans-memory-health-read").apply { isDaemon = true } }).apply {
    allowCoreThreadTimeOut(true)
}

@Composable
internal fun NativeMemoryHealthScreen(onBack: () -> Unit) {
    val uiText = rememberHansTextResolver()
    val context = LocalContext.current.applicationContext
    var request by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<NativeMemoryHealthResult?>(null) }
    LaunchedEffect(request) {
        loading = true
        result = try { readNativeMemoryHealth(context) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.IO) }
        loading = false
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("native_memory_health")) {
        ScreenHeader(title = uiText.text(R.string.ui_codex_memory_bd7222), onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(NativeMemoryHealthPresentation.disclosure(uiText))
            Spacer(Modifier.height(16.dp))
            Text(if (loading) uiText.text(R.string.ui_reading_status_4d36fe) else result?.let { NativeMemoryHealthPresentation.describe(it, uiText) } ?: uiText.text(R.string.ui_unavailable_5db96a))
            TextButton(onClick = { request++ }, enabled = !loading) { Text(stringResource(R.string.ui_refresh_96bf00)) }
        }
    }
}

/** Merely binds an existing service; never creates/starts the Runtime or a Codex session. */
internal suspend fun readNativeMemoryHealth(context: Context): NativeMemoryHealthResult {
    val ready = CompletableDeferred<IRuntimeService>()
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            ready.complete(IRuntimeService.Stub.asInterface(binder))
        }
        override fun onServiceDisconnected(name: ComponentName) { ready.completeExceptionally(IllegalStateException("Runtime unavailable")) }
        override fun onBindingDied(name: ComponentName) { ready.completeExceptionally(IllegalStateException("Runtime unavailable")) }
        override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(IllegalStateException("Runtime unavailable")) }
    }
    var bound = false
    return try {
        bound = context.bindService(Intent(context, RuntimeService::class.java), connection, 0)
        if (!bound) return NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.CONFIGURATION_UNRESOLVED)
        val service = withTimeout(5_000) { ready.await() }
        awaitNativeMemoryHealthReply { service.readNativeMemoryHealth(0) }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.BUSY)
    } finally {
        if (bound) context.unbindService(connection)
    }
}

internal suspend fun awaitNativeMemoryHealthReply(timeoutMillis: Long = 10_000, read: () -> String): NativeMemoryHealthResult {
    require(timeoutMillis > 0)
    val reply = CompletableDeferred<NativeMemoryHealthResult>()
    val work = nativeMemoryHealthExecutor.submit {
        try { reply.complete(NativeMemoryHealthWire.decode(read())) }
        catch (_: Exception) { reply.complete(NativeMemoryHealthResult.Unavailable(NativeMemoryUnavailableReason.IO)) }
    }
    return try { withTimeout(timeoutMillis) { reply.await() } }
    finally { work.cancel(true); nativeMemoryHealthExecutor.purge() }
}
