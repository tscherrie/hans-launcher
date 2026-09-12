package ai.hans.standard.plugins

import ai.hans.standard.mcp.RemoteMcpConnectionReason
import ai.hans.standard.mcp.RemoteMcpConnectionRequest
import ai.hans.standard.mcp.RemoteMcpConnectionRequiredException
import ai.hans.standard.mcp.RemoteMcpActivationIdentity
import ai.hans.standard.mcp.RemoteMcpDeclaredToolHints
import ai.hans.standard.mcp.RemoteMcpPolicyReviewRequest
import ai.hans.standard.mcp.RemoteMcpPolicyReviewRequiredException
import ai.hans.standard.mcp.RemoteMcpPolicyReviewToolSummary
import ai.hans.standard.plugins.install.PluginInstallJournal
import ai.hans.standard.plugins.install.PluginInstallJournalPhase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginInstallTransactionCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun runtimeCommitWaitsForExactFreshListProof() {
        val fixture = fixture(PluginRuntimeKind.EMBEDDED_PYTHON)
        val prepared = fixture.coordinator.prepare("plugin-op:1", fixture.record)

        assertTrue(prepared is PluginInstallRuntimePreparation.Prepared)
        assertEquals(0, fixture.transaction.commits)
        fixture.coordinator.markRemoteInstallIntent("plugin-op:1")
        fixture.coordinator.markAppServerAccepted("plugin-op:1")
        assertTrue(
            fixture.coordinator.proveAndCommit(
                "plugin-op:1",
                listOf(installedProof(fixture.record)),
            ),
        )
        assertEquals(1, fixture.transaction.commits)
        assertEquals(1, fixture.transaction.finalizes)
        assertEquals(0, fixture.transaction.rollbacks)
        assertEquals(0, fixture.coordinator.pendingCount())
    }

    @Test
    fun missingPostconditionRollsBackPreparedDependencies() {
        val fixture = fixture(PluginRuntimeKind.EMBEDDED_PYTHON)
        fixture.coordinator.prepare("plugin-op:2", fixture.record)
        fixture.coordinator.markRemoteInstallIntent("plugin-op:2")
        fixture.coordinator.markAppServerAccepted("plugin-op:2")

        assertFalse(fixture.coordinator.proveAndCommit("plugin-op:2", emptyList()))
        assertEquals(0, fixture.transaction.commits)
        assertEquals(1, fixture.transaction.rollbacks)
        assertEquals(0, fixture.coordinator.pendingCount())
    }

    @Test
    fun explicitFailureRollsBackAndIsIdempotentlyUnknownAfterward() {
        val fixture = fixture(PluginRuntimeKind.EMBEDDED_PYTHON)
        fixture.coordinator.prepare("plugin-op:3", fixture.record)

        assertTrue(fixture.coordinator.fail("plugin-op:3"))
        assertFalse(fixture.coordinator.fail("plugin-op:3"))
        assertEquals(1, fixture.transaction.rollbacks)
    }

    @Test
    fun sessionLossRollsBackEveryPreparedRuntime() {
        val first = fixture(PluginRuntimeKind.EMBEDDED_PYTHON)
        first.coordinator.prepare("plugin-op:loss", first.record)

        first.coordinator.abortAll()

        assertEquals(1, first.transaction.rollbacks)
        assertEquals(0, first.coordinator.pendingCount())
        assertFalse(first.coordinator.fail("plugin-op:loss"))
    }

    @Test
    fun unsupportedNodeNeverReachesAppServerAndRollsBackPreparation() {
        val fixture = fixture(PluginRuntimeKind.NODE_JS)

        assertTrue(
            fixture.coordinator.prepare("plugin-op:4", fixture.record)
                is PluginInstallRuntimePreparation.Rejected,
        )
        assertEquals(1, fixture.transaction.rollbacks)
        assertEquals(0, fixture.coordinator.pendingCount())
    }

    @Test
    fun preparedSurfaceCapabilitiesParticipateInTheFinalCompatibilityProof() {
        val fixture = fixture(
            kind = PluginRuntimeKind.EMBEDDED_PYTHON,
            requiredCapabilities = listOf(PluginCapabilityRequirement("phone.camera")),
            preparedCapabilities = setOf("phone.camera"),
        )

        assertTrue(
            fixture.coordinator.prepare("plugin-op:capability", fixture.record) is
                PluginInstallRuntimePreparation.Prepared,
        )
        assertEquals(0, fixture.transaction.rollbacks)
    }

    @Test
    fun proofRejectsAnotherInstalledVersionAndRollsBackExactlyOnce() {
        val fixture = fixture(PluginRuntimeKind.EMBEDDED_PYTHON)
        fixture.coordinator.prepare("plugin-op:version", fixture.record)
        fixture.coordinator.markRemoteInstallIntent("plugin-op:version")
        fixture.coordinator.markAppServerAccepted("plugin-op:version")

        assertFalse(
            fixture.coordinator.proveAndCommit(
                "plugin-op:version",
                listOf(installedProof(fixture.record).copy(localVersion = "2.0")),
            ),
        )
        assertEquals(0, fixture.transaction.commits)
        assertEquals(1, fixture.transaction.rollbacks)
        assertEquals(0, fixture.coordinator.pendingCount())
    }

    @Test
    fun proofRejectsSourceMutationAfterPreparation() {
        val fixture = fixture(PluginRuntimeKind.EMBEDDED_PYTHON)
        val marker = File(checkNotNull(fixture.record.localSourcePath), "content.txt")
        marker.writeText("prepared")
        fixture.coordinator.prepare("plugin-op:source", fixture.record)
        fixture.coordinator.markRemoteInstallIntent("plugin-op:source")
        fixture.coordinator.markAppServerAccepted("plugin-op:source")
        marker.writeText("mutated after preparation")

        assertFalse(
            fixture.coordinator.proveAndCommit(
                "plugin-op:source",
                listOf(installedProof(fixture.record)),
            ),
        )
        assertEquals(1, fixture.transaction.rollbacks)
        assertEquals(0, fixture.coordinator.pendingCount())
    }

    @Test
    fun sourceIdentityIsBoundedBeforeDependenciesArePrepared() {
        val fixture = fixture(
            kind = PluginRuntimeKind.EMBEDDED_PYTHON,
            sourceIdentityHasher = BoundedPluginSourceIdentityHasher(maximumFiles = 1),
        )
        File(checkNotNull(fixture.record.localSourcePath), "second-file.txt").writeText("extra")

        val result = fixture.coordinator.prepare("plugin-op:bounded-source", fixture.record)

        assertTrue(result is PluginInstallRuntimePreparation.Rejected)
        assertEquals(
            "runtime_source_identity_unavailable",
            (result as PluginInstallRuntimePreparation.Rejected).reason,
        )
        assertEquals(0, fixture.transaction.commits)
        assertEquals(0, fixture.transaction.rollbacks)
        assertEquals(0, fixture.coordinator.pendingCount())
    }

    @Test
    fun sessionLossCancelsAnInFlightSourceIdentityHash() {
        val started = CountDownLatch(1)
        val result = AtomicReference<PluginInstallRuntimePreparation>()
        val fixture = fixture(
            kind = PluginRuntimeKind.EMBEDDED_PYTHON,
            sourceIdentityHasher = PluginSourceIdentityHasher { _, cancellation ->
                started.countDown()
                while (!cancellation.isCancellationRequested()) Thread.yield()
                throw PluginRuntimePreparationCancelledException()
            },
        )
        val worker = Thread {
            result.set(fixture.coordinator.prepare("plugin-op:source-cancel", fixture.record))
        }.apply { start() }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        fixture.coordinator.abortAll()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertEquals(
            "runtime_prepare_cancelled",
            (result.get() as PluginInstallRuntimePreparation.Rejected).reason,
        )
        assertEquals(0, fixture.transaction.commits)
        assertEquals(0, fixture.transaction.rollbacks)
        assertEquals(0, fixture.coordinator.pendingCount())
    }

    @Test
    fun freshInstalledProofUsesTheSameBoundedSourceHasher() {
        val calls = AtomicInteger(0)
        val delegate = BoundedPluginSourceIdentityHasher()
        val fixture = fixture(
            kind = PluginRuntimeKind.EMBEDDED_PYTHON,
            sourceIdentityHasher = PluginSourceIdentityHasher { root, cancellation ->
                calls.incrementAndGet()
                delegate.digest(root, cancellation)
            },
        )

        fixture.coordinator.prepare("plugin-op:source-proof", fixture.record)
        assertEquals(2, calls.get())
        fixture.coordinator.markRemoteInstallIntent("plugin-op:source-proof")
        fixture.coordinator.markAppServerAccepted("plugin-op:source-proof")
        assertTrue(
            fixture.coordinator.proveAndCommit(
                "plugin-op:source-proof",
                listOf(installedProof(fixture.record)),
            ),
        )

        assertEquals(3, calls.get())
        assertEquals(1, fixture.transaction.commits)
        assertEquals(1, fixture.transaction.finalizes)
    }

    @Test
    fun preflightExceptionAlwaysReleasesPreparationSlot() {
        val fixture = fixture(
            kind = PluginRuntimeKind.EMBEDDED_PYTHON,
            availableCapabilities = { error("capability probe failed") },
        )

        assertTrue(
            fixture.coordinator.prepare("plugin-op:preflight", fixture.record) is
                PluginInstallRuntimePreparation.Rejected,
        )
        assertEquals(0, fixture.coordinator.pendingCount())
        assertFalse(fixture.coordinator.fail("plugin-op:preflight"))
    }

    @Test
    fun failCannotRaceARuntimeCommitAfterTerminalOwnershipWasClaimed() {
        val commitStarted = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val fixture = fixture(
            kind = PluginRuntimeKind.EMBEDDED_PYTHON,
            commitStarted = commitStarted,
            allowCommit = allowCommit,
        )
        fixture.coordinator.prepare("plugin-op:commit-fail", fixture.record)
        fixture.coordinator.markRemoteInstallIntent("plugin-op:commit-fail")
        fixture.coordinator.markAppServerAccepted("plugin-op:commit-fail")
        val committed = AtomicReference<Boolean>()
        val worker = Thread {
            committed.set(
                fixture.coordinator.proveAndCommit(
                    "plugin-op:commit-fail",
                    listOf(installedProof(fixture.record)),
                ),
            )
        }.apply { start() }

        assertTrue(commitStarted.await(2, TimeUnit.SECONDS))
        assertFalse(fixture.coordinator.fail("plugin-op:commit-fail"))
        allowCommit.countDown()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertTrue(committed.get())
        assertEquals(1, fixture.transaction.commits)
        assertEquals(1, fixture.transaction.finalizes)
        assertEquals(0, fixture.transaction.rollbacks)
        assertEquals(0, fixture.coordinator.pendingCount())
    }

    @Test
    fun abortAllCannotRollbackACommitOwnedByAnotherThread() {
        val commitStarted = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val fixture = fixture(
            kind = PluginRuntimeKind.EMBEDDED_PYTHON,
            commitStarted = commitStarted,
            allowCommit = allowCommit,
        )
        fixture.coordinator.prepare("plugin-op:commit-abort", fixture.record)
        fixture.coordinator.markRemoteInstallIntent("plugin-op:commit-abort")
        fixture.coordinator.markAppServerAccepted("plugin-op:commit-abort")
        val committed = AtomicReference<Boolean>()
        val worker = Thread {
            committed.set(
                fixture.coordinator.proveAndCommit(
                    "plugin-op:commit-abort",
                    listOf(installedProof(fixture.record)),
                ),
            )
        }.apply { start() }

        assertTrue(commitStarted.await(2, TimeUnit.SECONDS))
        fixture.coordinator.abortAll()
        assertEquals(1, fixture.coordinator.pendingCount())
        allowCommit.countDown()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertTrue(committed.get())
        assertEquals(1, fixture.transaction.commits)
        assertEquals(1, fixture.transaction.finalizes)
        assertEquals(0, fixture.transaction.rollbacks)
        assertEquals(0, fixture.coordinator.pendingCount())
    }

    @Test
    fun cancellationListenersRunExactlyOnceBeforeOrAfterCancellation() {
        repeat(200) {
            val cancellation = MutablePluginRuntimePreparationCancellation()
            val calls = AtomicInteger(0)
            val start = CountDownLatch(1)
            val registration = Thread {
                start.await()
                cancellation.onCancel(calls::incrementAndGet)
            }.apply { start() }
            val cancelling = Thread {
                start.await()
                cancellation.cancel()
            }.apply { start() }

            start.countDown()
            registration.join(2_000)
            cancelling.join(2_000)

            assertFalse(registration.isAlive)
            assertFalse(cancelling.isAlive)
            assertEquals(1, calls.get())
            assertFalse(cancellation.cancel())
            assertEquals(1, calls.get())
        }

        val alreadyCancelled = MutablePluginRuntimePreparationCancellation()
        assertTrue(alreadyCancelled.cancel())
        val lateCalls = AtomicInteger(0)
        alreadyCancelled.onCancel(lateCalls::incrementAndGet)
        assertEquals(1, lateCalls.get())
    }

    @Test
    fun sessionLossPushesCancellationIntoAnInFlightDependencyPreparation() {
        val root = temporaryFolder.newFolder()
        val source = File(root, "sample").apply { mkdir() }
        val requirements = PluginRuntimeRequirements(
            pluginId = "sample",
            runtimes = listOf(
                PluginRuntimeRequirement(
                    "runtime",
                    PluginRuntimeKind.EMBEDDED_PYTHON,
                    PluginRuntimePlacement.LOCAL,
                ),
            ),
            entrypoints = listOf(
                PluginEntrypointRequirement(
                    "main",
                    "runtime",
                    PluginEntrypointKind.PYTHON_CALLABLE,
                    "main:run",
                ),
            ),
            capabilities = emptyList(),
        )
        File(source, PluginRuntimeManifestLoader.MANIFEST_NAME)
            .writeBytes(PluginRuntimeRequirementsCodec.encode(requirements))
        val ready = PluginRuntimeEvidenceSource {
            PluginRuntimeEvidence(
                "3.14.7",
                PluginRuntimeAbi.ANDROID_ARM64_V8A,
                PluginRuntimeReadiness.READY,
            )
        }
        val service = PluginRuntimeCompatibilityService(
            PluginRuntimeManifestLoader(listOf(root)),
            PluginRuntimeProbeRegistry(
                StandardAndroidRuntimeProbeRegistrations.create(
                    localAbi = PluginRuntimeAbi.ANDROID_ARM64_V8A,
                    codeMode = ready,
                    python = ready,
                    git = ready,
                    http = ready,
                    androidCapabilities = ready,
                ),
            ),
        )
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val result = AtomicReference<PluginInstallRuntimePreparation>()
        val coordinator = PluginInstallTransactionCoordinator(
            service,
            PluginRuntimeDependencyPreparer { _, _, cancellation ->
                cancellation.onCancel(cancelled::countDown).use {
                    started.countDown()
                    assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                    cancellation.throwIfCancellationRequested()
                    error("cancelled preparation continued")
                }
            },
            availableCapabilities = { emptySet() },
        )
        val worker = Thread {
            result.set(coordinator.prepare("plugin-op:cancel", record(source)))
        }.apply { start() }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        coordinator.abortAll()
        worker.join(2_000)

        assertFalse(worker.isAlive)
        assertNotNull(result.get())
        assertTrue(result.get() is PluginInstallRuntimePreparation.Rejected)
        assertEquals(0, coordinator.pendingCount())
    }

    @Test
    fun provenUninstallHookForwardsExactPluginAndEnvironmentIdentity() {
        val removed = AtomicReference<Pair<String, String>>()
        val fixture = fixture(
            kind = PluginRuntimeKind.EMBEDDED_PYTHON,
            runtimeRemoval = PluginRuntimeDependencyRemoval { pluginId, environmentDigest ->
                removed.set(pluginId to environmentDigest)
                true
            },
        )
        val digest = "a".repeat(64)

        assertTrue(fixture.coordinator.removeCommittedRuntime("sample", digest))
        assertEquals("sample" to digest, removed.get())
    }

    @Test
    fun durableJournalTracksRemoteIntentAndDisappearsOnlyAfterFinalization() {
        val journal = PluginInstallJournal(temporaryFolder.newFolder("install-journal"))
        val fixture = fixture(
            kind = PluginRuntimeKind.EMBEDDED_PYTHON,
            installJournal = journal,
        )

        fixture.coordinator.prepare("plugin-op:journal", fixture.record)
        assertEquals(PluginInstallJournalPhase.LOCAL_PREPARED, journal.readAll().single().phase)
        fixture.coordinator.markRemoteInstallIntent("plugin-op:journal")
        assertEquals(
            PluginInstallJournalPhase.REMOTE_INSTALL_INTENT,
            journal.readAll().single().phase,
        )
        fixture.coordinator.markAppServerAccepted("plugin-op:journal")
        assertEquals(PluginInstallJournalPhase.REMOTE_ACCEPTED, journal.readAll().single().phase)

        assertTrue(
            fixture.coordinator.proveAndCommit(
                "plugin-op:journal",
                listOf(installedProof(fixture.record)),
            ),
        )
        assertTrue(journal.readAll().isEmpty())
        assertEquals(1, fixture.transaction.commits)
        assertEquals(1, fixture.transaction.finalizes)
    }

    @Test
    fun remoteMcpConnectionRequiredIsTypedAndDiscardsUncommittedJournal() {
        val journal = PluginInstallJournal(temporaryFolder.newFolder("remote-mcp-journal"))
        val request = RemoteMcpConnectionRequest(
            pluginId = "sample",
            serverId = "calendar",
            reason = RemoteMcpConnectionReason.MISSING,
        )
        val fixture = fixture(
            kind = PluginRuntimeKind.EMBEDDED_PYTHON,
            installJournal = journal,
            dependencyPreparer = PluginRuntimeDependencyPreparer { _, _, _ ->
                throw RemoteMcpConnectionRequiredException(request)
            },
        )

        val result = fixture.coordinator.prepare("plugin-op:remote-auth", fixture.record)

        assertEquals(
            PluginInstallRuntimePreparation.RemoteMcpConnectionRequired(request),
            result,
        )
        assertTrue(journal.readAll().isEmpty())
        assertEquals(0, fixture.coordinator.pendingCount())
        assertEquals(0, fixture.transaction.commits)
        assertEquals(0, fixture.transaction.rollbacks)
    }

    @Test
    fun remoteMcpPolicyReviewIsTypedSourceBoundAndLeavesNoDurableInstallTransaction() {
        val journal = PluginInstallJournal(temporaryFolder.newFolder("remote-mcp-review-journal"))
        val request = RemoteMcpPolicyReviewRequest(
            activationIdentity = RemoteMcpActivationIdentity(
                pluginId = "sample",
                serverId = "calendar",
                configurationDigest = "a".repeat(64),
            ),
            catalogDigest = "b".repeat(64),
            policyStoreRevision = 7L,
            tools = listOf(
                RemoteMcpPolicyReviewToolSummary(
                    name = "calendar/list",
                    title = "List calendar",
                    declaredHints = RemoteMcpDeclaredToolHints(true, false, true, false),
                    metadataDigest = "c".repeat(64),
                ),
            ),
        )
        val fixture = fixture(
            kind = PluginRuntimeKind.EMBEDDED_PYTHON,
            installJournal = journal,
            dependencyPreparer = PluginRuntimeDependencyPreparer { _, _, _ ->
                throw RemoteMcpPolicyReviewRequiredException(request)
            },
        )

        val result = fixture.coordinator.prepare("plugin-op:remote-review", fixture.record)

        assertTrue(result is PluginInstallRuntimePreparation.RemoteMcpPolicyReviewRequired)
        result as PluginInstallRuntimePreparation.RemoteMcpPolicyReviewRequired
        assertEquals(request, result.request)
        assertTrue(result.sourceSha256.matches(Regex("[0-9a-f]{64}")))
        assertTrue(journal.readAll().isEmpty())
        assertEquals(0, fixture.coordinator.pendingCount())
        assertEquals(0, fixture.transaction.commits)
        assertEquals(0, fixture.transaction.rollbacks)
    }

    @Test
    fun ambiguousRemoteIntentIsReconciledInsteadOfBlindlyRollingBack() {
        val journal = PluginInstallJournal(temporaryFolder.newFolder("ambiguous-journal"))
        val fixture = fixture(
            kind = PluginRuntimeKind.EMBEDDED_PYTHON,
            installJournal = journal,
        )
        fixture.coordinator.prepare("plugin-op:ambiguous", fixture.record)
        fixture.coordinator.markRemoteInstallIntent("plugin-op:ambiguous")

        assertTrue(fixture.coordinator.fail("plugin-op:ambiguous"))

        assertEquals(0, fixture.transaction.rollbacks)
        assertEquals(0, fixture.coordinator.pendingCount())
        assertEquals(
            PluginInstallJournalPhase.RECONCILE_REQUIRED,
            journal.readAll().single().phase,
        )
    }

    private fun fixture(
        kind: PluginRuntimeKind,
        requiredCapabilities: List<PluginCapabilityRequirement> = emptyList(),
        preparedCapabilities: Set<String> = emptySet(),
        availableCapabilities: () -> Set<String> = { emptySet() },
        commitStarted: CountDownLatch? = null,
        allowCommit: CountDownLatch? = null,
        runtimeRemoval: PluginRuntimeDependencyRemoval = PluginRuntimeDependencyRemoval.DENY_ALL,
        sourceIdentityHasher: PluginSourceIdentityHasher = BoundedPluginSourceIdentityHasher(),
        installJournal: PluginInstallJournal? = null,
        dependencyPreparer: PluginRuntimeDependencyPreparer? = null,
    ): Fixture {
        val root = temporaryFolder.newFolder()
        val source = File(root, "sample").apply { mkdir() }
        val requirements = PluginRuntimeRequirements(
            pluginId = "sample",
            runtimes = listOf(
                PluginRuntimeRequirement("runtime", kind, PluginRuntimePlacement.LOCAL),
            ),
            entrypoints = listOf(
                PluginEntrypointRequirement(
                    "main",
                    "runtime",
                    if (kind == PluginRuntimeKind.EMBEDDED_PYTHON) {
                        PluginEntrypointKind.PYTHON_CALLABLE
                    } else {
                        PluginEntrypointKind.RELATIVE_FILE
                    },
                    if (kind == PluginRuntimeKind.EMBEDDED_PYTHON) "main:run" else "main.js",
                ),
            ),
            capabilities = requiredCapabilities,
        )
        File(source, PluginRuntimeManifestLoader.MANIFEST_NAME)
            .writeBytes(PluginRuntimeRequirementsCodec.encode(requirements))
        if (kind == PluginRuntimeKind.NODE_JS) File(source, "main.js").writeText("export {}")
        val record = record(source)
        val transaction = FakeTransaction(
            resolvedEntrypointIds = setOf("main"),
            availableCapabilityIds = preparedCapabilities,
            commitStarted = commitStarted,
            allowCommit = allowCommit,
        )
        val ready = PluginRuntimeEvidenceSource {
            PluginRuntimeEvidence(
                "3.14.7",
                PluginRuntimeAbi.ANDROID_ARM64_V8A,
                PluginRuntimeReadiness.READY,
            )
        }
        val registry = PluginRuntimeProbeRegistry(
            StandardAndroidRuntimeProbeRegistrations.create(
                localAbi = PluginRuntimeAbi.ANDROID_ARM64_V8A,
                codeMode = ready,
                python = ready,
                git = ready,
                http = ready,
                androidCapabilities = ready,
            ),
        )
        val service = PluginRuntimeCompatibilityService(
            PluginRuntimeManifestLoader(listOf(root)),
            registry,
        )
        return Fixture(
            PluginInstallTransactionCoordinator(
                service,
                dependencyPreparer ?: PluginRuntimeDependencyPreparer { _, _, _ -> transaction },
                availableCapabilities = availableCapabilities,
                runtimeRemoval = runtimeRemoval,
                sourceIdentityHasher = sourceIdentityHasher,
                installJournal = installJournal,
                ownerProcessEpoch = "test-process",
                leaseIdFactory = { "test-lease" },
                wallClockMillis = { 1_000L },
                elapsedRealtimeMillis = { 100L },
            ),
            record,
            transaction,
        )
    }

    private fun record(source: File): PluginWireRecord {
        val card = PluginCard(
            handle = PluginHandle("2".repeat(64)),
            pluginId = "sample",
            name = "sample",
            displayName = null,
            shortDescription = null,
            marketplaceDisplayName = "Local",
            installed = false,
            enabled = true,
            availability = PluginAvailability.AVAILABLE,
            disabledReason = null,
            installPolicy = PluginInstallPolicy.AVAILABLE,
            authPolicy = PluginAuthPolicy.ON_USE,
            sourceKind = PluginSourceKind.LOCAL,
            logoUrl = null,
            logoDarkUrl = null,
            capabilities = emptyList(),
            featured = false,
        )
        return PluginWireRecord(
            PluginLocator("sample", "sample", "local", "/private/marketplace.json"),
            card,
            "1.0",
            null,
            source.absolutePath,
        )
    }

    private fun installedProof(record: PluginWireRecord): PluginWireRecord = record.copy(
        card = record.card.copy(installed = true),
        localVersion = checkNotNull(record.availableVersion),
    )

    private class FakeTransaction(
        override val resolvedEntrypointIds: Set<String>,
        override val availableCapabilityIds: Set<String> = emptySet(),
        private val commitStarted: CountDownLatch? = null,
        private val allowCommit: CountDownLatch? = null,
    ) : PluginRuntimeDependencyTransaction {
        var commits = 0
        var rollbacks = 0
        var finalizes = 0

        override fun commit() {
            commitStarted?.countDown()
            allowCommit?.let { assertTrue(it.await(2, TimeUnit.SECONDS)) }
            commits += 1
        }

        override fun rollback() {
            rollbacks += 1
        }

        override fun finalizeCommit() {
            finalizes += 1
        }
    }

    private data class Fixture(
        val coordinator: PluginInstallTransactionCoordinator,
        val record: PluginWireRecord,
        val transaction: FakeTransaction,
    )
}
