package ai.hans.standard.diagnostics

/** Fixed, data-free reasons: neither exception messages nor private state are exported. */
internal enum class MigrationDiagnosticsBlocker(val code: String) {
    AUTH_MISSING("auth_missing"),
    THREAD_MISSING("thread_missing"),
    BACKUP_IMPORT_PENDING("backup_import_pending"),
    CAMERA_PENDING("camera_pending"),
    PROCESS_MISSING("process_missing"),
    READINESS_MISSING("readiness_missing"),
    READINESS_OBSOLETE("readiness_obsolete"),
    READINESS_STALE("readiness_stale"),
    ACCOUNT_NOT_READY("account_not_ready"),
    RUNTIME_NOT_READY("runtime_not_ready"),
    SELECTION_NOT_READY("selection_not_ready"),
    THREAD_MISMATCH("thread_mismatch"),
    THREAD_NOT_RESUMED("thread_not_resumed"),
    MEMORY_NOT_READY("memory_not_ready"),
    SPEECH_NOT_READY("speech_not_ready"),
    SETUP_INCOMPLETE("setup_incomplete"),
    TURN_ACTIVE("turn_active"),
    DICTATION_ACTIVE("dictation_active"),
    LIVE_VOICE_ACTIVE("live_voice_active"),
    AUTOMATION_ACTIVE("automation_active"),
    ;

    val resultData: String get() = "hans.migration-diagnostics.v1:$code"
}

internal class MigrationDiagnosticsBlocked(val blocker: MigrationDiagnosticsBlocker) :
    IllegalArgumentException(blocker.resultData)

internal fun requireMigrationCondition(condition: Boolean, blocker: MigrationDiagnosticsBlocker) {
    if (!condition) throw MigrationDiagnosticsBlocked(blocker)
}

internal sealed interface MigrationDiagnosticsOutcome {
    data class Collected(val receipt: String) : MigrationDiagnosticsOutcome
    data class Blocked(val blocker: MigrationDiagnosticsBlocker) : MigrationDiagnosticsOutcome
    data object Failed : MigrationDiagnosticsOutcome

    companion object {
        fun collect(read: () -> String): MigrationDiagnosticsOutcome = runCatching(read).fold(
            onSuccess = { Collected(it) },
            onFailure = { error ->
                // Match a closed type, never a message, cause chain, file path or arbitrary code.
                if (error is MigrationDiagnosticsBlocked) Blocked(error.blocker) else Failed
            },
        )
    }
}
