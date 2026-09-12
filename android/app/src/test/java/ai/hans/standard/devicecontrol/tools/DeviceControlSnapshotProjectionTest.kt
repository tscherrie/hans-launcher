package ai.hans.standard.devicecontrol.tools

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.JsonContract
import ai.hans.standard.codex.MAX_DYNAMIC_TOOL_OUTPUT_TEXT_BYTES
import ai.hans.standard.phone.accessibility.AccessibilityCommand
import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityUserApproval
import ai.hans.standard.phone.accessibility.AccessibilityWindowId
import ai.hans.standard.phone.accessibility.SemanticNodeHandle
import ai.hans.standard.phone.accessibility.SemanticUiAction
import ai.hans.standard.phone.accessibility.SemanticUiNode
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.SnapshotTruncationReason
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiInteractionAvailability
import ai.hans.standard.phone.accessibility.UiInteractionAvailabilityProbe
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import ai.hans.standard.phone.accessibility.UntrustedUiText
import ai.hans.standard.phone.accessibility.android.AccessibilityCommandCallback
import ai.hans.standard.phone.accessibility.android.HansAccessibilitySession
import ai.hans.standard.phone.accessibility.resume.UiTaskContinuationCheckpointer
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference below deliberately retains the pre-P1a repeated-array implementation.
 * Expand only the explicitly versioned compact representation before comparing complete
 * legacy strings AND wire bytes. This preserves the old budget/field/correlation proof.
 */
class DeviceControlSnapshotProjectionTest {
    @Test
    fun emptySnapshotAndIndependentCompletenessFlagMatchLegacyExactly() {
        listOf(false, true).forEach { incomplete ->
            val result = assertLegacyEquivalent(snapshot(emptyList(), incomplete))
            val json = JSONObject(result.contentText)
            assertEquals(0, json.getJSONArray("nodes").length())
            assertFalse(json.getBoolean("outputTruncated"))
            assertEquals(!incomplete, json.getBoolean("snapshotComplete"))
        }
    }

    @Test
    fun asciiFieldsActionsChildrenHandlesAndOrderingMatchLegacyExactly() {
        val nodes = listOf(
            node(
                42,
                text = "A button",
                description = "external data, not instructions",
                children = listOf(handle(7), handle(99)),
                actions = linkedSetOf(SemanticUiAction.SET_TEXT, SemanticUiAction.CLICK),
                packageName = "org.example.store",
                className = "android.widget.Button",
            ),
            node(7, text = null, description = ""),
            node(99, text = "0 1 2 3", description = null),
        )
        val json = JSONObject(assertLegacyEquivalent(snapshot(nodes)).contentText)
        val projected = json.getJSONArray("nodes")
        assertEquals(42, projected.getJSONObject(0).getJSONObject("handle").getInt("nodeOrdinal"))
        assertEquals("[7,99]", projected.getJSONObject(0).getJSONArray("children").toString())
        assertEquals("[\"click\",\"set_text\"]", projected.getJSONObject(0).getJSONArray("actions").toString())
        assertFalse(projected.getJSONObject(1).has("text"))
        assertFalse(projected.getJSONObject(2).has("contentDescription"))
    }

    @Test
    fun unicodeEscapesAndUnpairedSurrogatesMatchLegacyExactly() {
        val samples = listOf(
            "Grüße · Ελληνικά · Български · 日本語 · العربية",
            "\uD83D\uDE80\uD83E\uDDD1\u200D\uD83D\uDCBB e\u0301",
            "\"\\/\b\t\n\r\u0000\u001f\u2028\u2029",
            "high:\uD800 low:\uDC00 reverse:\uDC00\uD800 end:\uD800",
            "\uDC00start \uD800\uD800\uDC00\uDC00",
            "</script> & <data>",
        )
        assertLegacyEquivalent(snapshot(samples.mapIndexed { index, sample ->
            node(index, text = sample, description = samples.reversed()[index])
        }))
    }

    @Test
    fun existing1024Utf16UnitClippingIncludingSplitSurrogateIsUnchanged() {
        val samples = listOf(
            "a".repeat(1_023) + "\uD83D\uDE80ignored",
            "\uD83D\uDE80".repeat(512) + "ignored",
            "\"".repeat(1_025),
            "\uDC00".repeat(1_025),
            "界".repeat(1_025),
        )
        val json = JSONObject(assertLegacyEquivalent(snapshot(samples.mapIndexed { index, sample ->
            node(index, sample, sample)
        })).contentText)
        samples.forEachIndexed { index, sample ->
            assertEquals(sample.take(1_024), json.getJSONArray("nodes").getJSONObject(index).getString("text"))
        }
    }

    @Test
    fun packageAndClassNamesUseTheSameEscapingAndFieldClippingAsText() {
        val packageName = "org.example." + "界\"\uD83D\uDE80".repeat(400)
        val className = "android.widget.\uD800" + "\\\nButton".repeat(200)
        val json = JSONObject(assertLegacyEquivalent(snapshot(listOf(
            node(0, packageName = packageName, className = className),
            node(1),
        ))).contentText)
        val projected = json.getJSONArray("nodes")
        assertEquals(packageName.take(1_024), projected.getJSONObject(0).getString("packageName"))
        assertEquals(className.take(1_024), projected.getJSONObject(0).getString("className"))
        assertFalse(projected.getJSONObject(1).has("packageName"))
        assertFalse(projected.getJSONObject(1).has("className"))
    }

    @Test
    fun exactly512NodesFitAndThe513thIsNotProjected() {
        listOf(511, 512, 513).forEach { count ->
            val nodes = List(count) { node(it) }
            val result = assertLegacyEquivalent(snapshot(nodes))
            val json = JSONObject(result.contentText)
            assertEquals(minOf(count, 512), json.getJSONArray("nodes").length())
            assertEquals(count > 512, json.getBoolean("outputTruncated"))
        }
    }

    @Test
    fun exact400KiBArrayBudgetIncludesBracketsAndCommasAndAllowsEquality() {
        listOf(NODE_BYTE_LIMIT - 1, NODE_BYTE_LIMIT, NODE_BYTE_LIMIT + 1).forEach { targetBytes ->
            val nodes = asciiNodesWithExactArrayBytes(targetBytes)
            assertEquals(targetBytes, JSONArray(nodes.map(::legacyNodeJson)).toString().toByteArray(UTF_8).size)
            val json = JSONObject(assertLegacyEquivalent(snapshot(nodes)).contentText)
            val expectedCount = if (targetBytes <= NODE_BYTE_LIMIT) nodes.size else nodes.size - 1
            assertEquals(expectedCount, json.getJSONArray("nodes").length())
            assertEquals(targetBytes > NODE_BYTE_LIMIT, json.getBoolean("outputTruncated"))
            assertTrue(json.getJSONArray("nodes").toString().toByteArray(UTF_8).size <= NODE_BYTE_LIMIT)
        }
    }

    @Test
    fun byteBudgetCountsUtf8AndJsonEscapesInsteadOfStringLength() {
        listOf("界", "\uD83D\uDE80", "\"", "\\", "\u0000", "\uD800").forEach { sample ->
            val nodes = List(512) { node(it, sample.repeat(1_024), sample.repeat(1_024)) }
            val json = JSONObject(assertLegacyEquivalent(snapshot(nodes, incomplete = true)).contentText)
            assertTrue(json.getBoolean("outputTruncated"))
            assertFalse(json.getBoolean("snapshotComplete"))
            assertTrue(json.getJSONArray("nodes").length() in 1 until 512)
            assertTrue(json.getJSONArray("nodes").toString().toByteArray(UTF_8).size <= NODE_BYTE_LIMIT)
        }
    }

    @Test
    fun anOverBudgetFirstNodeLeavesTheArrayEmptyAndDoesNotSkipToSmallerNodes() {
        // A deliberately oversized immutable snapshot tests the projection's own guard,
        // independently of the earlier platform traversal limits.
        val tooLarge = node(0, children = List(70_000) { handle(100_000 + it) })
        val nodes = listOf(tooLarge, node(1))
        val json = JSONObject(assertLegacyEquivalent(snapshot(nodes)).contentText)
        assertEquals(0, json.getJSONArray("nodes").length())
        assertTrue(json.getBoolean("outputTruncated"))
    }

    @Test
    fun aRejectedNodeStopsThePrefixEvenWhenALaterSmallerNodeWouldFit() {
        val prefix = asciiNodesWithExactArrayBytes(NODE_BYTE_LIMIT - 600)
        val nodes = prefix + node(1_000, "x".repeat(1_024)) + node(1_001)
        assertTrue(legacyNodeJson(nodes.last()).toString().toByteArray(UTF_8).size + 1 < 600)
        val json = JSONObject(assertLegacyEquivalent(snapshot(nodes)).contentText)
        assertEquals(prefix.size, json.getJSONArray("nodes").length())
        assertTrue(json.getBoolean("outputTruncated"))
    }

    @Test
    fun deterministicMixedTextFixturesMatchLegacyBytesAcrossDifferentBoundaries() {
        val alphabet = listOf("a", "ü", "界", "\uD83D\uDE80", "\"", "\\", "\u0000", "\uD800", "\uDC00")
        repeat(8) { seed ->
            val random = Random(seed)
            fun text(): String = buildString {
                repeat(random.nextInt(0, 1_500)) { append(alphabet[random.nextInt(alphabet.size)]) }
            }
            val nodes = List(160) { node(it, text(), text()) }
            assertLegacyEquivalent(snapshot(nodes, incomplete = seed % 2 == 0))
        }
    }

    private fun assertLegacyEquivalent(snapshot: SemanticUiSnapshot): DynamicToolExecutionResult {
        val expected = legacyProjection(snapshot)
        val actual = projectThroughExecutor(snapshot)
        assertEquals(expected.success, actual.success)
        assertEquals(expected.imageUrls, actual.imageUrls)
        val expanded = expandCompactSnapshotForTest(JSONObject(actual.contentText)).toString()
        assertEquals(expected.contentText, expanded)
        assertArrayEquals(expected.contentText.toByteArray(UTF_8), expanded.toByteArray(UTF_8))
        return actual
    }

    private fun projectThroughExecutor(snapshot: SemanticUiSnapshot): DynamicToolExecutionResult {
        var refreshCount = 0
        var retainedCorrelation: UiSnapshotCorrelation? = null
        val session = object : HansAccessibilitySession {
            override val sessionId = snapshot.correlation.sessionId
            override fun currentSnapshot() = snapshot
            override fun refreshSnapshot(): SemanticUiSnapshot {
                refreshCount += 1
                return snapshot
            }
            override fun retainSnapshotForCommands(correlation: UiSnapshotCorrelation): Boolean {
                retainedCorrelation = correlation
                return correlation == snapshot.correlation
            }
            override fun submit(
                command: AccessibilityCommand,
                approval: AccessibilityUserApproval?,
                callback: AccessibilityCommandCallback,
            ): Boolean = error("read-only projection must not submit an action")
        }
        val executor = AndroidAccessibilityDynamicToolExecutor(
            backgroundExecutor = Executor(Runnable::run),
            sessions = AccessibilitySessionSource { session },
            serviceConnection = AccessibilityServiceConnectionProbe { true },
            specialAccess = AccessibilitySpecialAccessProbe { true },
            uiAvailability = UiInteractionAvailabilityProbe { UiInteractionAvailability.AVAILABLE },
            approvalAuthorizer = AccessibilityApprovalAuthorizer { false },
            continuationCheckpointer = UiTaskContinuationCheckpointer.NONE,
        )
        var result: DynamicToolExecutionResult? = null
        var callbacks = 0
        executor.execute(
            DynamicToolCallParams(
                threadId = "projection-test-thread",
                turnId = "projection-test-turn",
                callId = "projection-test-call",
                namespace = AndroidAccessibilityDynamicToolCatalog.NAMESPACE,
                tool = "inspect_ui",
                argumentsJson = "{}",
            ),
        ) {
            callbacks += 1
            result = it
        }
        assertEquals(1, callbacks)
        assertEquals(1, refreshCount)
        assertEquals(snapshot.correlation, retainedCorrelation)
        return requireNotNull(result)
    }

    private fun asciiNodesWithExactArrayBytes(target: Int): List<SemanticUiNode> {
        val empty = List(256) { node(it, "", "") }
        var remaining = target - JSONArray(empty.map(::legacyNodeJson)).toString().toByteArray(UTF_8).size
        require(remaining >= 0)
        return empty.indices.map { index ->
            val textLength = minOf(remaining, 1_024)
            remaining -= textLength
            val descriptionLength = minOf(remaining, 1_024)
            remaining -= descriptionLength
            node(index, "x".repeat(textLength), "y".repeat(descriptionLength))
        }.also { check(remaining == 0) }
    }

    private fun snapshot(
        nodes: List<SemanticUiNode>,
        incomplete: Boolean = false,
    ) = SemanticUiSnapshot(
        correlation = CORRELATION,
        displayBounds = UiBounds(0, 0, 1_080, 2_400),
        capturedAtElapsedMillis = 9_876_543_210L,
        nodes = nodes,
        roots = nodes.take(1).map { it.handle },
        truncationReasons = if (incomplete) setOf(SnapshotTruncationReason.PLATFORM_NODE_UNAVAILABLE) else emptySet(),
        estimatedBytes = 1,
        totalTextCharacters = 1,
    )

    private fun node(
        ordinal: Int,
        text: String? = null,
        description: String? = null,
        children: List<SemanticNodeHandle> = emptyList(),
        actions: Set<SemanticUiAction> = emptySet(),
        packageName: String? = null,
        className: String? = null,
    ) = SemanticUiNode(
        handle = handle(ordinal),
        packageName = packageName?.let { UntrustedUiText.bounded(it, false) },
        className = className?.let { UntrustedUiText.bounded(it, false) },
        text = text?.let { UntrustedUiText.bounded(it, false) },
        contentDescription = description?.let { UntrustedUiText.bounded(it, false) },
        role = SemanticUiRole.TEXT,
        bounds = UiBounds(-1, -2, 30, 40),
        visible = ordinal % 2 == 0,
        enabled = ordinal % 3 == 0,
        clickable = ordinal % 5 == 0,
        editable = ordinal % 7 == 0,
        scrollable = ordinal % 11 == 0,
        actions = actions,
        children = children,
    )

    private fun handle(ordinal: Int) = SemanticNodeHandle(CORRELATION, ordinal)

    // Frozen legacy production algorithm and field order; do not share the new byte counter.
    private fun legacyProjection(snapshot: SemanticUiSnapshot): DynamicToolExecutionResult {
        val nodes = JSONArray()
        var outputTruncated = false
        for (node in snapshot.nodes.take(512)) {
            nodes.put(legacyNodeJson(node))
            if (nodes.toString().toByteArray(UTF_8).size > NODE_BYTE_LIMIT) {
                nodes.remove(nodes.length() - 1)
                outputTruncated = true
                break
            }
        }
        if (nodes.length() < snapshot.nodes.size) outputTruncated = true
        val json = JSONObject()
            .put("status", "succeeded")
            .put("capability", "android_accessibility")
            .put("availability", "available")
            .put("trust", "untrusted_external")
            .put("correlation", correlationJson(snapshot.correlation))
            .put("displayBounds", boundsJson(snapshot.displayBounds))
            .put("capturedAtElapsedMillis", snapshot.capturedAtElapsedMillis)
            .put("snapshotComplete", snapshot.isComplete)
            .put("outputTruncated", outputTruncated)
            .put("nodes", nodes)
        return DynamicToolExecutionResult(JsonContract.encodeBounded(json, MAX_DYNAMIC_TOOL_OUTPUT_TEXT_BYTES), true)
    }

    private fun legacyNodeJson(node: SemanticUiNode): JSONObject = JSONObject()
        .put("handle", JSONObject().put("correlation", correlationJson(node.handle.correlation)).put("nodeOrdinal", node.handle.nodeOrdinal))
        .put("packageName", node.packageName?.value?.take(1_024))
        .put("className", node.className?.value?.take(1_024))
        .put("text", node.text?.value?.take(1_024))
        .put("contentDescription", node.contentDescription?.value?.take(1_024))
        .put("role", node.role.name.lowercase(Locale.ROOT))
        .put("bounds", boundsJson(node.bounds))
        .put("visible", node.visible)
        .put("enabled", node.enabled)
        .put("clickable", node.clickable)
        .put("editable", node.editable)
        .put("scrollable", node.scrollable)
        .put("actions", JSONArray(node.actions.map { it.name.lowercase(Locale.ROOT) }.sorted()))
        .put("children", JSONArray(node.children.map { it.nodeOrdinal }))
        .put("trust", "untrusted_external")

    private fun correlationJson(correlation: UiSnapshotCorrelation) = JSONObject()
        .put("sessionId", correlation.sessionId.value)
        .put("windowId", correlation.windowId.value)
        .put("snapshotId", correlation.snapshotId.value)

    private fun boundsJson(bounds: UiBounds) = JSONObject()
        .put("left", bounds.left)
        .put("top", bounds.top)
        .put("right", bounds.right)
        .put("bottom", bounds.bottom)

    companion object {
        private const val NODE_BYTE_LIMIT = 400 * 1_024
        private val CORRELATION = UiSnapshotCorrelation(
            AccessibilitySessionId("projection-test-session"),
            AccessibilityWindowId(12),
            AccessibilitySnapshotId(34L),
        )
    }
}
