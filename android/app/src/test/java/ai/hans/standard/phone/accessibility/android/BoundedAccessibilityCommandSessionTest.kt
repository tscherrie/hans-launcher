package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.AccessibilityCommand
import ai.hans.standard.phone.accessibility.AccessibilityCommandExecutor
import ai.hans.standard.phone.accessibility.AccessibilityConfirmationRisk
import ai.hans.standard.phone.accessibility.AccessibilityExecutionResult
import ai.hans.standard.phone.accessibility.AccessibilityExecutionStatus
import ai.hans.standard.phone.accessibility.AccessibilityIdempotencyKey
import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilityUserApproval
import ai.hans.standard.phone.accessibility.DictationLifecycleStamp
import ai.hans.standard.phone.accessibility.ExactAccessibilityUserConfirmationGate
import ai.hans.standard.phone.accessibility.InMemorySemanticUiSnapshotSource
import ai.hans.standard.phone.accessibility.SemanticUiAction
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiInteractionAvailability
import ai.hans.standard.phone.accessibility.UiInteractionAvailabilityProbe
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedAccessibilityCommandSessionTest {
    @Test
    fun commandsRunSeriallyOffCallerAndQueueRejectsOverflow() {
        val harness = SessionHarness(queueCapacity = 1)
        try {
            val enteredFirst = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val firstCall = AtomicBoolean(true)
            harness.host.onNodeAction = {
                if (firstCall.compareAndSet(true, false)) {
                    enteredFirst.countDown()
                    releaseFirst.await(3, TimeUnit.SECONDS)
                }
            }
            val callbacks = Collections.synchronizedList(mutableListOf<String>())
            val callbackThreads = Collections.synchronizedList(mutableListOf<String>())
            val completed = CountDownLatch(2)
            val callerThread = Thread.currentThread().name

            assertTrue(harness.session.submit(harness.click("session-first")) { result ->
                callbacks += result.idempotencyKey.value
                callbackThreads += Thread.currentThread().name
                completed.countDown()
            })
            assertTrue(enteredFirst.await(2, TimeUnit.SECONDS))
            assertTrue(harness.session.submit(harness.click("session-second")) { result ->
                callbacks += result.idempotencyKey.value
                callbackThreads += Thread.currentThread().name
                completed.countDown()
            })
            assertFalse(harness.session.submit(harness.click("session-overflow")) {})

            releaseFirst.countDown()
            assertTrue(completed.await(4, TimeUnit.SECONDS))
            assertEquals(listOf("session-first", "session-second"), callbacks)
            assertTrue(callbackThreads.all { it == "hans-accessibility-commands" })
            assertTrue(callbackThreads.none { it == callerThread })
            assertEquals(2, harness.host.nodeCalls.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun callbackFailureDoesNotKillTheSingleCommandWorker() {
        val harness = SessionHarness(queueCapacity = 2)
        try {
            val secondCompleted = CountDownLatch(1)
            var secondResult: AccessibilityExecutionResult? = null

            assertTrue(harness.session.submit(harness.click("callback-throws")) {
                error("synthetic callback failure")
            })
            assertTrue(harness.session.submit(harness.click("callback-survives")) { result ->
                secondResult = result
                secondCompleted.countDown()
            })

            assertTrue(secondCompleted.await(3, TimeUnit.SECONDS))
            assertEquals(AccessibilityExecutionStatus.SUCCEEDED, secondResult?.status)
            assertEquals(2, harness.host.nodeCalls.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun commandQueuedWhileUnlockedDoesNotReachAndroidAfterDeviceLocks() {
        var availability = UiInteractionAvailability.AVAILABLE
        val harness = SessionHarness(
            queueCapacity = 2,
            uiAvailability = UiInteractionAvailabilityProbe { availability },
        )
        try {
            val enteredFirst = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val firstCall = AtomicBoolean(true)
            harness.host.onNodeAction = {
                if (firstCall.compareAndSet(true, false)) {
                    enteredFirst.countDown()
                    releaseFirst.await(3, TimeUnit.SECONDS)
                }
            }
            val completed = CountDownLatch(2)
            var firstResult: AccessibilityExecutionResult? = null
            var queuedResult: AccessibilityExecutionResult? = null

            assertTrue(harness.session.submit(harness.click("queue-before-lock-first")) {
                firstResult = it
                completed.countDown()
            })
            assertTrue(enteredFirst.await(2, TimeUnit.SECONDS))
            assertTrue(harness.session.submit(harness.click("queue-before-lock-second")) {
                queuedResult = it
                completed.countDown()
            })

            availability = UiInteractionAvailability.DEVICE_LOCKED
            releaseFirst.countDown()

            assertTrue(completed.await(4, TimeUnit.SECONDS))
            assertEquals(AccessibilityExecutionStatus.SUCCEEDED, firstResult?.status)
            assertEquals(AccessibilityExecutionStatus.REJECTED, queuedResult?.status)
            assertEquals("device_unlock_required", queuedResult?.errorCode)
            assertEquals("user_action_required", queuedResult?.postcondition?.detailCode)
            assertEquals(1, harness.host.nodeCalls.size)
        } finally {
            harness.close()
        }
    }

    @Test
    fun approvedRetryRunsInlineBeforeACommandQueuedDuringConfirmation() {
        val harness = SessionHarness(queueCapacity = 2)
        try {
            val awaitingDecision = CountDownLatch(1)
            val decisionReady = CountDownLatch(1)
            val completed = CountDownLatch(2)
            val order = Collections.synchronizedList(mutableListOf<String>())
            val sensitive = harness.click("sensitive-first").copy(
                confirmationRisk = AccessibilityConfirmationRisk.EXTERNAL_COMMUNICATION,
            )

            assertTrue(harness.session.submit(sensitive) { pending ->
                val request = checkNotNull(pending.requiredConfirmation)
                awaitingDecision.countDown()
                decisionReady.await(3, TimeUnit.SECONDS)
                val approval = AccessibilityUserApproval(
                    approvalId = "ui:inline-retry-test",
                    idempotencyKey = request.idempotencyKey,
                    commandFingerprint = request.commandFingerprint,
                    risk = request.risk,
                    correlation = request.correlation,
                )
                assertTrue(harness.session.submitApprovedRetry(sensitive, approval) { approved ->
                    assertEquals(AccessibilityExecutionStatus.SUCCEEDED, approved.status)
                    order += "approved-a"
                    completed.countDown()
                })
            })
            assertTrue(awaitingDecision.await(2, TimeUnit.SECONDS))
            assertTrue(harness.session.submit(harness.click("queued-second")) { second ->
                assertEquals(AccessibilityExecutionStatus.SUCCEEDED, second.status)
                order += "queued-b"
                completed.countDown()
            })
            decisionReady.countDown()

            assertTrue(completed.await(4, TimeUnit.SECONDS))
            assertEquals(listOf("approved-a", "queued-b"), order)
        } finally {
            harness.close()
        }
    }

    @Test
    fun closeIsIdempotentAndRejectsEveryLaterSubmission() {
        val harness = SessionHarness(queueCapacity = 1)
        harness.session.close()
        harness.session.close()

        assertFalse(harness.session.submit(harness.click("closed-session")) {})
        harness.closeRegistration()
    }

    @Test
    fun currentSnapshotIsReadOnlyProjectionOfSource() {
        val harness = SessionHarness(queueCapacity = 1)
        try {
            assertSame(harness.before, harness.session.currentSnapshot())
            harness.source.clear()
            assertEquals(null, harness.session.currentSnapshot())
        } finally {
            harness.close()
        }
    }

    @Test
    fun postActionReceiptHooksFailClosedByDefaultWithoutUsingCurrentSnapshot() {
        val harness = SessionHarness(queueCapacity = 1)
        try {
            assertNull(harness.session.receiptSnapshotForObservation(harness.before.correlation))
            assertFalse(harness.session.retainReceiptSnapshotForCommands(harness.before.correlation))
            assertSame(harness.before, harness.session.currentSnapshot())
        } finally {
            harness.close()
        }
    }

    @Test
    fun exactReceiptHooksRejectCrossSessionWrongCorrelationAndClosedSession() {
        val after = semanticSnapshot(correlation = testCorrelation(snapshot = 2))
        var returned = after
        var reads = 0
        var promotions = 0
        val harness = SessionHarness(
            queueCapacity = 1,
            receiptSnapshot = { reads += 1; returned },
            retainReceiptForCommands = { promotions += 1; true },
        )
        try {
            assertSame(after, harness.session.receiptSnapshotForObservation(after.correlation))
            assertTrue(harness.session.retainReceiptSnapshotForCommands(after.correlation))
            val foreign = testCorrelation(snapshot = 2, session = "other-session")
            assertNull(harness.session.receiptSnapshotForObservation(foreign))
            assertFalse(harness.session.retainReceiptSnapshotForCommands(foreign))
            assertEquals(1, reads)
            assertEquals(1, promotions)
            returned = semanticSnapshot(correlation = testCorrelation(snapshot = 3))
            assertNull(harness.session.receiptSnapshotForObservation(after.correlation))
            harness.session.close()
            assertNull(harness.session.receiptSnapshotForObservation(after.correlation))
            assertFalse(harness.session.retainReceiptSnapshotForCommands(after.correlation))
            assertEquals(2, reads)
            assertEquals(1, promotions)
        } finally {
            harness.close()
        }
    }

    @Test
    fun receiptHookFailuresCannotEscapeOrCauseAnyPlatformAction() {
        val harness = SessionHarness(
            queueCapacity = 1,
            receiptSnapshot = { error("synthetic receipt failure") },
            retainReceiptForCommands = { error("synthetic retention failure") },
        )
        try {
            assertNull(harness.session.receiptSnapshotForObservation(harness.before.correlation))
            assertFalse(harness.session.retainReceiptSnapshotForCommands(harness.before.correlation))
            assertTrue(harness.host.nodeCalls.isEmpty())
        } finally {
            harness.close()
        }
    }

    @Test
    fun processLocalRegistryOnlyRemovesTheExactPublishedSession() {
        val first = FakeSession(AccessibilitySessionId("registry-session-0001"))
        val second = FakeSession(AccessibilitySessionId("registry-session-0002"))
        val owner = Any()
        HansAccessibilitySessions.connect(owner)
        try {
            assertTrue(HansAccessibilitySessions.publish(owner, first))
            assertTrue(HansAccessibilitySessions.publish(owner, second))
            HansAccessibilitySessions.remove(owner, first)
            assertSame(second, HansAccessibilitySessions.current())

            HansAccessibilitySessions.remove(owner, second)
            assertEquals(null, HansAccessibilitySessions.current())
        } finally {
            HansAccessibilitySessions.disconnect(owner)
        }
    }

    @Test
    fun registryRepublishesLostSessionButObsoleteOwnerCannotReplaceNewConnection() {
        val oldOwner = Any()
        val currentOwner = Any()
        val old = FakeSession(AccessibilitySessionId("registry-session-old1"))
        val current = FakeSession(AccessibilitySessionId("registry-session-new1"))

        HansAccessibilitySessions.connect(oldOwner)
        assertTrue(HansAccessibilitySessions.publish(oldOwner, old))
        HansAccessibilitySessions.remove(oldOwner, old)
        assertTrue(HansAccessibilitySessions.isServiceConnected())
        assertTrue(HansAccessibilitySessions.ensurePublished(oldOwner, old))
        assertSame(old, HansAccessibilitySessions.current())

        HansAccessibilitySessions.connect(currentOwner)
        try {
            assertTrue(HansAccessibilitySessions.publish(currentOwner, current))
            assertFalse(HansAccessibilitySessions.ensurePublished(oldOwner, old))
            HansAccessibilitySessions.disconnect(oldOwner)
            assertTrue(HansAccessibilitySessions.isServiceConnected())
            assertSame(current, HansAccessibilitySessions.current())
        } finally {
            HansAccessibilitySessions.disconnect(currentOwner)
        }
        assertFalse(HansAccessibilitySessions.isServiceConnected())
        assertEquals(null, HansAccessibilitySessions.current())
    }

    @Test
    fun closingDuringPromotionWithdrawsTheExactUndeliveredFrameEvenAfterClose() {
        val withdrawn = mutableListOf<UiSnapshotCorrelation>()
        lateinit var harness: SessionHarness
        harness = SessionHarness(
            queueCapacity = 1,
            retainReceiptForCommands = { harness.session.close(); true },
            withdrawReceiptForCommands = { withdrawn += it },
        )
        try {
            val correlation = harness.before.correlation
            assertFalse(harness.session.retainReceiptSnapshotForCommands(correlation))
            assertEquals(listOf(correlation), withdrawn)
            harness.session.withdrawReceiptSnapshotForCommands(correlation)
            assertEquals(listOf(correlation, correlation), withdrawn)
            assertTrue(harness.host.nodeCalls.isEmpty())
        } finally {
            harness.close()
        }
    }

    private class SessionHarness(
        queueCapacity: Int,
        uiAvailability: UiInteractionAvailabilityProbe = UiInteractionAvailabilityProbe {
            UiInteractionAvailability.AVAILABLE
        },
        receiptSnapshot: (UiSnapshotCorrelation) -> SemanticUiSnapshot? = { null },
        retainReceiptForCommands: (UiSnapshotCorrelation) -> Boolean = { false },
        withdrawReceiptForCommands: (UiSnapshotCorrelation) -> Unit = {},
    ) {
        val before = semanticSnapshot(
            roots = listOf(
                rawNode(
                    bounds = TEST_DISPLAY_BOUNDS,
                    role = SemanticUiRole.UNKNOWN,
                    children = listOf(
                        rawNode(
                            text = "button",
                            className = "android.widget.Button",
                            role = SemanticUiRole.BUTTON,
                            bounds = UiBounds(10, 10, 200, 100),
                            clickable = true,
                            actions = setOf(SemanticUiAction.CLICK),
                        ),
                    ),
                ),
            ),
        )
        private val after = semanticSnapshot(
            correlation = testCorrelation(snapshot = 2),
            roots = listOf(
                rawNode(
                    bounds = TEST_DISPLAY_BOUNDS,
                    role = SemanticUiRole.UNKNOWN,
                    children = listOf(
                        rawNode(
                            text = "button",
                            className = "android.widget.Button",
                            role = SemanticUiRole.BUTTON,
                            bounds = UiBounds(10, 10, 200, 100),
                            clickable = true,
                            actions = setOf(SemanticUiAction.CLICK),
                        ),
                    ),
                ),
            ),
        )
        val source = InMemorySemanticUiSnapshotSource().apply { publish(before) }
        val host = FakeRootFreeAccessibilityHost().apply {
            snapshotAfter = after
            val node = before.nodes[1]
            nodeLocators[1] = AndroidNodeLocator(
                nodeOrdinal = 1,
                displayId = 0,
                windowId = before.correlation.windowId.value,
                uniqueId = "session-button-id",
                viewIdResourceName = null,
                structuralPath = listOf(0),
                fingerprint = AndroidNodeFingerprint(
                    packageName = node.packageName?.value,
                    className = node.className?.value,
                    role = node.role,
                    bounds = node.bounds,
                    visible = node.visible,
                    enabled = node.enabled,
                    clickable = node.clickable,
                    editable = node.editable,
                    scrollable = node.scrollable,
                    actions = node.actions,
                ),
            )
        }
        private val registration = AndroidDictationLifecycleRegistry.register {
            DictationLifecycleStamp(7, true)
        }
        private val executor = AccessibilityCommandExecutor(
            snapshots = source,
            adapter = RootFreeAndroidAccessibilityAdapter(source, host),
            confirmationGate = ExactAccessibilityUserConfirmationGate,
            dictationGuard = AndroidDictationLifecycleRegistry,
            uiAvailability = uiAvailability,
        )
        val session = BoundedAccessibilityCommandSession(
            sessionId = before.correlation.sessionId,
            snapshot = source::current,
            executor = executor,
            queueCapacity = queueCapacity,
            receiptSnapshot = receiptSnapshot,
            retainReceiptForCommands = retainReceiptForCommands,
            withdrawReceiptForCommands = withdrawReceiptForCommands,
        )

        fun click(key: String) = AccessibilityCommand.Click(
            idempotencyKey = AccessibilityIdempotencyKey(key),
            handle = before.nodes[1].handle,
        )

        fun close() {
            session.close()
            closeRegistration()
        }

        fun closeRegistration() = registration.close()
    }

    private class FakeSession(
        override val sessionId: AccessibilitySessionId,
    ) : HansAccessibilitySession {
        override fun currentSnapshot(): SemanticUiSnapshot? = null

        override fun submit(
            command: AccessibilityCommand,
            approval: ai.hans.standard.phone.accessibility.AccessibilityUserApproval?,
            callback: AccessibilityCommandCallback,
        ): Boolean = false
    }
}
