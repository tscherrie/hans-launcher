package ai.hans.standard.integration

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RevisionedDynamicToolRouterTest {
    @Test
    fun publishKeepsLeasedOldRevisionAndPrunesItAfterClose() {
        val first = snapshot(contributor("local", DynamicToolPlacement.BACKGROUND_ALLOWED))
        val second = snapshot(contributor("remote", DynamicToolPlacement.INTERACTIVE_ONLY),
            contributor("local", DynamicToolPlacement.BACKGROUND_ALLOWED))
        val router = RevisionedDynamicToolRouter(first)
        val old = router.acquire()

        assertEquals(
            DynamicToolSnapshotPublication.Changed(first.revision, second.revision),
            router.publish(second),
        )
        assertEquals(setOf(first.revision, second.revision), router.retainedRevisions())
        assertEquals("local", execute(old, call("local")))

        old.close()
        assertEquals(setOf(second.revision), router.retainedRevisions())
        assertEquals("stale_dynamic_tool_revision", errorCode(executeResult(old, call("local"))))
    }

    @Test
    fun identicalPublicationIsNoOpAndDoesNotRotateRevision() {
        val initial = snapshot(contributor("local", DynamicToolPlacement.BACKGROUND_ALLOWED))
        val equivalent = snapshot(contributor("local", DynamicToolPlacement.BACKGROUND_ALLOWED))
        val router = RevisionedDynamicToolRouter(initial)

        assertEquals(initial.revision, equivalent.revision)
        assertEquals(
            DynamicToolSnapshotPublication.Unchanged(initial.revision),
            router.publish(equivalent),
        )
        assertEquals(setOf(initial.revision), router.retainedRevisions())
    }

    @Test
    fun privateContributorIdentityRotatesSameCatalogAndRoutesToNewExecutor() {
        val executorA = FakeExecutor("remote", resultMarker = "executor-a")
        val executorB = FakeExecutor("remote", resultMarker = "executor-b")
        val first = snapshot(
            contributor("local", DynamicToolPlacement.BACKGROUND_ALLOWED),
            DynamicToolContributor(
                executor = executorA,
                revisionToken = "remote-worker:1:${"a".repeat(64)}",
            ),
        )
        val second = snapshot(
            contributor("local", DynamicToolPlacement.BACKGROUND_ALLOWED),
            DynamicToolContributor(
                executor = executorB,
                revisionToken = "remote-worker:2:${"b".repeat(64)}",
            ),
        )

        assertEquals(first.interactiveSpecs, second.interactiveSpecs)
        assertNotEquals(first.revision, second.revision)
        assertFalse(first.interactiveSpecs.toString().contains("remote-worker:"))

        val router = RevisionedDynamicToolRouter(first)
        val oldLease = router.acquire()
        assertEquals("executor-a", execute(oldLease, call("remote")))
        router.publish(second)
        val newLease = router.acquire()
        assertEquals("executor-b", execute(newLease, call("remote")))
        oldLease.close()
        newLease.close()
    }

    @Test
    fun contributorRevisionTokenIsBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            DynamicToolContributor(
                executor = FakeExecutor("remote"),
                revisionToken = "x".repeat(513),
            )
        }
    }

    @Test
    fun backgroundPolicyIsPartOfRevisionAndInteractiveOnlyNamespaceIsAbsentFromBackground() {
        val interactiveOnly = snapshot(
            contributor("local", DynamicToolPlacement.BACKGROUND_ALLOWED),
            contributor("remote", DynamicToolPlacement.INTERACTIVE_ONLY),
        )
        val backgroundEnabled = snapshot(
            contributor("local", DynamicToolPlacement.BACKGROUND_ALLOWED),
            contributor("remote", DynamicToolPlacement.BACKGROUND_ALLOWED),
        )
        assertNotEquals(interactiveOnly.revision, backgroundEnabled.revision)

        val lease = RevisionedDynamicToolRouter(interactiveOnly).acquire()
        val remote = executeResult(lease.backgroundExecutor(), call("remote"))
        assertFalse(remote.success)
        assertEquals("unknown_dynamic_tool_namespace", errorCode(remote))
        assertEquals("remote", execute(lease, call("remote")))
        lease.close()
    }

    @Test
    fun backgroundCatalogMayBeAnExactReadOnlySubsetAndItsPolicyChangesTheRevision() {
        val interactive = FakeExecutor("shared", listOf("read", "write"))
        val readOnly = FakeExecutor("shared", listOf("read"))
        val subset = snapshot(
            DynamicToolContributor(
                interactiveExecutor = interactive,
                backgroundExecutor = readOnly,
            ),
        )
        val fullyEnabled = snapshot(
            DynamicToolContributor(
                interactiveExecutor = interactive,
                backgroundExecutor = interactive,
            ),
        )

        assertNotEquals(subset.revision, fullyEnabled.revision)
        val lease = RevisionedDynamicToolRouter(subset).acquire()
        assertEquals(setOf("read", "write"), lease.specs.single().tools.map { it.name }.toSet())
        assertEquals(
            setOf("read"),
            lease.backgroundExecutor().specs.single().tools.map { it.name }.toSet(),
        )
        assertEquals(
            "unknown_dynamic_tool",
            errorCode(executeResult(lease.backgroundExecutor(), call("shared", "write"))),
        )
        lease.close()
    }

    @Test
    fun backgroundCatalogRejectsAnyToolThatIsNotAnExactInteractiveContractMember() {
        val interactive = FakeExecutor("shared", listOf("read"))
        val changedDescription = FakeExecutor(
            namespace = "shared",
            toolNames = listOf("read"),
            toolDescription = "Different contract",
        )
        val addedTool = FakeExecutor("shared", listOf("read", "write"))

        assertThrows(IllegalArgumentException::class.java) {
            snapshot(DynamicToolContributor(interactive, changedDescription))
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(DynamicToolContributor(interactive, addedTool))
        }
    }

    @Test
    fun snapshotCatalogIsDetachedFromMutableContributorLists() {
        val toolList = mutableListOf(tool("run"))
        val namespaceList = mutableListOf(
            DynamicToolNamespaceSpec("local", "Test namespace local", toolList),
        )
        val mutable = MutableCatalogExecutor(namespaceList)
        val frozen = snapshot(
            DynamicToolContributor(mutable, DynamicToolPlacement.BACKGROUND_ALLOWED),
        )

        toolList += tool("late")
        namespaceList.clear()

        assertEquals(listOf("local"), frozen.interactiveSpecs.map { it.name })
        assertEquals(listOf("run"), frozen.interactiveSpecs.single().tools.map { it.name })
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (frozen.interactiveSpecs as MutableList<DynamicToolNamespaceSpec>).clear()
        }
        assertSame(frozen.interactiveSpecs, frozen.interactive.specs)
    }

    @Test
    fun duplicateNamespaceAndEmptyBackgroundContractFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(
                contributor("same", DynamicToolPlacement.BACKGROUND_ALLOWED),
                contributor("same", DynamicToolPlacement.INTERACTIVE_ONLY),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(contributor("remote", DynamicToolPlacement.INTERACTIVE_ONLY))
        }
    }

    @Test
    fun leaseCloseIsIdempotentAndUnknownRevisionCannotBeAcquired() {
        val initial = snapshot(contributor("local", DynamicToolPlacement.BACKGROUND_ALLOWED))
        val router = RevisionedDynamicToolRouter(initial)
        val lease = router.acquire()
        lease.close()
        lease.close()
        assertThrows(IllegalArgumentException::class.java) {
            router.acquire("0".repeat(64))
        }
        assertTrue(router.retainedRevisions().contains(initial.revision))
    }

    @Test
    fun closeRetainsOldRevisionUntilAdmittedCallCompletesAndDeniesLateCalls() {
        val holding = HoldingExecutor("old")
        val first = snapshot(
            DynamicToolContributor(holding, DynamicToolPlacement.BACKGROUND_ALLOWED),
        )
        val second = snapshot(contributor("new", DynamicToolPlacement.BACKGROUND_ALLOWED))
        val router = RevisionedDynamicToolRouter(first)
        val lease = router.acquire()
        var admitted: DynamicToolExecutionResult? = null
        lease.execute(call("old")) { admitted = it }
        router.publish(second)

        lease.close()
        assertEquals(setOf(first.revision, second.revision), router.retainedRevisions())
        assertEquals(
            "stale_dynamic_tool_revision",
            errorCode(executeResult(lease, call("old"))),
        )
        holding.complete()
        assertTrue(admitted?.success == true)
        assertEquals(setOf(second.revision), router.retainedRevisions())
        assertThrows(IllegalArgumentException::class.java) { router.publish(first) }
    }

    @Test
    fun cancelledCallReleasesOldRevisionEvenWhenDelegateSuppressesItsCompletion() {
        val cancellable = CancellationSuppressingExecutor("old")
        val first = snapshot(
            DynamicToolContributor(cancellable, DynamicToolPlacement.BACKGROUND_ALLOWED),
        )
        val second = snapshot(contributor("new", DynamicToolPlacement.BACKGROUND_ALLOWED))
        val router = RevisionedDynamicToolRouter(first)
        val lease = router.acquire()
        val handle = lease.executeCancellable(
            call("old"),
            DynamicToolCancellation.NONE,
        ) { error("Cancellation-suppressing delegate must not complete") }
        router.publish(second)
        lease.close()

        assertEquals(setOf(first.revision, second.revision), router.retainedRevisions())
        assertEquals(
            DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED,
            handle.cancel(),
        )
        assertEquals(setOf(second.revision), router.retainedRevisions())
        assertEquals(
            DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED,
            handle.cancel(),
        )
    }

    @Test
    fun cancelledRevisionLeaseForwardsOnlyActualDelegateQuiescence() {
        val delegate = CancellationSuppressingExecutor("old")
        val router = RevisionedDynamicToolRouter(snapshot(
            DynamicToolContributor(delegate, DynamicToolPlacement.BACKGROUND_ALLOWED),
        ))
        val lease = router.acquire()
        val handle = lease.executeCancellable(call("old"), DynamicToolCancellation.NONE) {
            error("Cancelled calls must not emit model output")
        }
        var quiescent = false
        assertTrue(handle.onQuiescent { quiescent = true })
        handle.cancel()
        lease.close()
        assertFalse(quiescent)
        checkNotNull(delegate.quiescentListener).invoke()
        assertTrue(quiescent)
    }

    @Test
    fun contributorCapacityFailsBeforeCompositeConstruction() {
        val contributors = (0..256).map { index ->
            contributor(
                "namespace_$index",
                if (index == 0) {
                    DynamicToolPlacement.BACKGROUND_ALLOWED
                } else {
                    DynamicToolPlacement.INTERACTIVE_ONLY
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RevisionedDynamicToolSnapshot.create(contributors)
        }
    }

    private fun snapshot(vararg contributors: DynamicToolContributor) =
        RevisionedDynamicToolSnapshot.create(contributors.toList())

    private fun contributor(
        namespace: String,
        placement: DynamicToolPlacement,
    ) = DynamicToolContributor(FakeExecutor(namespace), placement)

    private fun call(
        namespace: String,
        tool: String = "run",
    ) = DynamicToolCallParams(
        threadId = "thread-1",
        turnId = "turn-1",
        callId = "call-1",
        namespace = namespace,
        tool = tool,
        argumentsJson = "{}",
    )

    private fun execute(executor: DynamicToolExecutor, call: DynamicToolCallParams): String {
        val result = executeResult(executor, call)
        assertTrue(result.success)
        return JSONObject(result.contentText).getString("namespace")
    }

    private fun executeResult(
        executor: DynamicToolExecutor,
        call: DynamicToolCallParams,
    ): DynamicToolExecutionResult {
        var result: DynamicToolExecutionResult? = null
        executor.execute(call) { result = it }
        return checkNotNull(result)
    }

    private fun errorCode(result: DynamicToolExecutionResult): String =
        JSONObject(result.contentText).getString("errorCode")

    private fun tool(
        name: String,
        description: String = "Run test tool",
    ) = DynamicToolFunctionSpec(
        name = name,
        description = description,
        inputSchemaJson = """{"type":"object","additionalProperties":false}""",
    )

    private class FakeExecutor(
        namespace: String,
        toolNames: List<String> = listOf("run"),
        toolDescription: String = "Run test tool",
        private val resultMarker: String = namespace,
    ) : DynamicToolExecutor {
        override val specs = listOf(
            DynamicToolNamespaceSpec(
                name = namespace,
                description = "Test namespace $namespace",
                tools = toolNames.map { toolName ->
                    DynamicToolFunctionSpec(
                        name = toolName,
                        description = toolDescription,
                        inputSchemaJson =
                            """{"type":"object","additionalProperties":false}""",
                    )
                },
            ),
        )

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) = completion(
            DynamicToolExecutionResult(
                JSONObject().put("status", "ok").put("namespace", resultMarker).toString(),
                success = true,
            ),
        )

        override fun failureResult(
            call: DynamicToolCallParams,
            code: String,
        ) = DynamicToolExecutionResult(
            JSONObject().put("status", "failed").put("errorCode", code).toString(),
            success = false,
        )
    }

    private class MutableCatalogExecutor(
        override val specs: MutableList<DynamicToolNamespaceSpec>,
    ) : DynamicToolExecutor {
        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) = completion(
            DynamicToolExecutionResult(
                JSONObject().put("status", "ok").put("namespace", call.namespace).toString(),
                success = true,
            ),
        )

        override fun failureResult(
            call: DynamicToolCallParams,
            code: String,
        ) = DynamicToolExecutionResult(
            JSONObject().put("status", "failed").put("errorCode", code).toString(),
            success = false,
        )
    }

    private class HoldingExecutor(namespace: String) : DynamicToolExecutor {
        private var completion: ((DynamicToolExecutionResult) -> Unit)? = null
        override val specs = FakeExecutor(namespace).specs

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) {
            check(this.completion == null)
            this.completion = completion
        }

        override fun failureResult(
            call: DynamicToolCallParams,
            code: String,
        ) = FakeExecutor(requireNotNull(call.namespace)).failureResult(call, code)

        fun complete() {
            requireNotNull(completion).invoke(
                DynamicToolExecutionResult(
                    JSONObject().put("status", "ok").put("namespace", "old").toString(),
                    success = true,
                ),
            )
        }
    }

    private class CancellationSuppressingExecutor(namespace: String) : DynamicToolExecutor {
        override val specs = FakeExecutor(namespace).specs
        var quiescentListener: (() -> Unit)? = null

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) = error("Use cancellable execution")

        override fun executeCancellable(
            call: DynamicToolCallParams,
            cancellation: DynamicToolCancellation,
            completion: (DynamicToolExecutionResult) -> Unit,
        ): DynamicToolExecutionHandle = object : DynamicToolExecutionHandle {
            override fun onQuiescent(listener: () -> Unit): Boolean {
                quiescentListener = listener
                return true
            }

            override fun cancel() =
                DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED
        }

        override fun failureResult(
            call: DynamicToolCallParams,
            code: String,
        ) = FakeExecutor(requireNotNull(call.namespace)).failureResult(call, code)
    }
}
