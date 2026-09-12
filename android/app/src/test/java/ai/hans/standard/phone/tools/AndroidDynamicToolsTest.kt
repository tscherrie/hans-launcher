package ai.hans.standard.phone.tools

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.phone.capabilities.AndroidAdapterResult
import ai.hans.standard.phone.capabilities.AndroidSpecialAccess
import ai.hans.standard.phone.capabilities.CapabilityBroker
import ai.hans.standard.phone.capabilities.CapabilityConfirmation
import ai.hans.standard.phone.capabilities.CapabilityEnvironment
import ai.hans.standard.phone.capabilities.CapabilityObservation
import ai.hans.standard.phone.capabilities.CapabilityRegistry
import ai.hans.standard.phone.capabilities.ConfirmationRisk
import ai.hans.standard.phone.capabilities.DispatchObservation
import ai.hans.standard.phone.capabilities.LaunchableApp
import ai.hans.standard.phone.capabilities.NetworkTransport
import ai.hans.standard.phone.capabilities.PublicAndroidActionAdapter
import ai.hans.standard.phone.capabilities.SettingsDestination
import ai.hans.standard.phone.capabilities.StandardCapabilities
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDynamicToolsTest {
    @Test
    fun processExecutorPublishesBothPublicAndroidAndSemanticAccessibilityNamespaces() {
        val namespaces = executor(FakeToolAdapter()).specs.map { it.name }

        assertEquals(listOf("android", "android_ui"), namespaces)
    }

    @Test
    fun catalogDefinesExactlyTheSixBrokerCommandsWithClosedObjectSchemas() {
        val tools = AndroidDynamicToolCatalog.namespace.tools.associateBy { it.name }
        assertTrue(AndroidDynamicToolCatalog.namespace.description.contains(
            "user-authorized full access needs no extra Hans prompt",
        ))
        assertTrue(AndroidDynamicToolCatalog.namespace.description.contains("Actual Android permissions"))
        assertFalse(tools.values.any { it.description.contains("Requires user confirmation") ||
            it.description.contains("plain HTTP stays one-time") })
        assertEquals(
            setOf(
                "list_launchable_apps",
                "launch_app",
                "open_view",
                "open_settings",
                "read_battery",
                "read_network",
            ),
            tools.keys,
        )
        val schemas = tools.mapValues { JSONObject(it.value.inputSchemaJson) }
        assertTrue(schemas.values.all { schema ->
            schema.getString("type") == "object" &&
                !schema.getBoolean("additionalProperties")
        })
        assertEquals(
            setOf("packageName", "profileId"),
            schemas.getValue("launch_app").getJSONObject("properties")
                .keys().asSequence().toSet(),
        )
        assertEquals(
            listOf("packageName"),
            schemas.getValue("launch_app").getJSONArray("required")
                .let { required -> (0 until required.length()).map(required::getString) },
        )
        assertEquals(
            setOf("destination", "packageName"),
            schemas.getValue("open_settings").getJSONObject("properties")
                .keys().asSequence().toSet(),
        )
        assertEquals(
            listOf("string", "null"),
            schemas.getValue("open_settings").getJSONObject("properties")
                .getJSONObject("packageName").getJSONArray("type")
                .let { types -> (0 until types.length()).map(types::getString) },
        )
        assertTrue(
            listOf("list_launchable_apps", "read_battery", "read_network").all { name ->
                schemas.getValue(name).getJSONObject("properties").length() == 0 &&
                    schemas.getValue(name).getJSONArray("required").length() == 0
            },
        )
    }

    @Test
    fun safeBatteryAndNetworkReadsExecuteAndReturnOnlyCapabilityProjectionJson() {
        val adapter = FakeToolAdapter()
        val executor = executor(adapter)

        val battery = executor.run(call("read_battery", "call-battery"))
        val network = executor.run(call("read_network", "call-network"))

        assertTrue(battery.success)
        assertTrue(network.success)
        assertEquals(listOf("battery", "network"), adapter.calls)
        val batteryJson = JSONObject(battery.contentText)
        assertEquals("android.device.read_battery", batteryJson.getString("capabilityId"))
        assertEquals("succeeded", batteryJson.getString("status"))
        assertEquals(63, batteryJson.getJSONObject("payload").getInt("capacityPercent"))
        assertFalse(batteryJson.has("exception"))
    }

    @Test
    fun launchAppForwardsOpaqueProfileHandleAndOmissionStaysPersonal() {
        val adapter = FakeToolAdapter()
        val executor = executor(
            adapter,
            DynamicToolConfirmationProvider { request ->
                ai.hans.standard.phone.capabilities.CapabilityConfirmation(
                    request.capabilityId,
                    request.idempotencyKey,
                    request.risk,
                )
            },
        )
        val privateId = "profile_0123456789abcdef0123456789abcdef"

        assertTrue(
            executor.run(
                call(
                    "launch_app",
                    "profile-launch",
                    "{\"packageName\":\"org.example.app\",\"profileId\":\"$privateId\"}",
                ),
            ).success,
        )
        assertTrue(
            executor.run(
                call("launch_app", "personal-launch", "{\"packageName\":\"org.example.app\"}"),
            ).success,
        )

        assertEquals(
            listOf("launch:org.example.app@$privateId", "launch:org.example.app@personal"),
            adapter.calls,
        )
    }

    @Test
    fun riskyCallWithoutSeparateConfirmationIsRejectedBeforeAndroidDispatch() {
        val adapter = FakeToolAdapter()
        val output = executor(adapter).run(
            call("launch_app", "call-launch", "{\"packageName\":\"org.example.app\"}"),
        )

        assertFalse(output.success)
        assertTrue(adapter.calls.isEmpty())
        val json = JSONObject(output.contentText)
        assertEquals("rejected", json.getString("status"))
        assertEquals("confirmation_required", json.getString("errorCode"))
        assertEquals("request_not_executed", json.getJSONObject("postcondition").getString("kind"))
    }

    @Test
    fun fullAccessListsLaunchesAndOpensSettingsWithoutAdditionalHansPrompts() {
        val adapter = FakeToolAdapter()
        var prompts = 0
        val provider = SwappableDynamicToolConfirmationProvider(
            actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
        )
        provider.attach(DynamicToolConfirmationProvider { prompts += 1; null })
        val executor = executor(adapter, provider)
        val requests = listOf(
            call("list_launchable_apps", "full-list"),
            call("launch_app", "full-launch", """{"packageName":"org.example.app"}"""),
            call("open_view", "full-view", """{"uri":"https://example.com/"}"""),
            // These destinations intentionally have no durable category grant in narrower mode.
            call("open_settings", "full-accessibility", """{"destination":"accessibility"}"""),
            call("open_settings", "full-details", """{"destination":"app_details","packageName":"org.example.app"}"""),
        )

        requests.forEach { request ->
            assertTrue(request.tool, executor.run(request).success)
        }

        assertEquals(0, prompts)
        assertEquals(listOf("list", "launch:org.example.app@personal", "view",
            "settings:ACCESSIBILITY", "settings:APP_DETAILS"), adapter.calls)
    }

    @Test
    fun fullAccessDoesNotMakeUnsafeUrisOrAdditionalArgumentsValid() {
        val adapter = FakeToolAdapter()
        val provider = SwappableDynamicToolConfirmationProvider(
            actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
        )
        provider.attach(DynamicToolConfirmationProvider { error("Invalid arguments must not prompt") })
        val executor = executor(adapter, provider)
        val invalid = listOf(
            call("open_view", "full-invalid-uri", """{"uri":"file:///data/private.txt"}"""),
            call("launch_app", "full-invalid-package", """{"packageName":"not a package"}"""),
            call("open_settings", "full-forged-authority", """{"destination":"wifi","grantAll":true}"""),
        )

        invalid.forEach { request ->
            assertFalse(request.tool, executor.run(request).success)
        }
        assertTrue(adapter.calls.isEmpty())
    }

    @Test
    fun exactConfirmationHookCanAuthorizeOneUserVisibleCall() {
        val adapter = FakeToolAdapter()
        val executor = executor(
            adapter,
            DynamicToolConfirmationProvider { request ->
                assertEquals("call-launch-confirmed", request.callId)
                assertEquals(ConfirmationRisk.USER_VISIBLE, request.risk)
                assertEquals(
                    PersistentAndroidConsentScope.OPEN_APP,
                    request.persistentConsent?.scope,
                )
                ai.hans.standard.phone.capabilities.CapabilityConfirmation(
                    request.capabilityId,
                    request.idempotencyKey,
                    request.risk,
                )
            },
        )

        val output = executor.run(
            call(
                "launch_app",
                "call-launch-confirmed",
                "{\"packageName\":\"org.example.app\"}",
            ),
        )

        assertTrue(output.success)
        assertEquals(listOf("launch:org.example.app@personal"), adapter.calls)
        assertEquals("succeeded", JSONObject(output.contentText).getString("status"))
    }

    @Test
    fun everydayNavigationAndOnlyNonPrivilegedSettingsCarryDurableScopes() {
        val observed = mutableListOf<DynamicToolConfirmationRequest>()
        val confirmations = DynamicToolConfirmationProvider { request ->
            observed += request
            ai.hans.standard.phone.capabilities.CapabilityConfirmation(
                request.capabilityId,
                request.idempotencyKey,
                request.risk,
            )
        }
        val executor = executor(FakeToolAdapter(), confirmations)

        assertTrue(executor.run(call("list_launchable_apps", "apps-read")).success)
        assertTrue(
            executor.run(
                call("open_view", "https-open", "{\"uri\":\"https://example.com/maps\"}"),
            ).success,
        )
        assertTrue(
            executor.run(
                call("open_view", "geo-open", "{\"uri\":\"geo:43.2,27.9\"}"),
            ).success,
        )
        assertTrue(
            executor.run(
                call(
                    "open_view",
                    "market-open",
                    "{\"uri\":\"market://details?id=org.example.app\"}",
                ),
            ).success,
        )
        assertTrue(
            executor.run(
                call("open_view", "http-open", "{\"uri\":\"http://example.com\"}"),
            ).success,
        )
        assertTrue(
            executor.run(
                call("open_settings", "wifi-open", "{\"destination\":\"wifi\"}"),
            ).success,
        )
        assertTrue(
            executor.run(
                call(
                    "open_settings",
                    "accessibility-open",
                    "{\"destination\":\"accessibility\"}",
                ),
            ).success,
        )

        assertEquals(
            PersistentAndroidConsentScope.INSTALLED_APPS_READ,
            observed[0].persistentConsent?.scope,
        )
        assertEquals(
            PersistentAndroidConsentScope.OPEN_SAFE_NAVIGATION,
            observed[1].persistentConsent?.scope,
        )
        assertEquals(PersistentAndroidConsentScope.OPEN_SAFE_NAVIGATION, observed[2].persistentConsent?.scope)
        assertEquals(PersistentAndroidConsentScope.OPEN_SAFE_NAVIGATION, observed[3].persistentConsent?.scope)
        assertEquals(null, observed[4].persistentConsent)
        assertEquals(PersistentAndroidConsentScope.OPEN_SETTINGS_PAGE, observed[5].persistentConsent?.scope)
        assertEquals(null, observed[6].persistentConsent)
    }

    @Test
    fun callIdProvidesStableIdempotencyWithoutExposingTheRawCallId() {
        val adapter = FakeToolAdapter()
        val executor = executor(adapter)
        val first = executor.run(call("read_battery", "private-call-id"))
        val replay = executor.run(call("read_battery", "private-call-id"))

        assertEquals(listOf("battery"), adapter.calls)
        assertFalse(JSONObject(first.contentText).getBoolean("replayed"))
        assertTrue(JSONObject(replay.contentText).getBoolean("replayed"))
        assertFalse(first.contentText.contains("private-call-id"))
    }

    @Test
    fun malformedUnknownAndAdapterExceptionPathsReturnBoundedSafeFailures() {
        val adapter = FakeToolAdapter(throwOnBattery = true)
        val executor = executor(adapter)

        val malformed = executor.run(call("read_battery", "call-malformed", "{\"extra\":1}"))
        val unknown = executor.run(call("not_registered", "call-unknown"))
        val thrown = executor.run(call("read_battery", "call-thrown"))

        assertEquals("invalid_arguments", JSONObject(malformed.contentText).getString("errorCode"))
        assertEquals("unknown_dynamic_tool", JSONObject(unknown.contentText).getString("errorCode"))
        assertEquals("dynamic_tool_exception", JSONObject(thrown.contentText).getString("errorCode"))
        assertFalse(malformed.success)
        assertFalse(unknown.success)
        assertFalse(thrown.success)
    }

    @Test
    fun executorRejectionCompletesExactlyOnceWithSafeFailureProjection() {
        val adapter = FakeToolAdapter()
        val executor = AndroidDynamicToolExecutor(
            broker(adapter),
            Executor { throw IllegalStateException("private executor detail") },
        )
        var callbacks = 0
        lateinit var result: DynamicToolExecutionResult

        executor.execute(call("read_battery", "call-rejected")) {
            callbacks += 1
            result = it
        }

        assertEquals(1, callbacks)
        assertEquals("executor_rejected", JSONObject(result.contentText).getString("errorCode"))
        assertFalse(result.contentText.contains("private executor detail"))
    }

    @Test
    fun cancellationWhileQueuedPreventsConfirmationAndBrokerSideEffects() {
        val adapter = FakeToolAdapter()
        val queued = QueuedExecutorService()
        var confirmations = 0
        var callbacks = 0
        val executor = AndroidDynamicToolExecutor(
            broker(adapter),
            queued,
            DynamicToolConfirmationProvider { request ->
                confirmations += 1
                CapabilityConfirmation(request.capabilityId, request.idempotencyKey, request.risk)
            },
        )

        val handle = executor.executeCancellable(
            call("launch_app", "queued-cancel", "{\"packageName\":\"org.example.app\"}"),
            DynamicToolCancellation.NONE,
        ) { callbacks += 1 }

        assertEquals(
            DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT,
            handle.cancel(),
        )
        queued.runAll()
        assertEquals(0, confirmations)
        assertTrue(adapter.calls.isEmpty())
        assertEquals(0, callbacks)
    }

    @Test
    fun cancellationDuringConfirmationPreventsSubsequentBrokerDispatch() {
        val adapter = FakeToolAdapter()
        val cancelled = AtomicBoolean(false)
        var callbacks = 0
        val executor = executor(
            adapter,
            DynamicToolConfirmationProvider { request ->
                cancelled.set(true)
                CapabilityConfirmation(request.capabilityId, request.idempotencyKey, request.risk)
            },
        )

        val handle = executor.executeCancellable(
            call("launch_app", "confirmation-cancel", "{\"packageName\":\"org.example.app\"}"),
            DynamicToolCancellation(cancelled::get),
        ) { callbacks += 1 }

        assertEquals(
            DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED,
            handle.cancel(),
        )
        assertTrue(adapter.calls.isEmpty())
        assertEquals(0, callbacks)
    }

    @Test
    fun cancellationAfterBrokerEntrySuppressesCompletionAndReportsAmbiguousEffect() {
        val cancelled = AtomicBoolean(false)
        val adapter = FakeToolAdapter(onBattery = { cancelled.set(true) })
        var callbacks = 0
        val executor = executor(adapter)

        val handle = executor.executeCancellable(
            call("read_battery", "effect-cancel"),
            DynamicToolCancellation(cancelled::get),
        ) { callbacks += 1 }

        assertEquals(listOf("battery"), adapter.calls)
        assertEquals(0, callbacks)
        assertEquals(
            DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED,
            handle.cancel(),
        )
    }

    private fun executor(
        adapter: FakeToolAdapter,
        confirmations: DynamicToolConfirmationProvider = DynamicToolConfirmationProvider.NONE,
    ): AndroidDynamicToolExecutor = AndroidDynamicToolExecutor(
        broker(adapter),
        Executor(Runnable::run),
        confirmations,
    )

    private fun broker(adapter: FakeToolAdapter): CapabilityBroker = CapabilityBroker(
        CapabilityRegistry(FakeToolEnvironment(), clock = { 1L }),
        adapter,
    )

    private fun AndroidDynamicToolExecutor.run(call: DynamicToolCallParams): DynamicToolExecutionResult {
        var callbacks = 0
        lateinit var result: DynamicToolExecutionResult
        execute(call) {
            callbacks += 1
            result = it
        }
        assertEquals(1, callbacks)
        return result
    }

    private fun call(
        tool: String,
        callId: String,
        arguments: String = "{}",
        namespace: String? = "android",
    ): DynamicToolCallParams = DynamicToolCallParams(
        threadId = "thread-test",
        turnId = "turn-test",
        callId = callId,
        namespace = namespace,
        tool = tool,
        argumentsJson = arguments,
    )
}

private class FakeToolEnvironment : CapabilityEnvironment {
    override val apiLevel: Int = 36

    override fun hasPermission(permission: String): Boolean =
        permission == StandardCapabilities.ACCESS_NETWORK_STATE

    override fun hasSpecialAccess(access: AndroidSpecialAccess): Boolean = false
    override fun hasSystemFeature(feature: String): Boolean = true
}

private class FakeToolAdapter(
    private val throwOnBattery: Boolean = false,
    private val onBattery: (() -> Unit)? = null,
) : PublicAndroidActionAdapter {
    val calls = mutableListOf<String>()

    override fun listLaunchableApps(): AndroidAdapterResult<List<LaunchableApp>> {
        calls += "list"
        return AndroidAdapterResult.Success(
            listOf(LaunchableApp("org.example.app", "org.example.app/.Main", "Example")),
        )
    }

    override fun launchApp(packageName: String): AndroidAdapterResult<DispatchObservation> {
        calls += "launch:$packageName"
        return AndroidAdapterResult.Success(DispatchObservation(packageName, "$packageName/.Main"))
    }

    override fun launchAppInProfile(
        packageName: String,
        profileId: String?,
    ): AndroidAdapterResult<DispatchObservation> {
        calls += "launch:$packageName@${profileId ?: "personal"}"
        return AndroidAdapterResult.Success(DispatchObservation(packageName, "$packageName/.Main"))
    }

    override fun openSafeView(uri: String): AndroidAdapterResult<DispatchObservation> {
        calls += "view"
        return AndroidAdapterResult.Success(DispatchObservation("org.example.browser", null))
    }

    override fun openSettings(
        destination: SettingsDestination,
        packageName: String?,
    ): AndroidAdapterResult<DispatchObservation> {
        calls += "settings:${destination.name}"
        return AndroidAdapterResult.Success(DispatchObservation("com.android.settings", null))
    }

    override fun readBattery(): AndroidAdapterResult<CapabilityObservation.Battery> {
        calls += "battery"
        onBattery?.invoke()
        if (throwOnBattery) throw IllegalStateException("private adapter detail")
        return AndroidAdapterResult.Success(
            CapabilityObservation.Battery(63, charging = false, powerSaveMode = true, 271),
        )
    }

    override fun readNetwork(): AndroidAdapterResult<CapabilityObservation.Network> {
        calls += "network"
        return AndroidAdapterResult.Success(
            CapabilityObservation.Network(
                connected = true,
                validated = true,
                metered = false,
                transports = setOf(NetworkTransport.WIFI),
            ),
        )
    }
}

private class QueuedExecutorService : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()
    private var shutdown = false

    override fun execute(command: Runnable) {
        check(!shutdown)
        tasks.addLast(command)
    }

    override fun shutdown() {
        shutdown = true
    }

    override fun shutdownNow(): MutableList<Runnable> {
        shutdown = true
        return buildList {
            while (tasks.isNotEmpty()) add(tasks.removeFirst())
        }.toMutableList()
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()

    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}
