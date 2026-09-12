package ai.hans.standard.diagnostics

import android.app.Activity
import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.Os
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** DUMP-authorized, read-only publisher-key migration receipt endpoint. */
class HansMigrationDiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val decision = MigrationDiagnosticsDispatchGate.evaluate(
            ordered = isOrderedBroadcast,
        )
        when (decision) {
            MigrationDiagnosticsDispatchDecision.REJECT_NOT_ORDERED -> {
                // Android has no result channel for an unordered broadcast. Returning before
                // parsing/collection is the fail-closed behavior; calling setResult here would
                // itself throw on a real unordered delivery.
                return
            }
            MigrationDiagnosticsDispatchDecision.ACCEPT -> Unit
        }
        val request = MigrationDiagnosticsContract.parse(
            intent,
            ComponentName(context, HansMigrationDiagnosticsReceiver::class.java),
        )
        if (request == null) {
            returnFailure(MigrationDiagnosticsContract.RESULT_MALFORMED)
            return
        }

        val pending = goAsync()
        val attempt = MigrationDiagnosticsAttempt.create()
        val completion = MigrationPendingResultCompletion { code, data ->
            pending.resultCode = code
            pending.resultData = data
            pending.finish()
        }
        val timeout = Runnable {
            if (
                completion.complete(
                    Activity.RESULT_CANCELED,
                    MigrationDiagnosticsContract.RESULT_TIMEOUT,
                )
            ) {
                attempt.cancel()
            }
        }
        MAIN_HANDLER.postDelayed(timeout, MigrationDiagnosticsContract.RECEIVER_TIMEOUT_MILLIS)
        val submitted = attempt.submit(
            work = {
                MigrationDiagnosticsOutcome.collect {
                    MigrationDiagnosticsAndroidEnvironment.collector(context.applicationContext)
                        .collect(request)
                }
            },
            deliver = { result ->
                val accepted = when (result) {
                    is MigrationDiagnosticsOutcome.Collected -> completion.complete(
                        Activity.RESULT_OK,
                        result.receipt,
                    )
                    is MigrationDiagnosticsOutcome.Blocked -> completion.complete(
                        Activity.RESULT_CANCELED,
                        result.blocker.resultData,
                    )
                    MigrationDiagnosticsOutcome.Failed -> completion.complete(
                        Activity.RESULT_CANCELED,
                        MigrationDiagnosticsContract.RESULT_COLLECTION_FAILED,
                    )
                }
                if (accepted) {
                    MAIN_HANDLER.removeCallbacks(timeout)
                    attempt.cancel()
                }
            },
        )
        if (!submitted) {
            MAIN_HANDLER.removeCallbacks(timeout)
            attempt.cancel()
            completion.complete(
                Activity.RESULT_CANCELED,
                MigrationDiagnosticsContract.RESULT_COLLECTION_FAILED,
            )
        }
    }

    private fun returnFailure(code: String) {
        setResult(Activity.RESULT_CANCELED, code, null)
    }

    companion object {
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
    }
}

/** One private worker per request: a timed-out read can never poison later diagnostics. */
internal class MigrationDiagnosticsAttempt(
    private val executor: ExecutorService,
) {
    fun <T> submit(work: () -> T, deliver: (T) -> Unit): Boolean = try {
        executor.execute { deliver(work()) }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    fun cancel() {
        executor.shutdownNow()
    }

    companion object {
        fun create(): MigrationDiagnosticsAttempt = MigrationDiagnosticsAttempt(
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "hans-migration-diagnostics").apply { isDaemon = true }
            },
        )
    }
}

internal class MigrationPendingResultCompletion(
    private val finish: (resultCode: Int, resultData: String) -> Unit,
) {
    private val completed = AtomicBoolean(false)

    fun complete(resultCode: Int, resultData: String): Boolean {
        require(resultData.toByteArray(StandardCharsets.UTF_8).size <= MigrationDiagnosticsContract.MAX_RESULT_BYTES)
        if (!completed.compareAndSet(false, true)) return false
        finish(resultCode, resultData)
        return true
    }
}

internal object MigrationDiagnosticsAndroidEnvironment {
    fun collector(context: Context): MigrationDiagnosticsCollector {
        val snapshot = packageSnapshot(context)
        val data = File(context.applicationInfo.dataDir).toPath().normalize()
        val roots = MigrationDiagnosticsRoots(
            dataDirectory = data,
            filesDirectory = data.resolve("files"),
            noBackupDirectory = data.resolve("no_backup"),
        )
        val readinessPath = roots.noBackupDirectory.resolve(
            MigrationSessionReadinessPublisher.FILE_NAME,
        )
        return MigrationDiagnosticsCollector(
            roots = roots,
            packageSnapshot = snapshot,
            readinessReader = { readMigrationSessionReadiness(readinessPath) },
            interactiveProcessId = { interactiveMainProcessId(context) },
        )
    }

    private fun interactiveMainProcessId(context: Context): Int? {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        return activityManager.runningAppProcesses
            .orEmpty()
            .asSequence()
            .filter { process ->
                process.uid == context.applicationInfo.uid && process.processName == context.packageName
            }
            .map { it.pid }
            .filter { it > 0 }
            .distinct()
            .singleOrNull()
    }

    @Suppress("DEPRECATION")
    fun packageSnapshot(context: Context): MigrationPackageSnapshot {
        val packageName = context.packageName
        require(packageName == MigrationPackageSnapshot.PACKAGE_NAME)
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        val signing = checkNotNull(info.signingInfo)
        val currentSigners = signing.apkContentsSigners.orEmpty()
        require(currentSigners.size == 1) { "migration_signer_count" }
        val current = certificateSha256(currentSigners.single().toByteArray())
        val history = signing.signingCertificateHistory.orEmpty()
            .map { certificateSha256(it.toByteArray()) }
            .distinct()
        require(history.size in 1..2) { "migration_signer_history" }
        require(current in history) { "migration_current_signer" }
        val dataDirectory = File(context.applicationInfo.dataDir)
        val inode = Os.lstat(dataDirectory.absolutePath).st_ino
        val roleManager = context.getSystemService(RoleManager::class.java)
        return MigrationPackageSnapshot(
            packageName = packageName,
            longVersionCode = info.longVersionCode,
            versionName = info.versionName.orEmpty(),
            uid = context.applicationInfo.uid,
            dataDirInode = inode,
            debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
            currentSignerSha256 = current,
            signingHistorySha256 = history,
            homeRoleHeld = runCatching {
                roleManager?.isRoleHeld(RoleManager.ROLE_HOME) == true
            }.getOrDefault(false),
            lastUpdateTimeMillis = info.lastUpdateTime,
        )
    }

    private fun certificateSha256(certificate: ByteArray): String = try {
        MessageDigest.getInstance("SHA-256")
            .digest(certificate)
            .toLowerHex()
    } finally {
        certificate.fill(0)
    }
}
