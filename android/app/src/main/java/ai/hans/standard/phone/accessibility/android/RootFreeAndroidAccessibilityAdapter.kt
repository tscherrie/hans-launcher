package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.AccessibilityGlobalAction
import ai.hans.standard.phone.accessibility.AccessibilityPostcondition
import ai.hans.standard.phone.accessibility.AccessibilityPostconditionKind
import ai.hans.standard.phone.accessibility.AccessibilityPostconditionStatus
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AndroidAccessibilityAdapterResult
import ai.hans.standard.phone.accessibility.AndroidSemanticAccessibilityAdapter
import ai.hans.standard.phone.accessibility.CurrentSemanticUiSnapshotSource
import ai.hans.standard.phone.accessibility.SemanticNodeHandle
import ai.hans.standard.phone.accessibility.SemanticUiNode
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.UiCoordinateGesture
import ai.hans.standard.phone.accessibility.UiDataTrust
import ai.hans.standard.phone.accessibility.UiInteractionAvailability
import ai.hans.standard.phone.accessibility.UiPostconditionExpectation
import ai.hans.standard.phone.accessibility.UiScrollDirection
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import java.util.ArrayDeque

internal enum class AndroidHostActionStatus {
    ACCEPTED,
    STALE_TARGET,
    AMBIGUOUS_TARGET,
    UNSUPPORTED,
    BLOCKED,
    UI_DEVICE_LOCKED,
    UI_SCREEN_NOT_INTERACTIVE,
    UI_DEVICE_LOCKED_AND_SCREEN_NOT_INTERACTIVE,
    UI_STATE_UNAVAILABLE,
    CANCELLED,
    TIMED_OUT,
    FAILED,
}

internal data class AndroidHostNodeActionResult(
    val status: AndroidHostActionStatus,
    val locator: AndroidNodeLocator? = null,
)

internal sealed interface AndroidTargetResolution {
    data class Found(
        val node: SemanticUiNode,
        val locator: AndroidNodeLocator,
    ) : AndroidTargetResolution

    data object Missing : AndroidTargetResolution

    data object Ambiguous : AndroidTargetResolution

    data object StaleSnapshot : AndroidTargetResolution
}

internal interface RootFreeAccessibilityHost {
    fun performNodeAction(
        handle: SemanticNodeHandle,
        action: AndroidNodeAction,
    ): AndroidHostNodeActionResult

    fun performGlobalAction(action: AccessibilityGlobalAction): AndroidHostActionStatus

    fun supportsCoordinateGestures(): Boolean

    /** Returns only after GestureResultCallback reports completion/cancellation or timeout. */
    fun performCoordinateGesture(
        correlation: UiSnapshotCorrelation,
        gesture: UiCoordinateGesture,
    ): AndroidHostActionStatus

    /** Snapshot-id baseline sampled only after Android accepted an action. */
    fun actionObservationBaseline(): AccessibilitySnapshotId?

    /** Waits for a frame newer than [baseline], then performs one explicit refresh. */
    fun snapshotAfterAction(
        before: UiSnapshotCorrelation,
        baseline: AccessibilitySnapshotId,
    ): SemanticUiSnapshot?

    /** Resolves an app-private locator against the already-published fresh snapshot. */
    fun resolveTargetAfterAction(
        beforeLocator: AndroidNodeLocator,
        after: UiSnapshotCorrelation,
    ): AndroidTargetResolution
}

/**
 * Domain adapter for the real Android service. Confirmation is deliberately
 * absent here: AccessibilityCommandExecutor must approve sensitive commands
 * before this adapter is ever invoked.
 */
internal class RootFreeAndroidAccessibilityAdapter(
    private val snapshots: CurrentSemanticUiSnapshotSource,
    private val host: RootFreeAccessibilityHost,
) : AndroidSemanticAccessibilityAdapter {
    override fun click(
        handle: SemanticNodeHandle,
        expectedPostcondition: UiPostconditionExpectation,
    ): AndroidAccessibilityAdapterResult = executeNodeAction(
        handle = handle,
        action = AndroidNodeAction.Click,
        expectedPostcondition = expectedPostcondition,
        requestedText = null,
    )

    override fun setText(
        handle: SemanticNodeHandle,
        value: String,
        expectedPostcondition: UiPostconditionExpectation,
    ): AndroidAccessibilityAdapterResult = executeNodeAction(
        handle = handle,
        action = AndroidNodeAction.SetText(value),
        expectedPostcondition = expectedPostcondition,
        requestedText = value,
    )

    override fun scroll(
        handle: SemanticNodeHandle,
        direction: UiScrollDirection,
        expectedPostcondition: UiPostconditionExpectation,
    ): AndroidAccessibilityAdapterResult = executeNodeAction(
        handle = handle,
        action = AndroidNodeAction.Scroll(direction),
        expectedPostcondition = expectedPostcondition,
        requestedText = null,
    )

    override fun globalAction(
        action: AccessibilityGlobalAction,
        expectedPostcondition: UiPostconditionExpectation,
    ): AndroidAccessibilityAdapterResult {
        val before = snapshots.current() ?: return AndroidAccessibilityAdapterResult.StaleTarget
        return when (val status = host.performGlobalAction(action)) {
            AndroidHostActionStatus.ACCEPTED -> {
                val baseline = host.actionObservationBaseline()
                    ?: return AndroidAccessibilityAdapterResult.Failure(
                        "postcondition_baseline_unavailable",
                    )
                receipt(
                    kind = AccessibilityPostconditionKind.GLOBAL_ACTION,
                    expected = expectedPostcondition,
                    before = before,
                    after = host.snapshotAfterAction(before.correlation, baseline),
                    baseline = baseline,
                    targetBefore = null,
                    targetLocator = null,
                    requestedText = null,
                )
            }
            else -> status.toAdapterFailure("android_global_action")
        }
    }

    override fun supportsCoordinateGestures(): Boolean =
        runCatching { host.supportsCoordinateGestures() }.getOrDefault(false)

    override fun coordinateGesture(
        correlation: UiSnapshotCorrelation,
        gesture: UiCoordinateGesture,
        expectedPostcondition: UiPostconditionExpectation,
    ): AndroidAccessibilityAdapterResult {
        val before = snapshots.current()
            ?: return AndroidAccessibilityAdapterResult.StaleTarget
        if (before.correlation != correlation) return AndroidAccessibilityAdapterResult.StaleTarget
        return when (val status = host.performCoordinateGesture(correlation, gesture)) {
            AndroidHostActionStatus.ACCEPTED -> {
                val baseline = host.actionObservationBaseline()
                    ?: return AndroidAccessibilityAdapterResult.Failure(
                        "postcondition_baseline_unavailable",
                    )
                receipt(
                    kind = AccessibilityPostconditionKind.COORDINATE_GESTURE,
                    expected = expectedPostcondition,
                    before = before,
                    after = host.snapshotAfterAction(before.correlation, baseline),
                    baseline = baseline,
                    targetBefore = null,
                    targetLocator = null,
                    requestedText = null,
                )
            }
            else -> status.toAdapterFailure("android_gesture")
        }
    }

    private fun executeNodeAction(
        handle: SemanticNodeHandle,
        action: AndroidNodeAction,
        expectedPostcondition: UiPostconditionExpectation,
        requestedText: String?,
    ): AndroidAccessibilityAdapterResult {
        val before = snapshots.snapshotForCorrelation(handle.correlation)
            ?: return AndroidAccessibilityAdapterResult.StaleTarget
        if (before.correlation != handle.correlation) {
            return AndroidAccessibilityAdapterResult.StaleTarget
        }
        val target = before.resolve(handle)
            ?: return AndroidAccessibilityAdapterResult.StaleTarget
        val actionResult = host.performNodeAction(handle, action)
        return when (actionResult.status) {
            AndroidHostActionStatus.ACCEPTED -> {
                val locator = actionResult.locator
                    ?: return AndroidAccessibilityAdapterResult.Failure(
                        "android_locator_receipt_missing",
                    )
                val baseline = host.actionObservationBaseline()
                    ?: return AndroidAccessibilityAdapterResult.Failure(
                        "postcondition_baseline_unavailable",
                    )
                receipt(
                    kind = AccessibilityPostconditionKind.NODE_ACTION,
                    expected = expectedPostcondition,
                    before = before,
                    after = host.snapshotAfterAction(before.correlation, baseline),
                    baseline = baseline,
                    targetBefore = target,
                    targetLocator = locator,
                    requestedText = requestedText,
                )
            }
            else -> actionResult.status.toAdapterFailure("android_node_action")
        }
    }

    private fun receipt(
        kind: AccessibilityPostconditionKind,
        expected: UiPostconditionExpectation,
        before: SemanticUiSnapshot,
        after: SemanticUiSnapshot?,
        baseline: AccessibilitySnapshotId,
        targetBefore: SemanticUiNode?,
        targetLocator: AndroidNodeLocator?,
        requestedText: String?,
    ): AndroidAccessibilityAdapterResult {
        if (
            after == null ||
            after.correlation.sessionId != before.correlation.sessionId ||
            after.correlation.snapshotId.value <= baseline.value
        ) {
            return AndroidAccessibilityAdapterResult.Failure("postcondition_snapshot_unavailable")
        }
        val targetAfter = if (targetLocator == null) {
            null
        } else {
            host.resolveTargetAfterAction(targetLocator, after.correlation)
        }
        val verification = AndroidAccessibilityPostconditions.verify(
            expectation = expected,
            before = before,
            after = after,
            targetBefore = targetBefore,
            targetLocator = targetLocator,
            targetAfter = targetAfter,
            requestedText = requestedText,
        )
        if (verification.status == AccessibilityPostconditionStatus.FAILED) {
            return AndroidAccessibilityAdapterResult.Failure("postcondition_not_verified")
        }
        return AndroidAccessibilityAdapterResult.Success(
            postcondition = AccessibilityPostcondition(
                kind = kind,
                status = verification.status,
                detailCode = verification.detailCode,
                before = before.correlation,
                after = after.correlation,
                trust = UiDataTrust.LOCAL_SYSTEM,
            ),
            actionCode = verification.actionCode,
            resultingCorrelation = after.correlation,
            trust = UiDataTrust.LOCAL_SYSTEM,
        )
    }

    private fun AndroidHostActionStatus.toAdapterFailure(prefix: String): AndroidAccessibilityAdapterResult =
        when (this) {
            AndroidHostActionStatus.STALE_TARGET,
            AndroidHostActionStatus.AMBIGUOUS_TARGET,
            -> AndroidAccessibilityAdapterResult.StaleTarget
            AndroidHostActionStatus.UNSUPPORTED -> AndroidAccessibilityAdapterResult.Unsupported
            AndroidHostActionStatus.BLOCKED ->
                AndroidAccessibilityAdapterResult.Failure("${prefix}_blocked")
            AndroidHostActionStatus.UI_DEVICE_LOCKED ->
                AndroidAccessibilityAdapterResult.Failure("device_unlock_required")
            AndroidHostActionStatus.UI_SCREEN_NOT_INTERACTIVE ->
                AndroidAccessibilityAdapterResult.Failure("screen_wake_required")
            AndroidHostActionStatus.UI_DEVICE_LOCKED_AND_SCREEN_NOT_INTERACTIVE ->
                AndroidAccessibilityAdapterResult.Failure("device_wake_and_unlock_required")
            AndroidHostActionStatus.UI_STATE_UNAVAILABLE ->
                AndroidAccessibilityAdapterResult.Failure(
                    "device_ui_state_verification_required",
                )
            AndroidHostActionStatus.CANCELLED ->
                AndroidAccessibilityAdapterResult.Failure("${prefix}_cancelled")
            AndroidHostActionStatus.TIMED_OUT ->
                AndroidAccessibilityAdapterResult.Failure("${prefix}_timed_out")
            AndroidHostActionStatus.FAILED ->
                AndroidAccessibilityAdapterResult.Failure("${prefix}_failed")
            AndroidHostActionStatus.ACCEPTED ->
                AndroidAccessibilityAdapterResult.Failure("${prefix}_receipt_invalid")
        }
}

internal fun UiInteractionAvailability.toAndroidHostActionStatus(): AndroidHostActionStatus =
    when (this) {
        UiInteractionAvailability.AVAILABLE -> AndroidHostActionStatus.FAILED
        UiInteractionAvailability.DEVICE_LOCKED -> AndroidHostActionStatus.UI_DEVICE_LOCKED
        UiInteractionAvailability.SCREEN_NOT_INTERACTIVE ->
            AndroidHostActionStatus.UI_SCREEN_NOT_INTERACTIVE
        UiInteractionAvailability.DEVICE_LOCKED_AND_SCREEN_NOT_INTERACTIVE ->
            AndroidHostActionStatus.UI_DEVICE_LOCKED_AND_SCREEN_NOT_INTERACTIVE
        UiInteractionAvailability.STATE_UNAVAILABLE ->
            AndroidHostActionStatus.UI_STATE_UNAVAILABLE
    }

internal data class AndroidPostconditionVerification(
    val status: AccessibilityPostconditionStatus,
    val detailCode: String,
    val actionCode: String,
)

internal object AndroidAccessibilityPostconditions {
    fun verify(
        expectation: UiPostconditionExpectation,
        before: SemanticUiSnapshot,
        after: SemanticUiSnapshot,
        targetBefore: SemanticUiNode?,
        targetLocator: AndroidNodeLocator?,
        targetAfter: AndroidTargetResolution?,
        requestedText: String?,
    ): AndroidPostconditionVerification = when (expectation) {
        UiPostconditionExpectation.ACTION_ACCEPTED -> observed("android_action_accepted_only")
        UiPostconditionExpectation.NODE_STATE_CHANGED -> when {
            targetBefore == null || targetLocator == null -> failed("node_target_missing")
            targetAfter is AndroidTargetResolution.Found &&
                !SemanticSnapshotContent.sameSubtree(
                    before,
                    targetBefore,
                    after,
                    targetAfter.node,
                ) ->
                verifiedForLocator(targetLocator, "target_node_state_changed")
            targetAfter is AndroidTargetResolution.Ambiguous -> failed("target_ambiguous")
            targetAfter is AndroidTargetResolution.StaleSnapshot -> failed("target_snapshot_stale")
            targetAfter is AndroidTargetResolution.Missing -> failed("target_missing")
            else -> failed("target_node_state_unchanged")
        }
        UiPostconditionExpectation.TARGET_DISAPPEARED -> when (targetAfter) {
            AndroidTargetResolution.Missing -> if (targetLocator == null) {
                failed("target_locator_missing")
            } else {
                verifiedForLocator(targetLocator, "target_disappeared")
            }
            is AndroidTargetResolution.Found -> failed("target_still_present")
            AndroidTargetResolution.Ambiguous -> failed("target_ambiguous")
            AndroidTargetResolution.StaleSnapshot -> failed("target_snapshot_stale")
            null -> failed("target_locator_missing")
        }
        UiPostconditionExpectation.WINDOW_CHANGED -> {
            if (windowChanged(before, after)) {
                verified("window_changed")
            } else {
                failed("window_unchanged")
            }
        }
        UiPostconditionExpectation.TEXT_EQUALS_REQUEST -> when {
            targetBefore == null || targetLocator == null || requestedText == null ->
                failed("text_target_missing")
            targetBefore.role == SemanticUiRole.PASSWORD_FIELD ->
                observed("password_text_not_observable")
            targetAfter is AndroidTargetResolution.Found &&
                targetAfter.node.text?.value == requestedText ->
                verifiedForLocator(targetLocator, "text_value_matches")
            targetAfter is AndroidTargetResolution.Ambiguous -> failed("target_ambiguous")
            targetAfter is AndroidTargetResolution.StaleSnapshot -> failed("target_snapshot_stale")
            targetAfter is AndroidTargetResolution.Missing -> failed("target_missing")
            else -> failed("text_value_mismatch")
        }
    }

    private fun windowChanged(
        before: SemanticUiSnapshot,
        after: SemanticUiSnapshot,
    ): Boolean {
        if (before.correlation.windowId != after.correlation.windowId) return true
        val beforeRoot = before.roots.firstOrNull()?.let(before::resolve)
        val afterRoot = after.roots.firstOrNull()?.let(after::resolve)
        return beforeRoot?.packageName != afterRoot?.packageName ||
            beforeRoot?.className != afterRoot?.className
    }

    private fun verifiedForLocator(
        locator: AndroidNodeLocator,
        detail: String,
    ): AndroidPostconditionVerification = if (
        locator.strength == AndroidLocatorStrength.STRUCTURAL_PATH
    ) {
        observed("${detail}_weak_locator")
    } else {
        verified(detail)
    }

    private fun verified(detail: String): AndroidPostconditionVerification =
        AndroidPostconditionVerification(
            status = AccessibilityPostconditionStatus.VERIFIED,
            detailCode = detail,
            actionCode = "android_action_verified",
        )

    private fun observed(detail: String): AndroidPostconditionVerification =
        AndroidPostconditionVerification(
            status = AccessibilityPostconditionStatus.OBSERVED_NOT_VERIFIED,
            detailCode = detail,
            actionCode = "android_action_observed",
        )

    private fun failed(detail: String): AndroidPostconditionVerification =
        AndroidPostconditionVerification(
            status = AccessibilityPostconditionStatus.FAILED,
            detailCode = detail,
            actionCode = "android_action_unverified",
        )
}

internal object SemanticSnapshotContent {
    fun same(first: SemanticUiSnapshot, second: SemanticUiSnapshot): Boolean {
        if (first.displayBounds != second.displayBounds) return false
        if (first.nodes.size != second.nodes.size) return false
        if (first.roots.map { it.nodeOrdinal } != second.roots.map { it.nodeOrdinal }) return false
        return first.nodes.zip(second.nodes).all { (left, right) -> sameNode(left, right) }
    }

    fun sameNode(first: SemanticUiNode, second: SemanticUiNode): Boolean =
        first.handle.nodeOrdinal == second.handle.nodeOrdinal &&
            sameNodeState(first, second) &&
            first.children.map { it.nodeOrdinal } == second.children.map { it.nodeOrdinal }

    fun sameNodeState(first: SemanticUiNode, second: SemanticUiNode): Boolean =
        first.packageName == second.packageName &&
            first.className == second.className &&
            first.text == second.text &&
            first.contentDescription == second.contentDescription &&
            first.role == second.role &&
            first.bounds == second.bounds &&
            first.visible == second.visible &&
            first.enabled == second.enabled &&
            first.clickable == second.clickable &&
            first.editable == second.editable &&
            first.scrollable == second.scrollable &&
            first.actions == second.actions

    fun sameSubtree(
        firstSnapshot: SemanticUiSnapshot,
        firstRoot: SemanticUiNode,
        secondSnapshot: SemanticUiSnapshot,
        secondRoot: SemanticUiNode,
    ): Boolean {
        val pending = ArrayDeque<Pair<SemanticUiNode, SemanticUiNode>>()
        pending.addLast(firstRoot to secondRoot)
        var compared = 0
        val maximum = maxOf(firstSnapshot.nodes.size, secondSnapshot.nodes.size)
        while (pending.isNotEmpty()) {
            if (++compared > maximum) return false
            val (first, second) = pending.removeFirst()
            if (!sameNodeState(first, second)) return false
            if (first.children.size != second.children.size) return false
            first.children.zip(second.children).forEach { (firstHandle, secondHandle) ->
                val firstChild = firstSnapshot.resolve(firstHandle) ?: return false
                val secondChild = secondSnapshot.resolve(secondHandle) ?: return false
                pending.addLast(firstChild to secondChild)
            }
        }
        return true
    }
}
