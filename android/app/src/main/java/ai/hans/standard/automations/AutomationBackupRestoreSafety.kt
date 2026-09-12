package ai.hans.standard.automations

/** Backup replaces portable definitions, never runtime ownership or idempotency evidence. */
internal object AutomationBackupRestoreSafety {
    fun requireQuiescent(snapshot: AutomationStorageSnapshot) {
        check(
            snapshot.leases.isEmpty() && snapshot.inbox.isEmpty() &&
                snapshot.confirmations.isEmpty() && snapshot.runs.none {
                    it.state in setOf(
                        AutomationRunState.PENDING,
                        AutomationRunState.LEASED,
                        AutomationRunState.RETRY_WAIT,
                    ) || it.dispatchFence != null
                },
        ) { "backup_automation_work_unsettled" }
    }

    fun requireSafeReplacement(
        current: AutomationStorageSnapshot,
        replacement: AutomationStorageSnapshot,
    ) {
        requireQuiescent(current)
        requireQuiescent(replacement)
        check(
            current.inbox == replacement.inbox && current.runs == replacement.runs &&
                current.receipts == replacement.receipts &&
                current.confirmations == replacement.confirmations &&
                current.manualInvocations == replacement.manualInvocations &&
                current.leases == replacement.leases &&
                current.recoveryState == replacement.recoveryState,
        ) { "backup_automation_evidence_changed" }
    }

    fun replaceDefinitions(
        current: AutomationStorageSnapshot,
        definitions: List<AutomationDefinition>,
    ): AutomationStorageSnapshot {
        requireQuiescent(current)
        val previous = current.definitions.associateBy { it.id }
        val unchangedIds = definitions.filter { previous[it.id] == it }.mapTo(hashSetOf()) { it.id }
        return current.copy(
            definitions = definitions,
            // A changed schedule needs a fresh evaluation; unchanged schedules retain their
            // cursor. Terminal receipts still suppress already-completed occurrences in either case.
            cursors = current.cursors.filter { it.automationId in unchangedIds },
        )
    }
}
