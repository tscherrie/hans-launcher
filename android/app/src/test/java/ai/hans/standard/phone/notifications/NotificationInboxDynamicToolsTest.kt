package ai.hans.standard.phone.notifications

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.phone.capabilities.CapabilityConfirmation
import ai.hans.standard.phone.tools.DynamicToolConfirmationProvider
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationInboxDynamicToolsTest {
    @Test
    fun queuedCancellationPreventsConfirmationQueryAndManagementSideEffects() {
        val source = FakeManagedSource()
        val queued = ManualExecutor()
        var confirmationCalls = 0
        var callbacks = 0
        val executor = NotificationInboxDynamicToolExecutor(
            source = source,
            backgroundExecutor = queued,
            confirmationProvider = DynamicToolConfirmationProvider { request ->
                confirmationCalls += 1
                CapabilityConfirmation(request.capabilityId, request.idempotencyKey, request.risk)
            },
        )

        val handle = executor.executeCancellable(
            call("set_retention", "{\"maxEvents\":77,\"maxAgeHours\":36}"),
            DynamicToolCancellation.NONE,
        ) { callbacks += 1 }

        assertEquals(
            DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT,
            handle.cancel(),
        )
        queued.runNext()
        assertEquals(0, confirmationCalls)
        assertEquals(0, source.totalCalls)
        assertEquals(0, callbacks)
    }

    @Test
    fun cancellationDuringConfirmationPreventsNotificationPrivacyMutation() {
        val source = FakeManagedSource()
        val queued = ManualExecutor()
        var callbacks = 0
        var cancellationDisposition: DynamicToolCancellationDisposition? = null
        lateinit var handle: DynamicToolExecutionHandle
        val executor = NotificationInboxDynamicToolExecutor(
            source = source,
            backgroundExecutor = queued,
            confirmationProvider = DynamicToolConfirmationProvider { request ->
                cancellationDisposition = handle.cancel()
                CapabilityConfirmation(request.capabilityId, request.idempotencyKey, request.risk)
            },
        )

        handle = executor.executeCancellable(
            call("exclude_package", "{\"packageName\":\"com.example.chat\"}"),
            DynamicToolCancellation.NONE,
        ) { callbacks += 1 }
        queued.runNext()

        assertEquals(
            DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED,
            cancellationDisposition,
        )
        assertEquals(0, source.mutationCalls)
        assertEquals(0, source.clearCalls)
        assertEquals(0, callbacks)
    }

    @Test
    fun cancellationDuringRecallPrivacyReadPreventsEveryLaterInboxPageRead() {
        val source = FakeManagedSource()
        val queued = ManualExecutor()
        var callbacks = 0
        lateinit var handle: DynamicToolExecutionHandle
        source.onPrivacyStatus = { handle.cancel() }
        val executor = NotificationInboxDynamicToolExecutor(source, queued)

        handle = executor.executeCancellable(
            call("recent", "{\"sinceEpochMillis\":0}"),
            DynamicToolCancellation.NONE,
        ) { callbacks += 1 }
        queued.runNext()

        assertEquals(1, source.statusCalls)
        assertEquals(0, source.queryCalls)
        assertEquals(0, callbacks)
    }

    @Test
    fun catalogExposesOnlyBoundedRecallNeverRawPagesOrDigests() {
        val names = NotificationInboxDynamicToolCatalog.namespace.tools.map { it.name }.toSet()

        assertTrue(names.contains("recent"))
        assertTrue(names.contains("relevant"))
        assertFalse(names.contains("page"))
        assertFalse(names.contains("digest"))
    }

    @Test
    fun unknownArgumentsFailClosedWithoutRunningAQuery() {
        val source = object : NotificationInboxQuerySource {
            override fun queryPage(afterSequenceExclusive: Long, limit: Int): NotificationPage =
                error("must not run")

            override fun queryDigest(
                afterSequenceExclusive: Long,
                maxEvents: Int,
                maxUtf8Bytes: Int,
            ): NotificationDigest = error("must not run")
        }
        val executor = NotificationInboxDynamicToolExecutor(source, Executor { it.run() })
        var result: DynamicToolExecutionResult? = null

        executor.execute(
            call("recent", "{\"sinceEpochMillis\":0,\"instruction\":\"do this\"}"),
        ) { result = it }

        assertFalse(requireNotNull(result).success)
        assertEquals(
            "notification_query_failed",
            JSONObject(requireNotNull(result).contentText).getString("errorCode"),
        )
    }

    @Test
    fun managementMutationsReturnVerifiedEffectiveState() {
        val source = FakeManagedSource()
        val executor = trustedExecutor(source)

        val excluded = execute(
            executor,
            "exclude_package",
            "{\"packageName\":\"com.example.chat\"}",
        )
        assertTrue(excluded.success)
        val excludedJson = JSONObject(excluded.contentText)
        assertTrue(excludedJson.getBoolean("effective"))
        assertEquals(
            "com.example.chat",
            excludedJson.getJSONArray("userExcludedPackages").getString(0),
        )

        val retention = execute(
            executor,
            "set_retention",
            "{\"maxEvents\":77,\"maxAgeHours\":36}",
        )
        assertEquals(
            77,
            JSONObject(retention.contentText).getJSONObject("retention").getInt("maxEvents"),
        )
    }

    @Test
    fun privacyReducingAndDestructiveOperationsRequireExactConfirmation() {
        val source = FakeManagedSource().apply { excludePackage("com.example.chat") }
        val executor = NotificationInboxDynamicToolExecutor(source, Executor { it.run() })

        val missingIncludeConfirmation = execute(
            executor,
            "include_package",
            "{\"packageName\":\"com.example.chat\"}",
        )
        assertFalse(missingIncludeConfirmation.success)
        assertTrue(source.privacyStatus().userExcludedPackages.contains("com.example.chat"))

        val missingClearConfirmation = execute(
            executor,
            "clear_history",
            "{}",
        )
        assertFalse(missingClearConfirmation.success)
        assertEquals(0, source.clearCalls)

        val publicMagicWordStillCannotAuthorize = execute(
            executor,
            "clear_history",
            "{\"confirmation\":\"CLEAR_NOTIFICATION_HISTORY\"}",
        )
        assertFalse(publicMagicWordStillCannotAuthorize.success)
        assertEquals(0, source.clearCalls)

        val cleared = execute(
            trustedExecutor(source),
            "clear_history",
            "{}",
        )
        assertTrue(cleared.success)
        assertEquals(1, source.clearCalls)
    }

    @Test
    fun everyNotificationPrivacyMutationFailsWithoutTrustedOnDeviceConfirmation() {
        val source = FakeManagedSource()
        val executor = NotificationInboxDynamicToolExecutor(source, Executor { it.run() })
        val recovery = JSONObject(source.exportPrivacySettings())

        listOf(
            "exclude_package" to "{\"packageName\":\"com.example.chat\"}",
            "include_package" to "{\"packageName\":\"com.example.chat\"}",
            "set_retention" to "{\"maxEvents\":777,\"maxAgeHours\":72}",
            "clear_history" to "{}",
            "import_privacy_settings" to JSONObject().put("document", recovery).toString(),
        ).forEach { (tool, arguments) ->
            assertFalse("unexpected success for $tool", execute(executor, tool, arguments).success)
        }

        assertEquals(0, source.mutationCalls)
        assertEquals(0, source.clearCalls)
        assertEquals(NotificationPrivacySettings(), source.currentSettings())
    }

    @Test
    fun incompleteDurableClearIsReportedAsToolFailureNeverEffectiveSuccess() {
        val source = FakeManagedSource().apply { clearSucceeds = false }
        val result = execute(trustedExecutor(source), "clear_history", "{}")

        assertFalse(result.success)
        val output = JSONObject(result.contentText)
        assertEquals("failed", output.getString("status"))
        assertFalse(output.optBoolean("effective", false))
        assertEquals(1, source.clearCalls)
    }

    @Test
    fun explicitClearUsesAllDataEntryNeverTransientHistoryRecovery() {
        val source = FakeManagedSource()

        val result = execute(trustedExecutor(source), "clear_history", "{}")

        assertTrue(result.success)
        assertEquals(1, source.clearCalls)
        assertEquals(0, source.transientClearCalls)
        val output = JSONObject(result.contentText)
        assertTrue(output.getBoolean("notificationFactArchiveCleared"))
        assertTrue(output.getBoolean("validatedFactCandidatesCleared"))
        assertTrue(output.getBoolean("privacyMutationAcknowledged"))
        assertFalse(output.getBoolean("nativeCodexDataTouched"))
        assertFalse(output.getBoolean("confirmedUserProfileTouched"))
    }

    @Test
    fun incompleteArchiveCandidatesOrIntentNeverClaimSuccessfulForget() {
        listOf<(FakeManagedSource) -> Unit>(
            { it.archiveClearSucceeds = false },
            { it.factCandidatesClearSucceeds = false },
            { it.privacyAckSucceeds = false },
        ).forEach { makeIncomplete ->
            val source = FakeManagedSource().also(makeIncomplete)

            val result = execute(trustedExecutor(source), "clear_history", "{}")

            assertFalse(result.success)
            val output = JSONObject(result.contentText)
            assertEquals("failed", output.getString("status"))
            assertFalse(output.optBoolean("effective", false))
            assertEquals(1, source.clearCalls)
            assertEquals(0, source.transientClearCalls)
        }
    }

    @Test
    fun allDataClearDescriptionDoesNotPromiseNativeMemoryOrProfileErasure() {
        val description = NotificationInboxDynamicToolCatalog.namespace.tools
            .single { it.name == "clear_history" }.description

        assertTrue(description.contains("notification fact archive"))
        assertTrue(description.contains("native Codex memory"))
        assertTrue(description.contains("confirmed user profile are not erased"))
    }

    @Test
    fun settingsRecoveryNeverExportsNotificationContentOrAcceptsCallerPaths() {
        val source = FakeManagedSource().apply { excludePackage("com.example.chat") }
        val executor = trustedExecutor(source)

        val exported = execute(executor, "export_privacy_settings", "{}")
        val output = JSONObject(exported.contentText)
        val serialized = output.toString()
        assertEquals("fixed_app_private_storage_no_caller_paths", output.getString("pathPolicy"))
        assertFalse(serialized.contains("private-key"))
        assertFalse(serialized.contains("notificationActions"))
        assertFalse(serialized.contains("replyActions"))

        val document = output.getJSONObject("document")
        document.getJSONObject("retention").put("maxEvents", 91)
        val imported = execute(
            executor,
            "import_privacy_settings",
            JSONObject()
                .put("document", document)
                .toString(),
        )
        assertTrue(imported.success)
        assertEquals(91, source.privacyStatus().retention.maxEvents)

        val pathAttempt = execute(
            executor,
            "export_privacy_settings",
            "{\"path\":\"/sdcard/leak.json\"}",
        )
        assertFalse(pathAttempt.success)
    }

    @Test
    fun oversizedArgumentsFailBeforeManagementRuns() {
        val source = FakeManagedSource()
        val executor = NotificationInboxDynamicToolExecutor(source, Executor { it.run() })
        val oversized = "x".repeat(41 * 1_024)

        val result = execute(
            executor,
            "exclude_package",
            JSONObject().put("packageName", oversized).toString(),
        )

        assertFalse(result.success)
        assertTrue(source.privacyStatus().userExcludedPackages.isEmpty())
    }

    private fun execute(
        executor: NotificationInboxDynamicToolExecutor,
        tool: String,
        arguments: String,
    ): DynamicToolExecutionResult {
        var result: DynamicToolExecutionResult? = null
        executor.execute(call(tool, arguments)) { result = it }
        return requireNotNull(result)
    }

    private fun trustedExecutor(source: NotificationInboxQuerySource) =
        NotificationInboxDynamicToolExecutor(
            source = source,
            backgroundExecutor = Executor { it.run() },
            confirmationProvider = DynamicToolConfirmationProvider { request ->
                CapabilityConfirmation(request.capabilityId, request.idempotencyKey, request.risk)
            },
        )

    private fun call(tool: String, arguments: String) = DynamicToolCallParams(
        threadId = "thread-1",
        turnId = "turn-1",
        callId = "call-1",
        namespace = NotificationInboxDynamicToolCatalog.NAMESPACE,
        tool = tool,
        argumentsJson = arguments,
    )

    private class FakeManagedSource : NotificationInboxQuerySource, NotificationInboxManagementSource {
        private var settings = NotificationPrivacySettings()
        var queryCalls = 0
        var statusCalls = 0
        var exportCalls = 0
        var onPrivacyStatus: () -> Unit = {}
        var clearCalls = 0
        var transientClearCalls = 0
        var mutationCalls = 0
        var clearSucceeds = true
        var archiveClearSucceeds = true
        var factCandidatesClearSucceeds = true
        var privacyAckSucceeds = true

        val totalCalls: Int
            get() = queryCalls + statusCalls + exportCalls + clearCalls +
                transientClearCalls + mutationCalls

        fun currentSettings(): NotificationPrivacySettings = settings

        override fun queryPage(afterSequenceExclusive: Long, limit: Int): NotificationPage {
            queryCalls += 1
            return NotificationPage(emptyList(), afterSequenceExclusive, false)
        }

        override fun queryDigest(
            afterSequenceExclusive: Long,
            maxEvents: Int,
            maxUtf8Bytes: Int,
        ): NotificationDigest {
            queryCalls += 1
            return NotificationDigest("", 0, 0, afterSequenceExclusive, false)
        }

        override fun privacyStatus(): NotificationPrivacyStatus {
            statusCalls += 1
            onPrivacyStatus()
            return NotificationPrivacyStatus(
                policyAvailable = true,
                protectedPackages = setOf("ai.hans.standard"),
                userExcludedPackages = settings.excludedPackages,
                retention = settings.retention,
            )
        }

        override fun excludePackage(packageName: String): NotificationPrivacyMutation {
            mutationCalls += 1
            settings = settings.copy(excludedPackages = settings.excludedPackages + packageName)
            return NotificationPrivacyMutation(settings, removedEvents = 2)
        }

        override fun includePackage(packageName: String): NotificationPrivacyMutation {
            mutationCalls += 1
            settings = settings.copy(excludedPackages = settings.excludedPackages - packageName)
            return NotificationPrivacyMutation(settings, removedEvents = 0)
        }

        override fun setRetention(maxEvents: Int, maxAgeHours: Int): NotificationPrivacyMutation {
            mutationCalls += 1
            settings = settings.copy(retention = NotificationRetentionPolicy(maxEvents, maxAgeHours))
            return NotificationPrivacyMutation(settings, removedEvents = 3)
        }

        override fun clearHistory(): NotificationHistoryClearResult {
            transientClearCalls += 1
            return NotificationHistoryClearResult(4, triageQueueCleared = clearSucceeds)
        }

        override fun clearAllNotificationData(): NotificationAllDataClearResult {
            clearCalls += 1
            return NotificationAllDataClearResult(
                removedInboxEvents = 4,
                triageQueueCleared = clearSucceeds,
                factArchiveCleared = archiveClearSucceeds,
                factCandidatesCleared = factCandidatesClearSucceeds,
                privacyMutationAcknowledged = privacyAckSucceeds,
            )
        }

        override fun exportPrivacySettings(): String {
            exportCalls += 1
            return NotificationPrivacyRecoveryCodec.encode(settings)
        }

        override fun importPrivacySettings(document: String): NotificationPrivacyMutation {
            mutationCalls += 1
            settings = NotificationPrivacyRecoveryCodec.decode(document)
            return NotificationPrivacyMutation(settings, removedEvents = 1)
        }
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            tasks.removeFirst().run()
        }
    }
}
