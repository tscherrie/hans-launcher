package ai.hans.standard.backup

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One process-wide ordering boundary for backup-owned state. Short reads/mutations hold [lock];
 * asynchronous automation work instead keeps a reservation until its actual completion. An
 * import never waits for or cancels an executor and can never mistake onStop/cancel for quiescence.
 */
class HansBackupMaintenance {
    private val lock = Any()
    private var acceptedActivities = 0
    private var maintenanceThread: Thread? = null

    @Volatile
    private var recoveryRequired = false

    val isRecoveryReady: Boolean
        get() = !recoveryRequired

    fun <T> withStateAccess(block: () -> T): T = synchronized(lock) {
        requireReadyUnlessRecovering()
        block()
    }

    fun reserveAutomationActivity(): AutoCloseable = synchronized(lock) {
        requireReadyUnlessRecovering()
        check(maintenanceThread == null) { "backup_cannot_dispatch_during_maintenance" }
        check(acceptedActivities < Int.MAX_VALUE)
        acceptedActivities += 1
        val closed = AtomicBoolean(false)
        AutoCloseable {
            if (closed.compareAndSet(false, true)) synchronized(lock) {
                check(acceptedActivities > 0)
                acceptedActivities -= 1
            }
        }
    }

    fun <T> importExclusively(block: () -> T): T = synchronized(lock) {
        requireReadyUnlessRecovering()
        enterMaintenance()
        try {
            block()
        } finally {
            maintenanceThread = null
        }
    }

    /** A failed journal read/rollback remains fail-closed until a later successful recovery. */
    fun <T> recoverBeforeRuntime(block: () -> T): T = synchronized(lock) {
        enterMaintenance()
        recoveryRequired = true
        try {
            block().also { recoveryRequired = false }
        } finally {
            maintenanceThread = null
        }
    }

    fun requireRecovery() = synchronized(lock) {
        recoveryRequired = true
    }

    private fun enterMaintenance() {
        check(maintenanceThread == null) { "backup_nested_maintenance" }
        if (acceptedActivities != 0) throw HansBackupException("backup_runtime_busy")
        maintenanceThread = Thread.currentThread()
    }

    private fun requireReadyUnlessRecovering() {
        if (recoveryRequired && maintenanceThread !== Thread.currentThread()) {
            throw HansBackupException("backup_import_rollback_pending")
        }
    }
}

/** Shared across independently constructed Android settings/profile/storage adapters. */
object HansBackupProcessState {
    val maintenance = HansBackupMaintenance()
}

/** The same ordering function is exercised by host regressions and used by Application startup. */
internal fun startRuntimeAfterBackupRecovery(
    gateway: HansBackupStateGateway,
    startRuntime: () -> Unit,
): Result<Boolean> {
    val recovery = runCatching { gateway.recoverInterruptedImport() }
    if (recovery.isSuccess) startRuntime()
    return recovery
}
