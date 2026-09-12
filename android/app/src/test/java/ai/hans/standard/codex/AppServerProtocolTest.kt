package ai.hans.standard.codex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppServerProtocolTest {
    @Test
    fun contractIsPinnedToTheRuntimeSchemaVersion() {
        assertEquals("0.154.0", CodexProtocolContract.APP_SERVER_VERSION)
        assertEquals("v2", CodexProtocolContract.SCHEMA_GENERATION_MODE)
        assertEquals(DispatchOptions.ASTRA_MEDIUM, DispatchOptions.DEFAULT)
        assertEquals("gpt-6-astra", DispatchOptions.DEFAULT.model)
        assertEquals(ReasoningEffort.MEDIUM, DispatchOptions.DEFAULT.effort)
        assertEquals("gpt-5.6-terra", DispatchOptions.TERRA_MAX.model)
        assertEquals(ApprovalPolicy.NEVER, DispatchOptions.DEFAULT.approvalPolicy)
        assertEquals(
            DispatchSandbox.DANGER_FULL_ACCESS,
            DispatchOptions.DEFAULT.sandbox,
        )
        assertEquals(CodexServiceTier.STANDARD, DispatchOptions.DEFAULT.serviceTier)
    }

    @Test
    fun initializeUsesPinnedV2EnvelopeShape() {
        val request = AppServerRequests.initialize(
            id = RequestId.Number(1),
            clientVersion = "0.1.0",
        )
        val json = JSONObject(request.json)

        assertEquals("initialize", json.getString("method"))
        assertEquals(1L, json.getLong("id"))
        assertFalse(json.has("jsonrpc"))
        assertEquals(
            "hans-android",
            json.getJSONObject("params").getJSONObject("clientInfo").getString("name"),
        )
        assertTrue(
            json.getJSONObject("params")
                .getJSONObject("capabilities")
                .getBoolean("experimentalApi"),
        )
        assertEquals(
            listOf("app/list/updated"),
            json.getJSONObject("params")
                .getJSONObject("capabilities")
                .getJSONArray("optOutNotificationMethods")
                .let { methods -> (0 until methods.length()).map(methods::getString) },
        )
    }

    @Test
    fun deviceCodeLoginNeverContainsAnApiKey() {
        val request = AppServerRequests.deviceCodeLogin(RequestId.Number(2))
        val json = JSONObject(request.json)

        assertEquals("account/login/start", json.getString("method"))
        assertEquals(
            "chatgptDeviceCode",
            json.getJSONObject("params").getString("type"),
        )
        assertFalse(request.json.contains("apiKey", ignoreCase = true))
        assertFalse(request.json.contains("accessToken", ignoreCase = true))
    }

    @Test
    fun turnStartCarriesTheActuallySelectedModelEffortAndTier() {
        val selected = DispatchOptions(
            model = "gpt-5.6-sol",
            effort = ReasoningEffort.ULTRA,
            serviceTier = "priority",
            approvalPolicy = ApprovalPolicy.ON_REQUEST,
            permissionsProfile = "standard-granted",
            personality = Personality.FRIENDLY,
            reasoningSummary = ReasoningSummary.CONCISE,
        )

        val request = AppServerRequests.turnStart(
            id = RequestId.Number(3),
            threadId = "019f7aed-b57a-7d72-ad9f-91075ef1332b",
            input = listOf(CodexInput.Text("Hallo Hans")),
            options = selected,
            clientUserMessageId = "local-message-1",
        )
        val params = JSONObject(request.json).getJSONObject("params")

        assertEquals("gpt-5.6-sol", params.getString("model"))
        assertEquals("ultra", params.getString("effort"))
        assertEquals("priority", params.getString("serviceTier"))
        assertEquals("on-request", params.getString("approvalPolicy"))
        assertEquals("standard-granted", params.getString("permissions"))
        assertEquals("friendly", params.getString("personality"))
        assertEquals("concise", params.getString("summary"))
        assertFalse(params.has("sandboxPolicy"))
        assertEquals(
            "Hallo Hans",
            params.getJSONArray("input").getJSONObject(0).getString("text"),
        )
    }

    @Test
    fun standardTurnStartCarriesExplicitDefaultTierInsteadOfOmittingIt() {
        val request = AppServerRequests.turnStart(
            id = RequestId.Number(33),
            threadId = "thread-standard",
            input = listOf(CodexInput.Text("Normal")),
            options = DispatchOptions.DEFAULT,
        )

        val params = JSONObject(request.json).getJSONObject("params")
        assertTrue(params.has("serviceTier"))
        assertEquals(CodexServiceTier.STANDARD, params.getString("serviceTier"))
    }

    @Test
    fun untrustedContextUsesTextWireShapeWithoutBecomingAUserTextType() {
        val input = CodexInput.UntrustedContext("External data only")

        assertFalse(input is CodexInput.Text)
        assertEquals("text", input.toJson().getString("type"))
        assertEquals("External data only", input.toJson().getString("text"))
    }

    @Test
    fun skillInputUsesTheAppServerSkillWireShape() {
        val input = CodexInput.Skill(
            name = "hans-setup:setup-hans-device",
            absolutePath = "/data/user/0/ai.hans.standard/files/setup/SKILL.md",
        )

        assertEquals("skill", input.toJson().getString("type"))
        assertEquals("hans-setup:setup-hans-device", input.toJson().getString("name"))
        assertEquals(
            "/data/user/0/ai.hans.standard/files/setup/SKILL.md",
            input.toJson().getString("path"),
        )
    }

    @Test
    fun threadStartDisablesSilentProviderFallback() {
        val request = AppServerRequests.threadStart(
            id = RequestId.Number(4),
            options = DispatchOptions.SOL_ULTRA,
            developerInstructions = "Antworte natuerlich und fuer Sprachausgabe.",
        )
        val params = JSONObject(request.json).getJSONObject("params")

        assertEquals("gpt-5.6-sol", params.getString("model"))
        assertEquals(CodexServiceTier.STANDARD, params.getString("serviceTier"))
        assertEquals("never", params.getString("approvalPolicy"))
        assertEquals("danger-full-access", params.getString("sandbox"))
        assertFalse(params.has("permissions"))
        assertFalse(params.getBoolean("allowProviderModelFallback"))
        assertFalse(params.has("effort")) // v2 ThreadStartParams has no effort field.
        assertEquals("ultra", params.getJSONObject("config").getString("model_reasoning_effort"))
    }

    @Test
    fun threadResumeRefreshesCurrentDeveloperInstructionsWithoutTouchingLogin() {
        val request = AppServerRequests.threadResume(
            id = RequestId.Number(41),
            threadId = "thread-existing",
            developerInstructions = "Aktuelle Hans- und Profildaten.",
        )
        val params = JSONObject(request.json).getJSONObject("params")

        assertEquals("thread-existing", params.getString("threadId"))
        assertTrue(params.getBoolean("excludeTurns"))
        assertEquals(
            "summary",
            params.getJSONObject("initialTurnsPage").getString("itemsView"),
        )
        assertEquals(
            ProtocolLimits.RECENT_HISTORY_TURN_LIMIT,
            params.getJSONObject("initialTurnsPage").getInt("limit"),
        )
        val context = request.context as RequestContext.ThreadResume
        assertEquals(
            ProtocolLimits.RECENT_HISTORY_TURN_LIMIT,
            context.requestedInitialTurnsLimit,
        )
        assertEquals(
            "Aktuelle Hans- und Profildaten.",
            params.getString("developerInstructions"),
        )
        assertFalse(request.json.contains("accessToken", ignoreCase = true))
        assertFalse(request.json.contains("apiKey", ignoreCase = true))
    }

    @Test
    fun persistedPersonalThreadMemoryIsEnabledThroughThePinnedRpc() {
        val request = AppServerRequests.threadMemoryModeSetEnabled(
            id = RequestId.Number(41),
            threadId = "019f7aed-b57a-7d72-ad9f-91075ef1332b",
        )
        val json = JSONObject(request.json)

        assertEquals("thread/memoryMode/set", json.getString("method"))
        assertEquals(41L, json.getLong("id"))
        assertEquals(
            "019f7aed-b57a-7d72-ad9f-91075ef1332b",
            json.getJSONObject("params").getString("threadId"),
        )
        assertEquals("enabled", json.getJSONObject("params").getString("mode"))

        val correlator = ResponseCorrelator()
        correlator.register(request)
        val response = correlator.accept("""{"id":41,"result":{}}""")
        assertTrue(response is CorrelatedResponse.Success)
        assertTrue((response as CorrelatedResponse.Success).result === ThreadMemoryModeSetResult)

        val malformedReceipt = ResponseCorrelator()
        malformedReceipt.register(request)
        assertThrows(MalformedEnvelopeException::class.java) {
            malformedReceipt.accept(
                """{"id":41,"result":{"mode":"enabled"}}""",
            )
        }
    }

    @Test
    fun defaultTurnStartUsesPinnedDangerFullAccessPolicyAndNeverApproval() {
        val request = AppServerRequests.turnStart(
            id = RequestId.Number(5),
            threadId = "thread-yolo",
            input = listOf(CodexInput.Text("Test")),
            options = DispatchOptions.DEFAULT,
        )
        val params = JSONObject(request.json).getJSONObject("params")

        assertEquals("never", params.getString("approvalPolicy"))
        assertEquals(
            "dangerFullAccess",
            params.getJSONObject("sandboxPolicy").getString("type"),
        )
        assertEquals(1, params.getJSONObject("sandboxPolicy").length())
        assertFalse(params.has("permissions"))
    }

    @Test
    fun parsesInitializeAccountAndDeviceCodeResults() {
        val correlator = ResponseCorrelator()
        val initialize = AppServerRequests.initialize(
            RequestId.Number(10),
            clientVersion = "0.1.0",
        )
        correlator.register(initialize)
        val initialized = correlator.accept(
            """{"id":10,"result":{"userAgent":"hans/0.1","codexHome":"/data/user/0/ai.hans.standard/no_backup/codex","platformFamily":"unix","platformOs":"linux"}}""",
        ) as CorrelatedResponse.Success
        assertEquals(
            "/data/user/0/ai.hans.standard/no_backup/codex",
            (initialized.result as InitializeResult).codexHome,
        )

        val accountRead = AppServerRequests.accountRead(RequestId.Number(11))
        correlator.register(accountRead)
        val account = correlator.accept(
            """{"id":11,"result":{"account":{"type":"chatgpt","email":"person@example.test","planType":"pro"},"requiresOpenaiAuth":true}}""",
        ) as CorrelatedResponse.Success
        val accountResult = account.result as AccountReadResult
        assertEquals(
            "person@example.test",
            (accountResult.account as AccountIdentity.ChatGpt).email,
        )
        assertTrue(accountResult.requiresOpenAiAuth)

        val login = AppServerRequests.deviceCodeLogin(RequestId.Number(12))
        correlator.register(login)
        val deviceCode = correlator.accept(
            """{"id":12,"result":{"type":"chatgptDeviceCode","loginId":"login-1","userCode":"ABCD-EFGH","verificationUrl":"https://auth.openai.test/device"}}""",
        ) as CorrelatedResponse.Success
        assertEquals("ABCD-EFGH", (deviceCode.result as DeviceCodeLoginResult).userCode)
    }

    @Test
    fun parsesAdvertisedModelsAndRejectsUnsupportedSelections() {
        val correlator = ResponseCorrelator()
        val request = AppServerRequests.modelList(RequestId.Number(20))
        correlator.register(request)
        val response = correlator.accept(
            """
            {
              "id":20,
              "result":{
                "data":[
                  {
                    "id":"gpt-5.6-luna",
                    "model":"gpt-5.6-luna",
                    "displayName":"GPT-5.6 Luna",
                    "description":"Fast",
                    "hidden":false,
                    "isDefault":true,
                    "defaultReasoningEffort":"max",
                    "supportedReasoningEfforts":[
                      {"reasoningEffort":"medium","description":"Medium"},
                      {"reasoningEffort":"max","description":"Max"}
                    ],
                    "defaultServiceTier":"priority",
                    "serviceTiers":[
                      {"id":"priority","name":"Priority","description":"Fast queue"}
                    ]
                  },
                  {
                    "id":"gpt-5.6-sol",
                    "model":"gpt-5.6-sol",
                    "displayName":"GPT-5.6 Sol",
                    "description":"Deep",
                    "hidden":false,
                    "isDefault":false,
                    "defaultReasoningEffort":"ultra",
                    "supportedReasoningEfforts":[
                      {"reasoningEffort":"max","description":"Max"},
                      {"reasoningEffort":"ultra","description":"Ultra"}
                    ],
                    "serviceTiers":[]
                  }
                ],
                "nextCursor":null
              }
            }
            """.trimIndent(),
        ) as CorrelatedResponse.Success
        val result = response.result as ModelListResult

        assertEquals(2, result.catalog.models.size)
        assertNull(result.nextCursor)
        assertEquals(
            "gpt-5.6-sol",
            result.catalog.requireSupported(DispatchOptions.SOL_ULTRA).wireModel,
        )
        assertEquals(
            "gpt-5.6-luna",
            result.catalog.requireSupported(
                DispatchOptions(
                    "gpt-5.6-luna",
                    ReasoningEffort.MAX,
                    CodexServiceTier.FAST,
                ),
            ).wireModel,
        )
        assertThrows(UnsupportedProtocolValueException::class.java) {
            result.catalog.requireSupported(
                DispatchOptions(
                    "gpt-5.6-luna",
                    ReasoningEffort.MAX,
                    "flex",
                ),
            )
        }
        assertThrows(UnsupportedProtocolValueException::class.java) {
            result.catalog.requireSupported(
                DispatchOptions("gpt-5.6-luna", ReasoningEffort.ULTRA),
            )
        }
        assertThrows(UnsupportedProtocolValueException::class.java) {
            result.catalog.requireSupported(
                DispatchOptions("gpt-5.6-terra", ReasoningEffort.MAX),
            )
        }
    }

    @Test
    fun correlatedTurnStartProvesRequestedOptionsAsEffective() {
        val correlator = ResponseCorrelator()
        val request = AppServerRequests.turnStart(
            id = RequestId.Number(30),
            threadId = "thread-a",
            input = listOf(CodexInput.Text("Test")),
            options = DispatchOptions.DEFAULT,
        )
        correlator.register(request)

        val response = correlator.accept(
            """{"id":30,"result":{"turn":{"id":"turn-a","status":"inProgress","items":[]}}}""",
        ) as CorrelatedResponse.Success
        val result = response.result as TurnStartResult

        assertEquals(DispatchOptions.DEFAULT, result.effectiveOptions)
        assertEquals("gpt-6-astra", result.asActiveTurn().effectiveOptions.model)
        assertEquals(ReasoningEffort.MEDIUM, result.asActiveTurn().effectiveOptions.effort)
    }

    @Test
    fun threadStartExposesServerEffectiveSettingsInsteadOfUiPreference() {
        val correlator = ResponseCorrelator()
        val request = AppServerRequests.threadStart(
            id = RequestId.Number(31),
            options = DispatchOptions.DEFAULT,
        )
        correlator.register(request)

        val response = correlator.accept(
            """
            {
              "id":31,
              "result":{
                "thread":{"id":"thread-runtime"},
                "model":"gpt-5.6-sol",
                "reasoningEffort":"ultra",
                "serviceTier":"priority"
              }
            }
            """.trimIndent(),
        ) as CorrelatedResponse.Success
        val result = response.result as ThreadStartResult

        assertEquals("gpt-6-astra", result.requestedOptions.model)
        assertEquals("gpt-5.6-sol", result.effectiveModel)
        assertEquals(ReasoningEffort.ULTRA, result.effectiveEffort)
        assertEquals("priority", result.effectiveServiceTier)
    }

    @Test
    fun unknownAndDuplicateResponseIdsAreRejected() {
        val correlator = ResponseCorrelator()
        val request = AppServerRequests.accountRead(RequestId.Number(40))
        correlator.register(request)

        assertThrows(CrossCorrelationException::class.java) {
            correlator.accept(
                """{"id":41,"result":{"account":null,"requiresOpenaiAuth":true}}""",
            )
        }
        assertEquals(1, correlator.pendingCount())

        correlator.accept(
            """{"id":40,"result":{"account":null,"requiresOpenaiAuth":true}}""",
        )
        assertEquals(0, correlator.pendingCount())
        assertThrows(CrossCorrelationException::class.java) {
            correlator.accept(
                """{"id":40,"result":{"account":null,"requiresOpenaiAuth":true}}""",
            )
        }
    }

    @Test
    fun numericAndTextRequestIdsCannotCrossCorrelate() {
        val correlator = ResponseCorrelator()
        correlator.register(AppServerRequests.accountRead(RequestId.Text("70")))

        assertThrows(CrossCorrelationException::class.java) {
            correlator.accept(
                """{"id":70,"result":{"account":null,"requiresOpenaiAuth":true}}""",
            )
        }
        val accepted = correlator.accept(
            """{"id":"70","result":{"account":null,"requiresOpenaiAuth":true}}""",
        )
        assertTrue(accepted is CorrelatedResponse.Success)
    }

    @Test
    fun malformedResponseDoesNotConsumePendingCorrelation() {
        val correlator = ResponseCorrelator()
        correlator.register(AppServerRequests.accountRead(RequestId.Number(50)))

        assertThrows(MalformedEnvelopeException::class.java) {
            correlator.accept(
                """{"id":50,"result":{},"error":{"code":-1,"message":"bad"}}""",
            )
        }
        assertEquals(1, correlator.pendingCount())
        correlator.accept(
            """{"id":50,"result":{"account":null,"requiresOpenaiAuth":true}}""",
        )
        assertEquals(0, correlator.pendingCount())
    }

    @Test
    fun wrongSteerTurnIdIsRejectedAsCrossCorrelation() {
        val active = ActiveTurn("thread-a", "turn-a", DispatchOptions.SOL_ULTRA)
        val request = AppServerRequests.turnSteer(
            id = RequestId.Number(60),
            activeTurn = active,
            input = listOf(CodexInput.Text("Neue Nachricht")),
        )
        val correlator = ResponseCorrelator()
        correlator.register(request)

        assertThrows(CrossCorrelationException::class.java) {
            correlator.accept("""{"id":60,"result":{"turnId":"turn-b"}}""")
        }
        assertEquals(1, correlator.pendingCount())
        val response = correlator.accept(
            """{"id":60,"result":{"turnId":"turn-a"}}""",
        ) as CorrelatedResponse.Success
        assertEquals("turn-a", (response.result as TurnSteerResult).turnId)
    }

    @Test
    fun eventsCannotMasqueradeAsResponses() {
        val event = AppServerEventDecoder.decode(
            """{"method":"turn/completed","params":{"threadId":"thread-a","turn":{"id":"turn-a","items":[],"status":"completed"}}}""",
        ) as ServerEvent.TurnCompleted
        assertEquals("turn/completed", event.method)
        assertEquals(TurnStatus.COMPLETED, event.turn.status)

        assertThrows(MalformedEnvelopeException::class.java) {
            AppServerEventDecoder.decode(
                """{"id":99,"method":"turn/completed","params":{}}""",
            )
        }
    }

    @Test
    fun oversizedInputsAreRejectedBeforeEncoding() {
        val oversized = "a".repeat(ProtocolLimits.MAX_INPUT_TEXT_BYTES + 1)

        assertThrows(FrameLimitException::class.java) {
            CodexInput.Text(oversized)
        }
    }
}
