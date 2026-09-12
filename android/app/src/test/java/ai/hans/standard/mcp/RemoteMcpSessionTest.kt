package ai.hans.standard.mcp

import ai.hans.standard.plugins.runtime.OAuthCredentialHandle
import ai.hans.standard.plugins.runtime.RemoteMcpRequirement
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMcpSessionTest {
    @Test
    fun modernDiscoverAndCallAreStatelessCorrelatedAllowlistedAndPostconditionVerified() {
        val secret = "private-access-token"
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    modernJsonResponse(1, modernDiscoverResult()),
                    modernJsonResponse(2, toolsResult("tasks/list", "tasks.create")),
                    modernJsonResponse(3, toolResult("one")),
                    modernJsonResponse(4, toolResult("created")),
                ),
            ),
        )
        val handle = OAuthCredentialHandle("c".repeat(64))
        val definition = definition(handle)
        val vault = FakeVault(credentialIdentity(definition), secret)
        val verifiers = RemoteMcpSignedVerifierRegistry.fromSignedInAppCode(
            listOf(
                RemoteMcpSignedVerifierDefinition("tasks.create.v1") { context ->
                    assertEquals("tasks-plugin", context.pluginId)
                    assertEquals(definition.identity.configurationDigest, context.configurationDigest)
                    assertEquals("tasks.create", context.toolName)
                    RemoteMcpPostcondition.VERIFIED
                },
            ),
        )
        val policies = RemoteMcpToolPolicyRegistry(
            listOf(
                RemoteMcpToolPolicy.approved(
                    definition.identity,
                    discoveredTool("tasks/list"),
                    RemoteMcpToolEffect.READ_ONLY,
                ),
                RemoteMcpToolPolicy.approved(
                    definition.identity,
                    discoveredTool("tasks.create"),
                    RemoteMcpToolEffect.MUTATING,
                    "tasks.create.v1",
                ),
            ),
            verifiers,
        )
        val statuses = RemoteMcpPassiveStatusRegistry()
        val session = RemoteMcpSession(definition, transport, vault, policies, statuses)

        val discovery = session.discover()
        assertEquals(setOf("tasks/list", "tasks.create"), discovery.allowedToolNames)
        assertEquals(RemoteMcpPostcondition.VERIFIED, session.call("tasks/list", "{}").postcondition)
        assertTrue(session.call("tasks.create", """{"title":"x"}""").success)
        assertTrue(transport.requests.all { it.sessionId == null })
        assertTrue(
            transport.requests.all {
                it.protocolVersion == RemoteMcpProtocol.MODERN_PROTOCOL_VERSION
            },
        )
        assertEquals(
            listOf("server/discover", "tools/list", "tools/call", "tools/call"),
            transport.requests.map { it.method },
        )
        assertEquals(listOf(null, null, "tasks/list", "tasks.create"), transport.requests.map { it.name })
        transport.requests.forEach { request ->
            val metadata = JSONObject(request.body.toString(Charsets.UTF_8))
                .getJSONObject("params")
                .getJSONObject("_meta")
            assertEquals(
                RemoteMcpProtocol.MODERN_PROTOCOL_VERSION,
                metadata.getString("io.modelcontextprotocol/protocolVersion"),
            )
            assertTrue(metadata.get("io.modelcontextprotocol/clientInfo") is JSONObject)
            assertTrue(metadata.get("io.modelcontextprotocol/clientCapabilities") is JSONObject)
        }
        assertTrue(transport.tokens.all { it == secret })
        assertFalse(statuses.snapshot().toString().contains(secret))
        assertFalse(statuses.snapshot().toString().contains(handle.value))
        assertEquals(setOf("tasks/list", "tasks.create"), statuses.snapshot().single().discoveredToolNames)
    }

    @Test
    fun exactLegacyProbeFallbackKeepsHandshakeAndSessionCorrelation() {
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    legacyProbeResponse(1),
                    jsonResponse(2, initializeResult(), "legacy-session"),
                    RemoteMcpHttpResponse(202, "", ByteArray(0), "legacy-session"),
                    jsonResponse(3, toolsResult("tasks/list"), "legacy-session"),
                    jsonResponse(4, toolResult("ok"), "legacy-session"),
                ),
            ),
        )
        val definition = definition(null, allowedTools = setOf("tasks/list"))
        val session = RemoteMcpSession(
            definition,
            transport,
            NoCredentials,
            RemoteMcpToolPolicyRegistry(
                listOf(
                    RemoteMcpToolPolicy.approved(
                        definition.identity,
                        discoveredTool("tasks/list"),
                        RemoteMcpToolEffect.READ_ONLY,
                    ),
                ),
            ),
            RemoteMcpPassiveStatusRegistry(),
        )

        assertEquals(setOf("tasks/list"), session.discover().allowedToolNames)
        assertTrue(session.call("tasks/list", "{}").success)
        assertEquals(
            listOf(
                "server/discover",
                "initialize",
                "notifications/initialized",
                "tools/list",
                "tools/call",
            ),
            transport.requests.map { JSONObject(it.body.toString(Charsets.UTF_8)).getString("method") },
        )
        assertEquals(RemoteMcpProtocol.MODERN_PROTOCOL_VERSION, transport.requests[0].protocolVersion)
        assertEquals("server/discover", transport.requests[0].method)
        assertNull(transport.requests[1].protocolVersion)
        assertNull(transport.requests[1].method)
        assertTrue(
            transport.requests.drop(2).all {
                it.protocolVersion == RemoteMcpProtocol.LEGACY_PROTOCOL_VERSION &&
                    it.sessionId == "legacy-session" && it.method == null && it.name == null
            },
        )
        assertFalse(
            JSONObject(transport.requests[1].body.toString(Charsets.UTF_8))
                .getJSONObject("params")
                .has("_meta"),
        )
    }

    @Test
    fun authHttpTransportParseAndRecognizedModernErrorsNeverDowngrade() {
        val scenarios = listOf(
            RemoteMcpHttpResponse(401, "", ByteArray(0), null),
            RemoteMcpHttpResponse(403, "", ByteArray(0), null),
            RemoteMcpHttpResponse(404, "", ByteArray(0), null),
            RemoteMcpHttpResponse(405, "", ByteArray(0), null),
            RemoteMcpHttpResponse(429, "", ByteArray(0), null),
            RemoteMcpHttpResponse(500, "", ByteArray(0), null),
            RemoteMcpHttpResponse(400, "application/json", "not-json".toByteArray(), null),
            modernErrorResponse(1, -32020),
            unsupportedVersionResponse(1),
            modernJsonResponse(1, modernDiscoverResult()).copy(sessionId = "unexpected-session"),
        )
        scenarios.forEach { response ->
            val transport = ScriptedTransport(ArrayDeque(listOf(response)))
            val failure = runCatching {
                RemoteMcpSession(
                    definition(null),
                    transport,
                    NoCredentials,
                    RemoteMcpToolPolicyRegistry(emptyList()),
                    RemoteMcpPassiveStatusRegistry(),
                ).discover()
            }.exceptionOrNull()
            assertTrue(failure is RemoteMcpFailure)
            assertEquals(1, transport.requests.size)
            assertEquals("server/discover", transport.requests.single().method)
            assertFalse(failure.toString().contains("https://"))
        }
    }

    @Test
    fun recognizedModernErrorPinsEraAndRetryCannotSwitchToLegacy() {
        val transport = ScriptedTransport(
            ArrayDeque(listOf(modernErrorResponse(1, -32021), legacyProbeResponse(2))),
        )
        val session = RemoteMcpSession(
            definition(null),
            transport,
            NoCredentials,
            RemoteMcpToolPolicyRegistry(emptyList()),
            RemoteMcpPassiveStatusRegistry(),
        )
        assertTrue(runCatching { session.discover() }.isFailure)
        assertTrue(runCatching { session.discover() }.isFailure)
        assertEquals(1, transport.requests.size)
        assertEquals(RemoteMcpProtocol.MODERN_PROTOCOL_VERSION, transport.requests.single().protocolVersion)
    }

    @Test
    fun missingPolicyIsNotExposedAndMutatingToolCannotExistWithoutVerifier() {
        assertTrue(
            runCatching {
                RemoteMcpToolPolicy.approved(
                    definition(null).identity,
                    discoveredTool("tasks.create"),
                    RemoteMcpToolEffect.MUTATING,
                )
            }.isFailure,
        )
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    modernJsonResponse(1, modernDiscoverResult()),
                    modernJsonResponse(2, toolsResult("tasks/list", "unknown")),
                ),
            ),
        )
        val definition = definition(
            null,
            allowedTools = setOf("tasks/list", "unknown"),
        )
        val session = RemoteMcpSession(
            definition,
            transport,
            NoCredentials,
            RemoteMcpToolPolicyRegistry(
                listOf(
                    RemoteMcpToolPolicy.approved(
                        definition.identity,
                        discoveredTool("tasks/list"),
                        RemoteMcpToolEffect.READ_ONLY,
                    ),
                ),
            ),
            RemoteMcpPassiveStatusRegistry(),
        )
        assertEquals(setOf("tasks/list"), session.discover().allowedToolNames)
        assertEquals("mcp_tool_policy_missing", session.call("unknown", "{}").errorCode)
    }

    @Test
    fun crossCorrelationCancellationAndRemoteErrorsReturnOnlyStableCodes() {
        val transport = ScriptedTransport(
            ArrayDeque(listOf(modernJsonResponse(99, modernDiscoverResult()))),
        )
        val session = RemoteMcpSession(
            definition(null),
            transport,
            NoCredentials,
            RemoteMcpToolPolicyRegistry(emptyList()),
            RemoteMcpPassiveStatusRegistry(),
        )
        val failure = runCatching { session.discover() }.exceptionOrNull()
        assertFalse(failure.toString().contains("https://"))
        assertFalse(failure.toString().contains("token"))

        val cancelled = AtomicBoolean(true)
        val cancelFailure = runCatching {
            RemoteMcpSession(
                definition(null),
                ScriptedTransport(ArrayDeque()),
                NoCredentials,
                RemoteMcpToolPolicyRegistry(emptyList()),
                RemoteMcpPassiveStatusRegistry(),
            ).discover(RemoteMcpCancellation(cancelled::get))
        }.exceptionOrNull()
        assertTrue(cancelFailure is RemoteMcpFailure)
        assertEquals("mcp_call_cancelled", (cancelFailure as RemoteMcpFailure).code)
    }

    @Test
    fun oauthFailurePublishesOnlySanitizedPassiveState() {
        val handle = OAuthCredentialHandle("d".repeat(64))
        val statuses = RemoteMcpPassiveStatusRegistry()
        val missingVault = object : RemoteMcpOAuthVault {
            override fun state(identity: RemoteMcpOAuthCredentialIdentity) =
                OAuthCredentialState.EXPIRED
            override fun <T> withBearerToken(
                identity: RemoteMcpOAuthCredentialIdentity,
                block: (String) -> T,
            ): T =
                error("must_not_resolve")
        }
        val definition = definition(handle)
        val session = RemoteMcpSession(
            definition,
            ScriptedTransport(ArrayDeque()),
            missingVault,
            RemoteMcpToolPolicyRegistry(emptyList()),
            statuses,
        )

        assertTrue(runCatching { session.discover() }.isFailure)
        val status = statuses.snapshot().single()
        assertFalse(status.authenticated)
        assertFalse(status.discoveryProven)
        assertFalse(status.toString().contains(handle.value))
    }

    @Test
    fun transportExceptionCannotCarryBearerTokenAcrossPublicBoundary() {
        val secret = "highly-private-bearer"
        val handle = OAuthCredentialHandle("e".repeat(64))
        val throwing = RemoteMcpHttpCallFactory { _, _ ->
            throw IllegalStateException("transport failed with $secret")
        }
        val definition = definition(handle)
        val session = RemoteMcpSession(
            definition,
            throwing,
            FakeVault(credentialIdentity(definition), secret),
            RemoteMcpToolPolicyRegistry(emptyList()),
            RemoteMcpPassiveStatusRegistry(),
        )

        val failure = runCatching { session.discover() }.exceptionOrNull()
        assertTrue(failure is RemoteMcpFailure)
        assertEquals("mcp_call_failed", (failure as RemoteMcpFailure).code)
        assertFalse(failure.toString().contains(secret))
    }

    @Test
    fun policyAndPassiveStatusRemainScopedAcrossSameServerIdAndStaleConfiguration() {
        val first = definition(null, pluginId = "first-plugin")
        val second = definition(null, pluginId = "second-plugin")
        val stale = first.identity.copy(configurationDigest = "f".repeat(64))
        val tool = discoveredTool("tasks/list")
        val policy = RemoteMcpToolPolicy.approved(
            first.identity,
            tool,
            RemoteMcpToolEffect.READ_ONLY,
        )
        val policies = RemoteMcpToolPolicyRegistry(listOf(policy))
        assertEquals(RemoteMcpToolEffect.READ_ONLY, policies.resolve(first.identity, tool)?.effect)
        assertNull(policies.resolve(second.identity, tool))
        assertNull(policies.resolve(stale, tool))

        val statuses = RemoteMcpPassiveStatusRegistry()
        statuses.publish(
            RemoteMcpActivationPassiveStatus(first.identity, true, true, true, setOf("tasks/list")),
        )
        statuses.publish(
            RemoteMcpActivationPassiveStatus(second.identity, true, false, false, emptySet()),
        )
        assertEquals(2, statuses.snapshot().size)
        assertTrue(statuses.sourceFor(first.identity).snapshot().single().authenticated)
        assertFalse(statuses.sourceFor(second.identity).snapshot().single().authenticated)
        assertTrue(statuses.sourceFor(stale).snapshot().isEmpty())
    }

    @Test
    fun livePolicyRevocationStopsAnOpenSessionBeforeAnyExternalToolEffect() {
        val transport = ScriptedTransport(
            ArrayDeque(
                listOf(
                    modernJsonResponse(1, modernDiscoverResult()),
                    modernJsonResponse(2, toolsResult("tasks/list")),
                ),
            ),
        )
        val definition = definition(null, allowedTools = setOf("tasks/list"))
        val tool = discoveredTool("tasks/list")
        val resolver = MutablePolicyResolver(
            RemoteMcpToolPolicyRegistry(
                listOf(
                    RemoteMcpToolPolicy.approved(
                        definition.identity,
                        tool,
                        RemoteMcpToolEffect.READ_ONLY,
                    ),
                ),
            ),
        )
        val session = RemoteMcpSession(
            definition,
            transport,
            NoCredentials,
            resolver,
            RemoteMcpPassiveStatusRegistry(),
        )

        assertEquals(setOf("tasks/list"), session.discover().allowedToolNames)
        resolver.delegate = RemoteMcpToolPolicyRegistry(emptyList())

        val receipt = session.call("tasks/list", "{}")
        assertEquals("mcp_tool_policy_missing", receipt.errorCode)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun cancellingOneOfTwoParallelCallsDoesNotCancelTheOther() {
        val definition = definition(null)
        val transport = ParallelCallTransport()
        val session = RemoteMcpSession(
            definition,
            transport,
            NoCredentials,
            RemoteMcpToolPolicyRegistry(
                listOf(
                    RemoteMcpToolPolicy.approved(
                        definition.identity,
                        discoveredTool("tasks/list"),
                        RemoteMcpToolEffect.READ_ONLY,
                    ),
                ),
            ),
            RemoteMcpPassiveStatusRegistry(),
        )
        session.discover()
        val firstInvocation = session.beginInvocation()
        val secondInvocation = session.beginInvocation()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<RemoteMcpInvocationReceipt> {
                session.call(firstInvocation, "tasks/list", """{"call":"a"}""")
            }
            val second = executor.submit<RemoteMcpInvocationReceipt> {
                session.call(secondInvocation, "tasks/list", """{"call":"b"}""")
            }
            assertTrue(transport.parallelCallsEntered.await(2, TimeUnit.SECONDS))
            assertTrue(session.cancel(firstInvocation))
            assertTrue(transport.cancelled("a"))
            assertFalse(transport.cancelled("b"))
            transport.releaseParallelCalls.countDown()

            assertEquals("mcp_call_cancelled", first.get(2, TimeUnit.SECONDS).errorCode)
            assertTrue(second.get(2, TimeUnit.SECONDS).success)
            assertFalse(transport.cancelled("b"))
        } finally {
            transport.releaseParallelCalls.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun callerOwnedDiscoveryInvocationCancelsItsInFlightHttpCall() {
        val transport = BlockingDiscoveryTransport()
        val definition = definition(null, allowedTools = setOf("tasks/list"))
        val session = RemoteMcpSession(
            definition,
            transport,
            NoCredentials,
            RemoteMcpToolPolicyRegistry(emptyList()),
            RemoteMcpPassiveStatusRegistry(),
        )
        val invocation = session.beginInvocation()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Throwable?> {
                runCatching { session.discover(invocation) }.exceptionOrNull()
            }
            assertTrue(transport.entered.await(2, TimeUnit.SECONDS))

            assertTrue(session.cancel(invocation))
            val failure = result.get(2, TimeUnit.SECONDS)

            assertTrue(transport.cancelled.get())
            assertTrue(failure is RemoteMcpFailure)
            assertEquals("mcp_call_cancelled", (failure as RemoteMcpFailure).code)
        } finally {
            transport.release.countDown()
            executor.shutdownNow()
        }
    }

    private fun definition(
        handle: OAuthCredentialHandle?,
        pluginId: String = "tasks-plugin",
        allowedTools: Set<String> = setOf("tasks/list", "tasks.create"),
    ): RemoteMcpActivationDefinition {
        val requirement = RemoteMcpRequirement(
            "tasks",
            "https://mcp.example.com/v1",
            handle,
            allowedTools,
            true,
            10_000,
            128 * 1024,
        )
        return RemoteMcpActivationDefinition(
            RemoteMcpActivationIdentity(
                pluginId,
                requirement.id,
                RemoteMcpConfigurationIdentity.digest(requirement),
            ),
            requirement,
        )
    }

    private fun credentialIdentity(
        definition: RemoteMcpActivationDefinition,
    ): RemoteMcpOAuthCredentialIdentity = RemoteMcpOAuthCredentialIdentity(
        definition.identity.pluginId,
        definition.identity.serverId,
        definition.identity.configurationDigest,
        requireNotNull(definition.requirement.oauthHandle),
    )

    private fun initializeResult() = JSONObject()
        .put("protocolVersion", RemoteMcpProtocol.LEGACY_PROTOCOL_VERSION)
        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
        .put("serverInfo", JSONObject().put("name", "test").put("version", "1"))

    private fun modernDiscoverResult() = JSONObject()
        .put("supportedVersions", JSONArray().put(RemoteMcpProtocol.MODERN_PROTOCOL_VERSION))
        .put("capabilities", JSONObject().put("tools", JSONObject()))
        .put("ttlMs", 0)
        .put("cacheScope", "private")

    private fun toolsResult(vararg names: String) = JSONObject()
        .put(
            "tools",
            JSONArray(names.map { name ->
                JSONObject()
                    .put("name", name)
                    .put("description", "Tool $name")
                    .put("inputSchema", JSONObject().put("type", "object"))
            }),
        )
        .put("nextCursor", JSONObject.NULL)

    private fun toolResult(text: String) = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", false)

    private fun discoveredTool(name: String) = RemoteMcpTool(
        name = name,
        title = null,
        description = "Tool $name",
        inputSchemaJson = """{"type":"object"}""",
        outputSchemaJson = null,
    )

    private fun jsonResponse(id: Long, result: JSONObject, sessionId: String?) =
        RemoteMcpHttpResponse(
            200,
            "application/json",
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result)
                .toString()
                .toByteArray(),
            sessionId,
        )

    private fun modernJsonResponse(id: Long, result: JSONObject) = jsonResponse(
        id,
        JSONObject(result.toString()).apply {
            if (has("tools")) {
                put("ttlMs", 0)
                put("cacheScope", "private")
            }
            put("resultType", "complete")
        },
        null,
    )

    private fun legacyProbeResponse(id: Long) = RemoteMcpHttpResponse(
        400,
        "",
        ByteArray(0),
        null,
    )

    private fun modernErrorResponse(id: Long, code: Int) = RemoteMcpHttpResponse(
        400,
        "application/json",
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("error", JSONObject().put("code", code).put("message", "modern error"))
            .toString()
            .toByteArray(),
        null,
    )

    private fun unsupportedVersionResponse(id: Long) = RemoteMcpHttpResponse(
        400,
        "application/json",
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put(
                "error",
                JSONObject()
                    .put("code", -32022)
                    .put("message", "unsupported")
                    .put(
                        "data",
                        JSONObject()
                            .put("requested", RemoteMcpProtocol.MODERN_PROTOCOL_VERSION)
                            .put(
                                "supported",
                                JSONArray().put(RemoteMcpProtocol.LEGACY_PROTOCOL_VERSION),
                            ),
                    ),
            )
            .toString()
            .toByteArray(),
        null,
    )

    private class ScriptedTransport(
        private val responses: ArrayDeque<RemoteMcpHttpResponse>,
    ) : RemoteMcpHttpCallFactory {
        val requests = mutableListOf<RemoteMcpHttpRequest>()
        val tokens = mutableListOf<String?>()

        override fun create(request: RemoteMcpHttpRequest, bearerToken: String?): RemoteMcpHttpCall {
            requests += request
            tokens += bearerToken
            return object : RemoteMcpHttpCall {
                override fun execute(): RemoteMcpHttpResponse = responses.removeFirst()
                override fun cancel() = Unit
                override fun close() = Unit
            }
        }
    }

    private class MutablePolicyResolver(
        @Volatile var delegate: RemoteMcpToolPolicyResolver,
    ) : RemoteMcpToolPolicyResolver {
        override fun resolve(
            activationIdentity: RemoteMcpActivationIdentity,
            tool: RemoteMcpTool,
        ): RemoteMcpResolvedToolPolicy? = delegate.resolve(activationIdentity, tool)
    }

    private inner class ParallelCallTransport : RemoteMcpHttpCallFactory {
        val parallelCallsEntered = CountDownLatch(2)
        val releaseParallelCalls = CountDownLatch(1)
        private val cancelled = ConcurrentHashMap<String, AtomicBoolean>()

        override fun create(request: RemoteMcpHttpRequest, bearerToken: String?): RemoteMcpHttpCall {
            val message = JSONObject(request.body.toString(Charsets.UTF_8))
            val method = message.optString("method")
            val id = message.optLong("id", -1L)
            val callName = message.optJSONObject("params")
                ?.optJSONObject("arguments")
                ?.optString("call")
                ?.takeIf(String::isNotEmpty)
            val cancelledFlag = callName?.let { cancelled.computeIfAbsent(it) { AtomicBoolean() } }
            return object : RemoteMcpHttpCall {
                override fun execute(): RemoteMcpHttpResponse = when (method) {
                    "server/discover" -> modernJsonResponse(id, modernDiscoverResult())
                    "tools/list" -> modernJsonResponse(
                        id,
                        toolsResult("tasks/list"),
                    )
                    "tools/call" -> {
                        parallelCallsEntered.countDown()
                        check(releaseParallelCalls.await(2, TimeUnit.SECONDS))
                        if (cancelledFlag?.get() == true) throw IllegalStateException("cancelled")
                        modernJsonResponse(id, toolResult(callName ?: "ok"))
                    }
                    else -> error("unexpected method $method")
                }

                override fun cancel() {
                    cancelledFlag?.set(true)
                }

                override fun close() = Unit
            }
        }

        fun cancelled(call: String): Boolean = cancelled[call]?.get() == true
    }

    private inner class BlockingDiscoveryTransport : RemoteMcpHttpCallFactory {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)

        override fun create(
            request: RemoteMcpHttpRequest,
            bearerToken: String?,
        ): RemoteMcpHttpCall = object : RemoteMcpHttpCall {
            override fun execute(): RemoteMcpHttpResponse {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                return modernJsonResponse(1, modernDiscoverResult())
            }

            override fun cancel() {
                cancelled.set(true)
                release.countDown()
            }

            override fun close() = Unit
        }
    }

    private class FakeVault(
        private val expected: RemoteMcpOAuthCredentialIdentity,
        private val secret: String,
    ) : RemoteMcpOAuthVault {
        override fun state(identity: RemoteMcpOAuthCredentialIdentity): OAuthCredentialState {
            assertEquals(expected, identity)
            return OAuthCredentialState.AVAILABLE
        }

        override fun <T> withBearerToken(
            identity: RemoteMcpOAuthCredentialIdentity,
            block: (String) -> T,
        ): T {
            assertEquals(expected, identity)
            return block(secret)
        }
    }

    private object NoCredentials : RemoteMcpOAuthVault {
        override fun state(identity: RemoteMcpOAuthCredentialIdentity) = OAuthCredentialState.MISSING
        override fun <T> withBearerToken(
            identity: RemoteMcpOAuthCredentialIdentity,
            block: (String) -> T,
        ): T =
            error("credential_not_configured")
    }
}
