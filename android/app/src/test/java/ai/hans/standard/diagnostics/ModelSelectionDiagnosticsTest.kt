package ai.hans.standard.diagnostics

import ai.hans.standard.codex.*
import ai.hans.standard.integration.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelSelectionDiagnosticsTest {
    @Test
    fun passiveMissingSnapshotIsUnknownRatherThanReadyOrInactive() {
        val result = JSONObject(ModelSelectionDiagnostics.encode(null))
        assertEquals("hans-model-selection-v1", result.getString("schema"))
        assertFalse(result.getBoolean("snapshotAvailable"))
        listOf("runtimePhase", "sessionPhase", "accountPhase", "activeTurn", "effectiveSelection")
            .forEach { assertTrue(result.isNull(it)) }
        assertEquals(0, result.getJSONArray("models").length())
    }

    @Test
    fun pendingSettingsDoNotMasqueradeAsTurnOrConfirmedSelection() {
        val result = JSONObject(ModelSelectionDiagnostics.encode(client().copy(
            pendingSettingsSelection = DispatchSelection("gpt-6-astra", ReasoningEffort.HIGH),
        )))
        assertEquals("gpt-6-astra", result.getJSONObject("pendingSettingsSelection").getString("model"))
        assertEquals("high", result.getJSONObject("pendingSettingsSelection").getString("effort"))
        assertTrue(result.isNull("pendingSelection"))
        assertEquals("gpt-5.6-luna", result.getJSONObject("confirmedSelection").getString("model"))
    }

    @Test
    fun exposesExactCatalogCapabilitiesAndSeparatesPendingFromEffectiveSelection() {
        val pending = DispatchSelection("gpt-6-astra", ReasoningEffort.MEDIUM)
        val confirmed = DispatchSelection("gpt-5.6-luna", ReasoningEffort.HIGH)
        val state = client().copy(
            models = listOf(model()),
            pendingSelection = pending,
            confirmedSelection = confirmed,
            migrationReadiness = CodexMigrationReadiness(
                accountReadComplete = true,
                threadResumeConfirmed = true,
                memoryModeEnabledAck = true,
                activeTurn = true,
                effectiveSelection = confirmed,
            ),
        )
        val result = JSONObject(ModelSelectionDiagnostics.encode(state))
        assertEquals("READY", result.getString("runtimePhase"))
        assertEquals("gpt-6-astra", result.getJSONObject("pendingSelection").getString("model"))
        assertEquals("gpt-5.6-luna", result.getJSONObject("confirmedSelection").getString("model"))
        assertEquals("high", result.getJSONObject("effectiveSelection").getString("effort"))
        assertTrue(result.getBoolean("activeTurn"))
        val astra = result.getJSONArray("models").getJSONObject(0)
        assertEquals("catalog-astra", astra.getString("catalogId"))
        assertEquals("gpt-6-astra", astra.getString("wireModel"))
        assertEquals("medium", astra.getString("defaultEffort"))
        assertEquals("priority", astra.getJSONArray("serviceTiers").getString(0))
        assertTrue(astra.getBoolean("hansSelectable"))
    }

    @Test
    fun distinguishesHiddenMissingAndUnsupportedEffortWithoutInventingAvailability() {
        val astra = model()
        val variants = listOf(
            astra.copy(hidden = true),
            astra.copy(wireModel = "future-model"),
            astra.copy(defaultEffort = ReasoningEffort.MINIMAL, supportedEfforts = setOf(ReasoningEffort.MINIMAL)),
        )
        variants.forEach { unavailable ->
            val result = JSONObject(ModelSelectionDiagnostics.encode(client().copy(models = listOf(unavailable))))
            assertFalse(result.getJSONArray("models").getJSONObject(0).getBoolean("hansSelectable"))
        }
        assertEquals(0, JSONObject(ModelSelectionDiagnostics.encode(client())).getInt("catalogModelCount"))
    }

    @Test
    fun excludesAllPrivateSessionLoginTimelineAndTokenData() {
        val base = client()
        val state = base.copy(
            models = listOf(model()),
            session = base.session.copy(
                account = base.session.account.copy(
                    identity = AccountIdentity.ChatGpt("private-email@example.invalid", "private-plan"),
                    pendingLoginId = "private-login-id", error = "private-account-error",
                ),
                threads = listOf(ThreadUiSnapshot(
                    threadId = "secret-thread", name = "private-thread-name", preview = "private-preview",
                    runtimeStatus = ThreadStatusSnapshot(ThreadRuntimeStatus.ACTIVE), effectiveOptions = null,
                    currentTurn = TurnUiSnapshot("secret-turn", TurnStatus.IN_PROGRESS, "private-turn-error"),
                    messages = emptyList(), tools = emptyList(),
                    tokenUsage = ThreadTokenUsageSnapshot(
                        last = TokenUsageBreakdown(912345, 0, 0, 0, 912345),
                        total = TokenUsageBreakdown(912345, 0, 0, 0, 912345), modelContextWindow = 400_000,
                    ),
                )),
            ),
            deviceCodeLogin = DeviceCodeLoginUi("secret-code", "https://secret-login.example"),
            outboundTimeline = listOf(OutboundUserMessageUi(
                "secret-message-id", "secret-thread", "private-user-text", OutboundMessageStatus.PENDING, false,
            )),
            timeline = listOf(ClientTimelineItem(
                "secret-timeline-id", ClientTimelineRole.USER, "private-timeline-text", 1, 1, true,
                ClientTimelineStatus.COMPLETE, turnId = "secret-turn",
            )),
        )
        val encoded = ModelSelectionDiagnostics.encode(state)
        listOf(
            "secret-code", "secret-login", "secret-message-id", "secret-thread", "private-user-text",
            "secret-timeline-id", "private-timeline-text", "secret-turn", "private-model-description",
            "private-tier-description", "private-model-display-name", "private-tier-name", "tokenUsage",
            "private-email", "private-plan", "private-login-id", "private-account-error",
            "private-thread-name", "private-preview", "private-turn-error", "912345",
        ).forEach { assertFalse("Unexpected private field: $it", encoded.contains(it)) }
    }

    @Test
    fun capsCatalogAndCapabilitiesAndEntireUtf8Payload() {
        val manyEfforts = (0..31).map { ReasoningEffort.of("effort-$it-" + "x".repeat(115)) }.toSet()
        val manyTiers = (0..31).map { ModelServiceTier("tier-$it-" + "x".repeat(117), "private-tier-name", "private-tier-description") }
        val manyModels = (0..80).map { index ->
            model().copy(
                catalogId = "catalog-$index-" + "x".repeat(115),
                wireModel = "model-$index-" + "x".repeat(117),
                defaultEffort = manyEfforts.first(), supportedEfforts = manyEfforts,
                defaultServiceTier = manyTiers.first().id, serviceTiers = manyTiers,
            )
        }
        val encoded = ModelSelectionDiagnostics.encode(client().copy(models = manyModels))
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= ModelSelectionDiagnostics.MAX_ENCODED_BYTES)
        val result = JSONObject(encoded)
        val visibleModels = result.getJSONArray("models")
        assertTrue(visibleModels.length() <= ModelSelectionDiagnostics.MAX_MODEL_ENTRIES)
        assertEquals(81 - visibleModels.length(), result.getInt("catalogModelsOmitted"))
        assertEquals(16, visibleModels.getJSONObject(0).getJSONArray("supportedEfforts").length())
        assertEquals(16, visibleModels.getJSONObject(0).getInt("supportedEffortsOmitted"))
        assertEquals(16, visibleModels.getJSONObject(0).getJSONArray("serviceTiers").length())
        assertEquals(16, visibleModels.getJSONObject(0).getInt("serviceTiersOmitted"))
    }

    private fun model() = CodexModel(
        catalogId = "catalog-astra", wireModel = "gpt-6-astra", displayName = "private-model-display-name",
        description = "private-model-description", hidden = false, isDefault = false,
        defaultEffort = ReasoningEffort.MEDIUM, supportedEfforts = setOf(ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
        defaultServiceTier = null,
        serviceTiers = listOf(ModelServiceTier("priority", "private-tier-name", "private-tier-description")),
    )

    private fun client() = CodexClientSnapshot(
        runtimePhase = ClientRuntimePhase.READY, sessionPhase = ClientSessionPhase.READY, generation = 7,
        session = SessionUiSnapshot(
            account = AccountUiSnapshot(AccountPhase.SIGNED_IN, null, null, null, null, null),
            currentThreadId = "secret-thread", threads = emptyList(),
            delivery = DeliveryUiSnapshot(7, 3, null, false),
        ),
        models = emptyList(), deviceCodeLogin = null, outboundTimeline = emptyList(), timeline = emptyList(),
        pendingSelection = null, confirmedSelection = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MEDIUM),
        problem = null,
    )
}
