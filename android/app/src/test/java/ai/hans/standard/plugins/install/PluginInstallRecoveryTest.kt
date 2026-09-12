package ai.hans.standard.plugins.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginInstallRecoveryTest {
    @Test
    fun tableIsTotalDeterministicAndPureForEveryProofCombination() {
        var decisions = 0
        PluginInstallJournalPhase.entries.forEach { phase ->
            PluginInstallRemoteProof.entries.forEach { remote ->
                PluginInstallLocalRecoverability.entries.forEach { local ->
                    val first = PluginInstallRecovery.decide(phase, remote, local)
                    val second = PluginInstallRecovery.decide(phase, remote, local)
                    assertEquals(first, second)
                    assertTrue(first.reason.startsWith(phase.name.lowercase()))
                    decisions += 1
                }
            }
        }
        assertEquals(
            PluginInstallJournalPhase.entries.size *
                PluginInstallRemoteProof.entries.size *
                PluginInstallLocalRecoverability.entries.size,
            decisions,
        )
    }

    @Test
    fun beforeRemoteIntentRollsBackOnlyOwnedLocalStateAndQuarantinesUnexpectedRemoteState() {
        listOf(
            PluginInstallJournalPhase.LOCAL_PREPARING,
            PluginInstallJournalPhase.LOCAL_PREPARED,
        ).forEach { phase ->
            assertAction(
                PluginInstallRecoveryAction.ROLLBACK,
                phase,
                PluginInstallRemoteProof.ABSENT,
                PluginInstallLocalRecoverability.PREPARED,
            )
            assertAction(
                PluginInstallRecoveryAction.ROLLBACK,
                phase,
                PluginInstallRemoteProof.UNAVAILABLE,
                PluginInstallLocalRecoverability.ABSENT,
            )
            assertAction(
                PluginInstallRecoveryAction.QUARANTINE,
                phase,
                PluginInstallRemoteProof.EXACT,
                PluginInstallLocalRecoverability.PREPARED,
            )
            assertAction(
                PluginInstallRecoveryAction.RETRY,
                phase,
                PluginInstallRemoteProof.ABSENT,
                PluginInstallLocalRecoverability.UNAVAILABLE,
            )
        }
    }

    @Test
    fun acceptedOrAmbiguousInstallCommitsOnlyWithExactRemoteAndRecoverableLocalProof() {
        listOf(
            PluginInstallJournalPhase.REMOTE_INSTALL_INTENT,
            PluginInstallJournalPhase.REMOTE_ACCEPTED,
            PluginInstallJournalPhase.REMOTE_PROVEN_INSTALLED,
            PluginInstallJournalPhase.LOCAL_COMMIT_INTENT,
            PluginInstallJournalPhase.LOCAL_COMMITTED,
            PluginInstallJournalPhase.RECONCILE_REQUIRED,
        ).forEach { phase ->
            assertAction(
                PluginInstallRecoveryAction.COMMIT,
                phase,
                PluginInstallRemoteProof.EXACT,
                PluginInstallLocalRecoverability.PREPARED,
            )
            assertAction(
                PluginInstallRecoveryAction.COMMIT,
                phase,
                PluginInstallRemoteProof.EXACT,
                PluginInstallLocalRecoverability.COMMITTED,
            )
            assertAction(
                PluginInstallRecoveryAction.COMPENSATE,
                phase,
                PluginInstallRemoteProof.EXACT,
                PluginInstallLocalRecoverability.ABSENT,
            )
            assertAction(
                PluginInstallRecoveryAction.ROLLBACK,
                phase,
                PluginInstallRemoteProof.ABSENT,
                PluginInstallLocalRecoverability.PREPARED,
            )
            assertAction(
                PluginInstallRecoveryAction.QUARANTINE,
                phase,
                PluginInstallRemoteProof.CHANGED,
                PluginInstallLocalRecoverability.PREPARED,
            )
            assertAction(
                PluginInstallRecoveryAction.RETRY,
                phase,
                PluginInstallRemoteProof.UNAVAILABLE,
                PluginInstallLocalRecoverability.PREPARED,
            )
        }
    }

    @Test
    fun compensationConvergesOnlyOnFreshAbsentProofAndNeverRemovesChangedRemotePlugin() {
        listOf(
            PluginInstallJournalPhase.ROLLBACK_INTENT,
            PluginInstallJournalPhase.COMPENSATION_UNINSTALL_INTENT,
            PluginInstallJournalPhase.COMPENSATION_ACCEPTED,
        ).forEach { phase ->
            assertAction(
                PluginInstallRecoveryAction.COMPENSATE,
                phase,
                PluginInstallRemoteProof.EXACT,
                PluginInstallLocalRecoverability.PREPARED,
            )
            assertAction(
                PluginInstallRecoveryAction.ROLLBACK,
                phase,
                PluginInstallRemoteProof.ABSENT,
                PluginInstallLocalRecoverability.COMMITTED,
            )
            assertAction(
                PluginInstallRecoveryAction.QUARANTINE,
                phase,
                PluginInstallRemoteProof.CHANGED,
                PluginInstallLocalRecoverability.PREPARED,
            )
        }

        assertAction(
            PluginInstallRecoveryAction.ROLLBACK,
            PluginInstallJournalPhase.COMPENSATION_PROVEN_ABSENT,
            PluginInstallRemoteProof.ABSENT,
            PluginInstallLocalRecoverability.PREPARED,
        )
        assertAction(
            PluginInstallRecoveryAction.QUARANTINE,
            PluginInstallJournalPhase.COMPENSATION_PROVEN_ABSENT,
            PluginInstallRemoteProof.EXACT,
            PluginInstallLocalRecoverability.PREPARED,
        )
    }

    @Test
    fun finalizedAndQuarantinedReceiptsNeverTriggerDestructiveGuessing() {
        assertAction(
            PluginInstallRecoveryAction.COMMIT,
            PluginInstallJournalPhase.FINALIZED,
            PluginInstallRemoteProof.EXACT,
            PluginInstallLocalRecoverability.COMMITTED,
        )
        assertAction(
            PluginInstallRecoveryAction.QUARANTINE,
            PluginInstallJournalPhase.FINALIZED,
            PluginInstallRemoteProof.ABSENT,
            PluginInstallLocalRecoverability.COMMITTED,
        )
        PluginInstallRemoteProof.entries.forEach { remote ->
            PluginInstallLocalRecoverability.entries.forEach { local ->
                assertAction(
                    PluginInstallRecoveryAction.QUARANTINE,
                    PluginInstallJournalPhase.QUARANTINED,
                    remote,
                    local,
                )
            }
        }
    }

    private fun assertAction(
        expected: PluginInstallRecoveryAction,
        phase: PluginInstallJournalPhase,
        remote: PluginInstallRemoteProof,
        local: PluginInstallLocalRecoverability,
    ) {
        assertEquals(expected, PluginInstallRecovery.decide(phase, remote, local).action)
    }
}
