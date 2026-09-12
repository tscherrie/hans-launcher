package ai.hans.standard.plugins

import ai.hans.standard.plugins.install.PluginDependencyRecoveryComponent
import ai.hans.standard.plugins.install.PluginDependencyRecoveryComponentKind
import ai.hans.standard.plugins.install.PluginDependencyRecoveryDescriptor
import ai.hans.standard.plugins.install.PluginDependencyRecoveryKind
import ai.hans.standard.plugins.install.PluginInstallJournalPhase
import ai.hans.standard.plugins.install.PluginInstallLocalRecoverability
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CompositePluginRuntimeDependenciesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun prepareCommitFinalizeUsesFixedOrderAndUnifiesPublishedIds() {
        val events = mutableListOf<String>()
        val python = transaction("python", pythonDescriptor(), events, setOf("py-entry"), setOf("py"))
        val surface = transaction(
            "surface",
            surface().toSingleComponentDescriptor(),
            events,
            setOf("android-entry"),
            setOf("android"),
        )
        val mcp = transaction(
            "mcp",
            PluginDependencyRecoveryDescriptor.composite(listOf(remote("b"), remote("a"))),
            events,
            setOf("mcp-entry"),
            setOf("remote"),
        )
        val preparer = preparer(events, python, surface, mcp)

        val composite = preparer.prepare(requirements(), temporaryFolder.root, cancellation())
        assertEquals(listOf("prepare:python", "prepare:surface", "prepare:mcp"), events)
        assertEquals(setOf("py-entry", "android-entry", "mcp-entry"), composite.resolvedEntrypointIds)
        assertEquals(setOf("py", "android", "remote"), composite.availableCapabilityIds)
        assertEquals(
            listOf("python", "surface", "a", "b"),
            checkNotNull(composite.recoveryDescriptor).components.map { it.componentId },
        )

        composite.commit()
        composite.finalizeCommit()
        assertEquals(
            listOf(
                "prepare:python", "prepare:surface", "prepare:mcp",
                "commit:python", "commit:surface", "commit:mcp",
                "finalize:python", "finalize:surface", "finalize:mcp",
            ),
            events,
        )
    }

    @Test
    fun partialPrepareFailureRollsBackOnlyPreparedOwnersInReverseOrder() {
        val events = mutableListOf<String>()
        val python = transaction("python", pythonDescriptor(), events)
        val surface = transaction("surface", surface().toSingleComponentDescriptor(), events)
        val failure = IllegalStateException("mcp prepare failed")
        val recovery = recoveryProvider(allAbsentProvider(), allAbsentComponent(), allAbsentComponent())
        val preparer = CompositePluginRuntimeDependencyPreparer(
            python = fixedPreparer("python", events, python),
            surface = fixedPreparer("surface", events, surface),
            remoteMcp = PluginRuntimeDependencyPreparer { _, _, _ ->
                events += "prepare:mcp"
                throw failure
            },
            recovery = recovery,
        )

        assertEquals(
            failure,
            assertThrows(IllegalStateException::class.java) {
                preparer.prepare(requirements(), temporaryFolder.root, cancellation())
            },
        )
        assertEquals(
            listOf(
                "prepare:python", "prepare:surface", "prepare:mcp",
                "rollback:surface", "rollback:python",
            ),
            events,
        )
    }

    @Test
    fun finalizeWaitsForEveryCommitAndRetrySkipsAlreadyCommittedOwners() {
        val events = mutableListOf<String>()
        val failSurfaceOnce = AtomicBoolean(true)
        val python = transaction("python", pythonDescriptor(), events)
        val surface = transaction(
            "surface",
            surface().toSingleComponentDescriptor(),
            events,
            commitFailure = {
                if (failSurfaceOnce.compareAndSet(true, false)) {
                    IllegalStateException("surface commit failed")
                } else {
                    null
                }
            },
        )
        val mcp = transaction("mcp", remote("mcp").toSingleComponentDescriptor(), events)
        val composite = preparer(events, python, surface, mcp)
            .prepare(requirements(), temporaryFolder.root, cancellation())

        assertThrows(IllegalStateException::class.java) { composite.commit() }
        assertThrows(IllegalStateException::class.java) { composite.finalizeCommit() }
        composite.commit()
        composite.finalizeCommit()
        assertEquals(1, events.count { it == "commit:python" })
        assertEquals(2, events.count { it == "commit:surface" })
        assertEquals(1, events.count { it == "commit:mcp" })
        assertEquals(
            listOf("finalize:python", "finalize:surface", "finalize:mcp"),
            events.filter { it.startsWith("finalize:") },
        )
    }

    @Test
    fun rollbackAttemptsEveryOwnerInReverseOrderAndAggregatesFailures() {
        val events = mutableListOf<String>()
        val python = transaction("python", pythonDescriptor(), events)
        val surface = transaction("surface", surface().toSingleComponentDescriptor(), events)
        val mcp = transaction(
            "mcp",
            remote("mcp").toSingleComponentDescriptor(),
            events,
            rollbackFailure = IllegalStateException("mcp rollback failed"),
        )
        val composite = preparer(events, python, surface, mcp)
            .prepare(requirements(), temporaryFolder.root, cancellation())

        assertThrows(IllegalStateException::class.java) { composite.rollback() }
        assertEquals(
            listOf("rollback:mcp", "rollback:surface", "rollback:python"),
            events.filter { it.startsWith("rollback:") },
        )
    }

    @Test
    fun recoveryLatticeIsChangedThenUnavailableThenAbsentThenCommittedThenPrepared() {
        val descriptor = compositeDescriptor()

        assertState(
            PluginInstallLocalRecoverability.CHANGED,
            recoveryProvider(
                pythonProvider(PluginInstallLocalRecoverability.CHANGED),
                componentProvider(PluginInstallLocalRecoverability.UNAVAILABLE),
                componentProvider(PluginInstallLocalRecoverability.COMMITTED),
            ).recover(PLUGIN_ID, descriptor, PHASE),
        )
        assertState(
            PluginInstallLocalRecoverability.UNAVAILABLE,
            recoveryProvider(
                pythonProvider(PluginInstallLocalRecoverability.COMMITTED),
                componentProvider(PluginInstallLocalRecoverability.UNAVAILABLE),
                componentProvider(PluginInstallLocalRecoverability.PREPARED),
            ).recover(PLUGIN_ID, descriptor, PHASE),
        )
        assertState(
            PluginInstallLocalRecoverability.ABSENT,
            recoveryProvider(allAbsentProvider(), allAbsentComponent(), allAbsentComponent())
                .recover(PLUGIN_ID, descriptor, PHASE),
        )

        val committed = recoveryProvider(
            pythonProvider(PluginInstallLocalRecoverability.COMMITTED),
            componentProvider(PluginInstallLocalRecoverability.COMMITTED),
            componentProvider(PluginInstallLocalRecoverability.COMMITTED),
        ).recover(PLUGIN_ID, descriptor, PHASE)
        assertEquals(PluginInstallLocalRecoverability.COMMITTED, committed.recoverability)
        assertNotNull(committed.transaction)
        assertEquals(descriptor, committed.transaction?.recoveryDescriptor)

        val prepared = recoveryProvider(
            pythonProvider(PluginInstallLocalRecoverability.COMMITTED),
            componentProvider(PluginInstallLocalRecoverability.PREPARED),
            componentProvider(PluginInstallLocalRecoverability.PREPARED),
        ).recover(PLUGIN_ID, descriptor, PHASE)
        assertEquals(PluginInstallLocalRecoverability.PREPARED, prepared.recoverability)
        assertNotNull(prepared.transaction)
    }

    @Test
    fun partialAbsenceAndMismatchedPrivateReceiptQuarantineInsteadOfExposingTransaction() {
        val descriptor = compositeDescriptor()
        val partiallyAbsent = recoveryProvider(
            pythonProvider(PluginInstallLocalRecoverability.COMMITTED),
            componentProvider(PluginInstallLocalRecoverability.ABSENT),
            componentProvider(PluginInstallLocalRecoverability.PREPARED),
        ).recover(PLUGIN_ID, descriptor, PHASE)
        assertState(PluginInstallLocalRecoverability.CHANGED, partiallyAbsent)

        val mismatchingSurface = PluginRuntimeDependencyComponentRecoveryProvider {
                _, expected, _ ->
            val changed = checkNotNull(expected).copy(stateDigest = "9".repeat(64))
            result(
                PluginInstallLocalRecoverability.PREPARED,
                transaction("wrong", changed.toSingleComponentDescriptor(), mutableListOf()),
            )
        }
        val mismatch = recoveryProvider(
            pythonProvider(PluginInstallLocalRecoverability.PREPARED),
            mismatchingSurface,
            componentProvider(PluginInstallLocalRecoverability.PREPARED),
        ).recover(PLUGIN_ID, descriptor, PHASE)
        assertState(PluginInstallLocalRecoverability.CHANGED, mismatch)
    }

    @Test
    fun recoveredTransactionCommitsOnlyPreparedComponentsAndThenFinalizesAll() {
        val events = mutableListOf<String>()
        val descriptor = compositeDescriptor()
        fun provider(
            state: PluginInstallLocalRecoverability,
            name: String,
        ) = PluginRuntimeDependencyComponentRecoveryProvider { _, expected, _ ->
            result(
                state,
                transaction(
                    name,
                    checkNotNull(expected).toSingleComponentDescriptor(),
                    events,
                ),
            )
        }
        val python = PluginRuntimeDependencyRecoveryProvider { _, expected, _ ->
            result(
                PluginInstallLocalRecoverability.COMMITTED,
                transaction("python", checkNotNull(expected), events),
            )
        }
        val recovered = recoveryProvider(
            python,
            provider(PluginInstallLocalRecoverability.PREPARED, "surface"),
            provider(PluginInstallLocalRecoverability.PREPARED, "mcp"),
        ).recover(PLUGIN_ID, descriptor, PHASE)

        checkNotNull(recovered.transaction).commit()
        recovered.transaction.finalizeCommit()
        assertEquals(listOf("commit:surface", "commit:mcp"), events.filter { it.startsWith("commit:") })
        assertEquals(
            listOf("finalize:python", "finalize:surface", "finalize:mcp"),
            events.filter { it.startsWith("finalize:") },
        )
    }

    @Test
    fun legacyPythonAndUndescribedRecoveryRemainBackwardCompatible() {
        val legacy = pythonDescriptor()
        val python = PluginRuntimeDependencyRecoveryProvider { _, expected, _ ->
            if (expected == null) {
                PluginRuntimeDependencyRecoveryResult.withoutTransaction(
                    PluginInstallLocalRecoverability.ABSENT,
                )
            } else {
                result(
                    PluginInstallLocalRecoverability.PREPARED,
                    transaction("python", expected, mutableListOf()),
                )
            }
        }
        val recovery = recoveryProvider(python, allAbsentComponent(), allAbsentComponent())
        val legacyResult = recovery.recover(PLUGIN_ID, legacy, PHASE)
        assertEquals(PluginInstallLocalRecoverability.PREPARED, legacyResult.recoverability)
        assertEquals(legacy, legacyResult.transaction?.recoveryDescriptor)

        val undescribed = recovery.recover(
            PLUGIN_ID,
            null,
            PluginInstallJournalPhase.LOCAL_PREPARING,
        )
        assertState(PluginInstallLocalRecoverability.ABSENT, undescribed)
    }

    private fun preparer(
        events: MutableList<String>,
        python: PluginRuntimeDependencyTransaction,
        surface: PluginRuntimeDependencyTransaction,
        mcp: PluginRuntimeDependencyTransaction,
    ) = CompositePluginRuntimeDependencyPreparer(
        python = fixedPreparer("python", events, python),
        surface = fixedPreparer("surface", events, surface),
        remoteMcp = fixedPreparer("mcp", events, mcp),
        recovery = recoveryProvider(allAbsentProvider(), allAbsentComponent(), allAbsentComponent()),
    )

    private fun fixedPreparer(
        name: String,
        events: MutableList<String>,
        transaction: PluginRuntimeDependencyTransaction,
    ) = PluginRuntimeDependencyPreparer { _, _, _ ->
        events += "prepare:$name"
        transaction
    }

    private fun transaction(
        name: String,
        descriptor: PluginDependencyRecoveryDescriptor,
        events: MutableList<String>,
        entrypoints: Set<String> = emptySet(),
        capabilities: Set<String> = emptySet(),
        commitFailure: () -> Throwable? = { null },
        rollbackFailure: Throwable? = null,
    ) = FakeTransaction(
        name,
        descriptor,
        events,
        entrypoints,
        capabilities,
        commitFailure,
        rollbackFailure,
    )

    private fun recoveryProvider(
        python: PluginRuntimeDependencyRecoveryProvider,
        surface: PluginRuntimeDependencyComponentRecoveryProvider,
        remote: PluginRuntimeDependencyComponentRecoveryProvider,
    ) = CompositePluginRuntimeDependencyRecoveryProvider(python, surface, remote)

    private fun pythonProvider(state: PluginInstallLocalRecoverability) =
        PluginRuntimeDependencyRecoveryProvider { _, expected, _ ->
            val descriptor = checkNotNull(expected)
            result(
                state,
                if (state == PluginInstallLocalRecoverability.PREPARED ||
                    state == PluginInstallLocalRecoverability.COMMITTED) {
                    transaction("python", descriptor, mutableListOf())
                } else {
                    null
                },
            )
        }

    private fun componentProvider(state: PluginInstallLocalRecoverability) =
        PluginRuntimeDependencyComponentRecoveryProvider { _, expected, _ ->
            val component = expected
            result(
                state,
                if (state == PluginInstallLocalRecoverability.PREPARED ||
                    state == PluginInstallLocalRecoverability.COMMITTED) {
                    val exact = checkNotNull(component)
                    transaction(
                        exact.componentId,
                        exact.toSingleComponentDescriptor(),
                        mutableListOf(),
                    )
                } else {
                    null
                },
            )
        }

    private fun allAbsentProvider() = pythonProvider(PluginInstallLocalRecoverability.ABSENT)
    private fun allAbsentComponent() = componentProvider(PluginInstallLocalRecoverability.ABSENT)

    private fun result(
        state: PluginInstallLocalRecoverability,
        transaction: PluginRuntimeDependencyTransaction?,
    ) = if (transaction == null) {
        PluginRuntimeDependencyRecoveryResult.withoutTransaction(state)
    } else {
        PluginRuntimeDependencyRecoveryResult.recoverable(state, transaction)
    }

    private fun assertState(
        expected: PluginInstallLocalRecoverability,
        actual: PluginRuntimeDependencyRecoveryResult,
    ) {
        assertEquals(expected, actual.recoverability)
        assertNull(actual.transaction)
    }

    private fun compositeDescriptor() = PluginDependencyRecoveryDescriptor.composite(
        listOf(pythonComponent(), surface(), remote("mcp")),
    )

    private fun pythonDescriptor() = PluginDependencyRecoveryDescriptor(
        kind = PluginDependencyRecoveryKind.PYTHON_ENVIRONMENT_V1,
        environmentTransactionId = "python-environment",
        environmentDigest = "a".repeat(64),
        entrypointTransactionId = "python-entrypoints",
        entrypointMetadataDigest = "b".repeat(64),
    )

    private fun pythonComponent() = pythonDescriptor().asComponents().single()

    private fun surface() = PluginDependencyRecoveryComponent(
        kind = PluginDependencyRecoveryComponentKind.PLUGIN_SURFACE_V1,
        componentId = PluginDependencyRecoveryComponent.PLUGIN_SURFACE_COMPONENT_ID,
        transactionId = "surface-transaction",
        stateDigest = "c".repeat(64),
    )

    private fun remote(serverId: String) = PluginDependencyRecoveryComponent(
        kind = PluginDependencyRecoveryComponentKind.REMOTE_MCP_V1,
        componentId = serverId,
        transactionId = "mcp-$serverId-transaction",
        stateDigest = "d".repeat(64),
    )

    private fun requirements() = PluginRuntimeRequirements(
        pluginId = PLUGIN_ID,
        runtimes = emptyList(),
        entrypoints = emptyList(),
        capabilities = emptyList(),
    )

    private fun cancellation() = object : PluginRuntimePreparationCancellation {
        override fun isCancellationRequested() = false
        override fun onCancel(action: () -> Unit): Closeable = Closeable { }
    }

    private class FakeTransaction(
        private val name: String,
        override val recoveryDescriptor: PluginDependencyRecoveryDescriptor,
        private val events: MutableList<String>,
        override val resolvedEntrypointIds: Set<String>,
        override val availableCapabilityIds: Set<String>,
        private val commitFailure: () -> Throwable?,
        private val rollbackFailure: Throwable?,
    ) : PluginRuntimeDependencyTransaction {
        override fun commit() {
            events += "commit:$name"
            commitFailure()?.let { throw it }
        }

        override fun rollback() {
            events += "rollback:$name"
            rollbackFailure?.let { throw it }
        }

        override fun finalizeCommit() {
            events += "finalize:$name"
        }
    }

    private companion object {
        const val PLUGIN_ID = "garage"
        val PHASE = PluginInstallJournalPhase.REMOTE_PROVEN_INSTALLED
    }
}
