package ai.hans.standard.phone.accessibility

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Root-free, API-31-compatible domain executor. Android framework calls are
 * isolated behind [AndroidSemanticAccessibilityAdapter].
 */
class AccessibilityCommandExecutor(
    private val snapshots: CurrentSemanticUiSnapshotSource,
    private val adapter: AndroidSemanticAccessibilityAdapter,
    private val confirmationGate: AccessibilityUserConfirmationGate,
    private val riskPolicy: AccessibilityRiskPolicy = DefaultAccessibilityRiskPolicy,
    private val dictationGuard: DictationNonInterferenceGuard,
    private val uiAvailability: UiInteractionAvailabilityProbe,
    private val idempotencyLedger: AccessibilityIdempotencyLedger =
        BoundedAccessibilityIdempotencyLedger(),
) {
    @Synchronized
    fun execute(
        command: AccessibilityCommand,
        approval: AccessibilityUserApproval? = null,
    ): AccessibilityExecutionResult {
        currentUiInteractionAvailability(uiAvailability).let { availability ->
            if (!availability.isAvailable) {
                return userActionRequired(command, availability)
            }
        }
        val fingerprint = AccessibilityCommandFingerprint.of(command)
        idempotencyLedger.find(command.idempotencyKey)?.let { previous ->
            if (previous.commandFingerprint != fingerprint) {
                return rejected(command, "idempotency_key_conflict")
            }
            return previous.result.copy(replayed = true)
        }

        val snapshot = try {
            snapshotFor(command)
        } catch (_: Exception) {
            return rejected(command, "snapshot_source_failed")
        } ?: return rejected(command, "snapshot_unavailable")
        val prepared = prepare(command, snapshot)
        if (prepared.errorCode != null) {
            return rejected(command, prepared.errorCode)
        }

        if (command is AccessibilityCommand.Find) {
            currentUiInteractionAvailability(uiAvailability).let { availability ->
                if (!availability.isAvailable) {
                    return userActionRequired(command, availability)
                }
            }
            val result = executeFind(command, snapshot)
            store(fingerprint, result)
            return result
        }

        val risk = try {
            riskPolicy.requiredRisk(command, prepared.node)
        } catch (_: Exception) {
            return rejected(command, "risk_policy_failed")
        }
        if (risk != AccessibilityConfirmationRisk.NONE) {
            val request = AccessibilityConfirmationRequest(
                idempotencyKey = command.idempotencyKey,
                commandFingerprint = fingerprint,
                risk = risk,
                correlation = snapshot.correlation,
            )
            val approved = runCatching { confirmationGate.isApproved(request, approval) }
                .getOrDefault(false)
            if (!approved) return confirmationRequired(command, request)
        }

        // The dynamic-tool layer probes before queueing. Recheck here on the serial command
        // worker, immediately before the adapter boundary, so locking the phone while a command
        // waits in that queue cannot turn a previously allowed request into a platform action.
        currentUiInteractionAvailability(uiAvailability).let { availability ->
            if (!availability.isAvailable) {
                return userActionRequired(command, availability)
            }
        }

        val dictationStamp = try {
            dictationGuard.beforeAccessibilityAction()
        } catch (_: Exception) {
            return failed(
                command,
                "dictation_guard_unavailable",
                before = snapshot.correlation,
            )
        }
        val adapterResult = runCatching { dispatch(command) }
            .getOrElse { AndroidAccessibilityAdapterResult.Failure("adapter_exception") }
        val dictationIndependent = runCatching {
            dictationGuard.remainedIndependent(dictationStamp)
        }.getOrDefault(false)
        val deepUiUnavailability = adapterResult.uiInteractionAvailabilityOrNull()
        val result = if (deepUiUnavailability != null) {
            // The service's last-moment public-API gate rejected this before touching Android.
            // Keep the same non-terminal contract as the outer gate and deliberately do not put
            // it in the idempotency ledger: the identical command may run once the user unlocks.
            userActionRequired(command, deepUiUnavailability)
        } else if (!dictationIndependent) {
            AccessibilityExecutionResult(
                idempotencyKey = command.idempotencyKey,
                status = AccessibilityExecutionStatus.FAILED,
                replayed = false,
                observation = null,
                postcondition = AccessibilityPostcondition(
                    kind = AccessibilityPostconditionKind.DICTATION_NONINTERFERENCE,
                    status = AccessibilityPostconditionStatus.FAILED,
                    detailCode = "dictation_lifecycle_interference",
                    before = snapshot.correlation,
                    after = safeCurrentCorrelation(),
                    trust = UiDataTrust.LOCAL_SYSTEM,
                ),
                errorCode = "dictation_lifecycle_interference",
            )
        } else {
            mapAdapterResult(command, snapshot, prepared.node, adapterResult)
        }
        if (deepUiUnavailability == null) store(fingerprint, result)
        return result
    }

    private fun prepare(
        command: AccessibilityCommand,
        snapshot: SemanticUiSnapshot,
    ): PreparedCommand = when (command) {
        is AccessibilityCommand.Find -> {
            if (command.correlation != snapshot.correlation) {
                PreparedCommand(errorCode = "stale_snapshot")
            } else {
                PreparedCommand()
            }
        }
        is AccessibilityCommand.Click -> prepareNode(snapshot, command.handle) { node ->
            when {
                !node.visible -> "node_not_visible"
                !node.enabled -> "node_not_enabled"
                !node.clickable -> "node_not_clickable"
                SemanticUiAction.CLICK !in node.actions -> "node_click_unsupported"
                else -> null
            }
        }
        is AccessibilityCommand.SetText -> prepareNode(snapshot, command.handle) { node ->
            when {
                !node.visible -> "node_not_visible"
                !node.enabled -> "node_not_enabled"
                !node.editable -> "node_not_editable"
                SemanticUiAction.SET_TEXT !in node.actions -> "node_set_text_unsupported"
                else -> null
            }
        }
        is AccessibilityCommand.Scroll -> prepareNode(snapshot, command.handle) { node ->
            val action = when (command.direction) {
                UiScrollDirection.FORWARD -> SemanticUiAction.SCROLL_FORWARD
                UiScrollDirection.BACKWARD -> SemanticUiAction.SCROLL_BACKWARD
            }
            when {
                !node.visible -> "node_not_visible"
                !node.enabled -> "node_not_enabled"
                !node.scrollable -> "node_not_scrollable"
                action !in node.actions -> "node_scroll_unsupported"
                else -> null
            }
        }
        is AccessibilityCommand.Global -> {
            if (command.sessionId != snapshot.correlation.sessionId) {
                PreparedCommand(errorCode = "stale_session")
            } else {
                PreparedCommand()
            }
        }
        is AccessibilityCommand.CoordinateGesture -> when {
            command.correlation != snapshot.correlation ->
                PreparedCommand(errorCode = "stale_snapshot")
            !snapshot.displayBounds.contains(command.gesture.start) ->
                PreparedCommand(errorCode = "gesture_start_out_of_bounds")
            command.gesture.end?.let(snapshot.displayBounds::contains) == false ->
                PreparedCommand(errorCode = "gesture_end_out_of_bounds")
            !runCatching { adapter.supportsCoordinateGestures() }.getOrDefault(false) ->
                PreparedCommand(errorCode = "coordinate_gesture_unsupported")
            else -> PreparedCommand()
        }
    }

    private fun prepareNode(
        snapshot: SemanticUiSnapshot,
        handle: SemanticNodeHandle,
        validate: (SemanticUiNode) -> String?,
    ): PreparedCommand {
        if (handle.correlation != snapshot.correlation) {
            return PreparedCommand(errorCode = "stale_node_handle")
        }
        val node = snapshot.resolve(handle)
            ?: return PreparedCommand(errorCode = "node_not_found")
        return PreparedCommand(node = node, errorCode = validate(node))
    }

    private fun executeFind(
        command: AccessibilityCommand.Find,
        snapshot: SemanticUiSnapshot,
    ): AccessibilityExecutionResult {
        val handles = snapshot.nodes.asSequence()
            .filter { matches(it, command.query) }
            .take(command.query.maxResults)
            .map(SemanticUiNode::handle)
            .toList()
        return AccessibilityExecutionResult(
            idempotencyKey = command.idempotencyKey,
            status = AccessibilityExecutionStatus.SUCCEEDED,
            replayed = false,
            observation = AccessibilityObservation.FoundNodes(
                handles = handles,
                snapshotTruncated = !snapshot.isComplete,
            ),
            postcondition = AccessibilityPostcondition(
                kind = AccessibilityPostconditionKind.SNAPSHOT_QUERY,
                status = AccessibilityPostconditionStatus.VERIFIED,
                detailCode = "snapshot_query_completed",
                before = snapshot.correlation,
                after = snapshot.correlation,
                trust = UiDataTrust.LOCAL_SYSTEM,
            ),
        )
    }

    private fun matches(node: SemanticUiNode, query: UiFindQuery): Boolean {
        if (query.requireVisible && !node.visible) return false
        if (query.requireEnabled != null && query.requireEnabled != node.enabled) return false
        if (query.role != null && query.role != node.role) return false
        if (query.requiredAction != null && query.requiredAction !in node.actions) return false
        if (query.packageName != null && query.packageName != node.packageName?.value) return false
        if (query.className != null && query.className != node.className?.value) return false
        if (!matchesText(node.text?.value, query.text, query.textMatchMode)) return false
        if (!matchesText(
                node.contentDescription?.value,
                query.contentDescription,
                query.textMatchMode,
            )
        ) {
            return false
        }
        return true
    }

    private fun matchesText(
        actual: String?,
        expected: String?,
        mode: UiTextMatchMode,
    ): Boolean {
        if (expected == null) return true
        if (actual == null) return false
        return when (mode) {
            UiTextMatchMode.EXACT -> actual == expected
            UiTextMatchMode.PREFIX_CASE_INSENSITIVE -> actual.lowercase(Locale.ROOT)
                .startsWith(expected.lowercase(Locale.ROOT))
            UiTextMatchMode.CONTAINS_CASE_INSENSITIVE -> actual.lowercase(Locale.ROOT)
                .contains(expected.lowercase(Locale.ROOT))
        }
    }

    private fun dispatch(command: AccessibilityCommand): AndroidAccessibilityAdapterResult =
        when (command) {
            is AccessibilityCommand.Find -> error("find is handled without Android dispatch")
            is AccessibilityCommand.Click -> adapter.click(command.handle, command.postcondition)
            is AccessibilityCommand.SetText -> adapter.setText(
                command.handle,
                command.value,
                command.postcondition,
            )
            is AccessibilityCommand.Scroll -> adapter.scroll(
                command.handle,
                command.direction,
                command.postcondition,
            )
            is AccessibilityCommand.Global -> adapter.globalAction(
                command.action,
                command.postcondition,
            )
            is AccessibilityCommand.CoordinateGesture -> adapter.coordinateGesture(
                command.correlation,
                command.gesture,
                command.postcondition,
            )
        }

    private fun mapAdapterResult(
        command: AccessibilityCommand,
        snapshot: SemanticUiSnapshot,
        node: SemanticUiNode?,
        result: AndroidAccessibilityAdapterResult,
    ): AccessibilityExecutionResult = when (result) {
        is AndroidAccessibilityAdapterResult.Success -> {
            val receiptError = validateSuccessReceipt(command, snapshot, result)
            if (receiptError != null) {
                actionFailed(command, receiptError, snapshot.correlation)
            } else {
                AccessibilityExecutionResult(
                    idempotencyKey = command.idempotencyKey,
                    status = AccessibilityExecutionStatus.SUCCEEDED,
                    replayed = false,
                    observation = AccessibilityObservation.ActionReceipt(
                        target = node?.handle,
                        actionCode = result.actionCode,
                        resultingCorrelation = result.resultingCorrelation,
                        trust = UiDataTrust.LOCAL_SYSTEM,
                    ),
                    postcondition = result.postcondition,
                )
            }
        }
        AndroidAccessibilityAdapterResult.StaleTarget ->
            rejected(command, "adapter_stale_target")
        AndroidAccessibilityAdapterResult.Unsupported ->
            rejected(command, "adapter_action_unsupported")
        is AndroidAccessibilityAdapterResult.Failure ->
            actionFailed(command, safeCode(result.code), snapshot.correlation)
    }

    private fun validateSuccessReceipt(
        command: AccessibilityCommand,
        snapshot: SemanticUiSnapshot,
        result: AndroidAccessibilityAdapterResult.Success,
    ): String? {
        if (!result.actionCode.matches(SAFE_CODE)) return "adapter_receipt_invalid"
        if (result.trust != UiDataTrust.LOCAL_SYSTEM) return "adapter_receipt_untrusted"
        val postcondition = result.postcondition
        if (postcondition.trust != UiDataTrust.LOCAL_SYSTEM) {
            return "adapter_postcondition_untrusted"
        }
        if (postcondition.kind != command.actionPostconditionKind()) {
            return "adapter_postcondition_invalid"
        }
        if (postcondition.status !in setOf(
                AccessibilityPostconditionStatus.VERIFIED,
                AccessibilityPostconditionStatus.OBSERVED_NOT_VERIFIED,
            )
        ) {
            return "adapter_postcondition_invalid"
        }
        if (postcondition.before != snapshot.correlation) {
            return "adapter_correlation_mismatch"
        }
        if (postcondition.after != result.resultingCorrelation) {
            return "adapter_correlation_mismatch"
        }
        val after = postcondition.after
        if (after != null && after.sessionId != snapshot.correlation.sessionId) {
            return "adapter_session_mismatch"
        }
        return null
    }

    private fun confirmationRequired(
        command: AccessibilityCommand,
        request: AccessibilityConfirmationRequest,
    ): AccessibilityExecutionResult = AccessibilityExecutionResult(
        idempotencyKey = command.idempotencyKey,
        status = AccessibilityExecutionStatus.CONFIRMATION_REQUIRED,
        replayed = false,
        observation = null,
        postcondition = notExecuted(command, "confirmation_required"),
        requiredConfirmation = request,
        errorCode = "confirmation_required_${request.risk.name.lowercase(Locale.ROOT)}",
    )

    private fun userActionRequired(
        command: AccessibilityCommand,
        availability: UiInteractionAvailability,
    ): AccessibilityExecutionResult = AccessibilityExecutionResult(
        idempotencyKey = command.idempotencyKey,
        status = AccessibilityExecutionStatus.REJECTED,
        replayed = false,
        observation = null,
        postcondition = notExecuted(command, "user_action_required"),
        errorCode = availability.userActionRequiredErrorCode(),
    )

    private fun rejected(
        command: AccessibilityCommand,
        code: String,
    ): AccessibilityExecutionResult = AccessibilityExecutionResult(
        idempotencyKey = command.idempotencyKey,
        status = AccessibilityExecutionStatus.REJECTED,
        replayed = false,
        observation = null,
        postcondition = notExecuted(command, "request_rejected"),
        errorCode = safeCode(code),
    )

    private fun failed(
        command: AccessibilityCommand,
        code: String,
        postconditionKind: AccessibilityPostconditionKind =
            AccessibilityPostconditionKind.REQUEST_NOT_EXECUTED,
        before: UiSnapshotCorrelation? = command.correlationOrNull(),
    ): AccessibilityExecutionResult = AccessibilityExecutionResult(
        idempotencyKey = command.idempotencyKey,
        status = AccessibilityExecutionStatus.FAILED,
        replayed = false,
        observation = null,
        postcondition = AccessibilityPostcondition(
            kind = postconditionKind,
            status = AccessibilityPostconditionStatus.FAILED,
            detailCode = "adapter_operation_failed",
            before = before,
            after = safeCurrentCorrelation(),
            trust = UiDataTrust.LOCAL_SYSTEM,
        ),
        errorCode = safeCode(code),
    )

    private fun actionFailed(
        command: AccessibilityCommand,
        code: String,
        before: UiSnapshotCorrelation,
    ): AccessibilityExecutionResult = failed(
        command = command,
        code = code,
        postconditionKind = command.actionPostconditionKind(),
        before = before,
    )

    private fun notExecuted(
        command: AccessibilityCommand,
        detailCode: String,
    ): AccessibilityPostcondition = AccessibilityPostcondition(
        kind = AccessibilityPostconditionKind.REQUEST_NOT_EXECUTED,
        status = AccessibilityPostconditionStatus.NOT_EVALUATED,
        detailCode = detailCode,
        before = command.correlationOrNull(),
        after = null,
        trust = UiDataTrust.LOCAL_SYSTEM,
    )

    private fun AccessibilityCommand.correlationOrNull(): UiSnapshotCorrelation? = when (this) {
        is AccessibilityCommand.Find -> correlation
        is AccessibilityCommand.Click -> handle.correlation
        is AccessibilityCommand.SetText -> handle.correlation
        is AccessibilityCommand.Scroll -> handle.correlation
        is AccessibilityCommand.Global -> null
        is AccessibilityCommand.CoordinateGesture -> correlation
    }

    private fun safeCurrentCorrelation(): UiSnapshotCorrelation? =
        runCatching { snapshots.current()?.correlation }.getOrNull()

    /** Semantic handles may refer to a retained inspect frame; visual coordinates never do. */
    private fun snapshotFor(command: AccessibilityCommand): SemanticUiSnapshot? = when (command) {
        is AccessibilityCommand.Find -> snapshots.snapshotForCorrelation(command.correlation)
        is AccessibilityCommand.Click -> snapshots.snapshotForCorrelation(command.handle.correlation)
        is AccessibilityCommand.SetText ->
            snapshots.snapshotForCorrelation(command.handle.correlation)
        is AccessibilityCommand.Scroll -> snapshots.snapshotForCorrelation(command.handle.correlation)
        is AccessibilityCommand.Global,
        is AccessibilityCommand.CoordinateGesture,
        -> snapshots.current()
    }

    private fun AccessibilityCommand.actionPostconditionKind(): AccessibilityPostconditionKind =
        when (this) {
            is AccessibilityCommand.Find -> AccessibilityPostconditionKind.SNAPSHOT_QUERY
            is AccessibilityCommand.Click,
            is AccessibilityCommand.SetText,
            is AccessibilityCommand.Scroll,
            -> AccessibilityPostconditionKind.NODE_ACTION
            is AccessibilityCommand.Global -> AccessibilityPostconditionKind.GLOBAL_ACTION
            is AccessibilityCommand.CoordinateGesture ->
                AccessibilityPostconditionKind.COORDINATE_GESTURE
        }

    private fun store(fingerprint: String, result: AccessibilityExecutionResult) {
        idempotencyLedger.store(
            result.idempotencyKey,
            AccessibilityIdempotencyRecord(fingerprint, result),
        )
    }

    private fun safeCode(value: String): String =
        value.takeIf { it.matches(SAFE_CODE) } ?: "unspecified_adapter_failure"

    private data class PreparedCommand(
        val node: SemanticUiNode? = null,
        val errorCode: String? = null,
    )

    private companion object {
        val SAFE_CODE = Regex("[a-z][a-z0-9_]{2,79}")
    }
}

/** Reads a potentially platform-backed probe without ever failing open. */
internal fun currentUiInteractionAvailability(
    probe: UiInteractionAvailabilityProbe,
): UiInteractionAvailability = runCatching { probe.current() }
    .getOrDefault(UiInteractionAvailability.STATE_UNAVAILABLE)

/** Exact machine codes already used by the dynamic-tool user-action-required projection. */
internal fun UiInteractionAvailability.userActionRequiredErrorCode(): String = when (this) {
    UiInteractionAvailability.AVAILABLE -> "device_ui_available"
    UiInteractionAvailability.DEVICE_LOCKED -> "device_unlock_required"
    UiInteractionAvailability.SCREEN_NOT_INTERACTIVE -> "screen_wake_required"
    UiInteractionAvailability.DEVICE_LOCKED_AND_SCREEN_NOT_INTERACTIVE ->
        "device_wake_and_unlock_required"
    UiInteractionAvailability.STATE_UNAVAILABLE -> "device_ui_state_verification_required"
}

private fun AndroidAccessibilityAdapterResult.uiInteractionAvailabilityOrNull():
    UiInteractionAvailability? {
    val code = (this as? AndroidAccessibilityAdapterResult.Failure)?.code ?: return null
    return when (code) {
        "device_unlock_required" -> UiInteractionAvailability.DEVICE_LOCKED
        "screen_wake_required" -> UiInteractionAvailability.SCREEN_NOT_INTERACTIVE
        "device_wake_and_unlock_required" ->
            UiInteractionAvailability.DEVICE_LOCKED_AND_SCREEN_NOT_INTERACTIVE
        "device_ui_state_verification_required" -> UiInteractionAvailability.STATE_UNAVAILABLE
        else -> null
    }
}

class BoundedAccessibilityIdempotencyLedger(
    private val maxEntries: Int = 128,
) : AccessibilityIdempotencyLedger {
    init {
        require(maxEntries in 1..2_048)
    }

    private val records = object : LinkedHashMap<
        AccessibilityIdempotencyKey,
        AccessibilityIdempotencyRecord,
        >(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<
                AccessibilityIdempotencyKey,
                AccessibilityIdempotencyRecord,
                >?,
        ): Boolean = size > maxEntries
    }

    @Synchronized
    override fun find(key: AccessibilityIdempotencyKey): AccessibilityIdempotencyRecord? =
        records[key]

    @Synchronized
    override fun store(
        key: AccessibilityIdempotencyKey,
        record: AccessibilityIdempotencyRecord,
    ) {
        records[key] = record
    }
}

private object AccessibilityCommandFingerprint {
    fun of(command: AccessibilityCommand): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String?) {
            if (value == null) {
                digest.update(NULL_FIELD)
                return
            }
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        fun correlation(value: UiSnapshotCorrelation?) {
            field(value?.sessionId?.value)
            field(value?.windowId?.value?.toString())
            field(value?.snapshotId?.value?.toString())
        }

        field(command::class.java.simpleName)
        when (command) {
            is AccessibilityCommand.Find -> {
                correlation(command.correlation)
                with(command.query) {
                    field(packageName)
                    field(className)
                    field(text)
                    field(contentDescription)
                    field(role?.name)
                    field(requiredAction?.name)
                    field(requireVisible.toString())
                    field(requireEnabled?.toString())
                    field(textMatchMode.name)
                    field(maxResults.toString())
                }
            }
            is AccessibilityCommand.Click -> {
                correlation(command.handle.correlation)
                field(command.handle.nodeOrdinal.toString())
                field(command.confirmationRisk.name)
                field(command.postcondition.name)
            }
            is AccessibilityCommand.SetText -> {
                correlation(command.handle.correlation)
                field(command.handle.nodeOrdinal.toString())
                field(command.value)
                field(command.confirmationRisk.name)
                field(command.postcondition.name)
            }
            is AccessibilityCommand.Scroll -> {
                correlation(command.handle.correlation)
                field(command.handle.nodeOrdinal.toString())
                field(command.direction.name)
                field(command.confirmationRisk.name)
                field(command.postcondition.name)
            }
            is AccessibilityCommand.Global -> {
                field(command.sessionId.value)
                field(command.action.name)
                field(command.confirmationRisk.name)
                field(command.postcondition.name)
            }
            is AccessibilityCommand.CoordinateGesture -> {
                correlation(command.correlation)
                field(command.gesture.start.x.toString())
                field(command.gesture.start.y.toString())
                field(command.gesture.end?.x?.toString())
                field(command.gesture.end?.y?.toString())
                field(command.gesture.durationMillis.toString())
                field(command.confirmationRisk.name)
                field(command.postcondition.name)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private val NULL_FIELD = byteArrayOf(-1, -1, -1, -1)
}
