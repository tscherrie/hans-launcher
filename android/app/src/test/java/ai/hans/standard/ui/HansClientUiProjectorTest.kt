package ai.hans.standard.ui

import ai.hans.standard.localization.TestResourceTextResolver
import java.util.Locale

import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.codex.AccountUiSnapshot
import ai.hans.standard.codex.DeliveryUiSnapshot
import ai.hans.standard.codex.DispatchOptions
import ai.hans.standard.codex.CodexModel
import ai.hans.standard.codex.ModelServiceTier
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.codex.SessionUiSnapshot
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.ClientWorkInterruptPhase
import ai.hans.standard.integration.ClientWorkInterruptSnapshot
import ai.hans.standard.integration.ClientTimelineItem
import ai.hans.standard.integration.ClientTimelineRole
import ai.hans.standard.integration.ClientTimelineStatus
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.integration.CodexClientProblem
import ai.hans.standard.integration.ClientProblemCode
import ai.hans.standard.network.InternetSnapshot
import ai.hans.standard.network.InternetStatus
import ai.hans.standard.integration.DispatchSelection
import ai.hans.standard.integration.latestVisibleChatTimelineId
import ai.hans.standard.voice.realtime.LiveVoiceVoiceResolution
import ai.hans.standard.voice.realtime.LiveVoiceVoiceSelection
import ai.hans.standard.plugins.PluginAuthPolicy
import ai.hans.standard.plugins.PluginAvailability
import ai.hans.standard.plugins.PluginCard
import ai.hans.standard.plugins.PluginCatalogPhase
import ai.hans.standard.plugins.PluginConnectionActionKind
import ai.hans.standard.plugins.PluginConnectionActionSnapshot
import ai.hans.standard.plugins.PluginDomainSnapshot
import ai.hans.standard.plugins.PluginDetailSnapshot
import ai.hans.standard.plugins.PluginHandle
import ai.hans.standard.plugins.PluginHookSummary
import ai.hans.standard.plugins.PluginAppSummary
import ai.hans.standard.plugins.PluginInstallPolicy
import ai.hans.standard.plugins.PluginOperationKind
import ai.hans.standard.plugins.PluginOperationSnapshot
import ai.hans.standard.plugins.PluginOperationStatus
import ai.hans.standard.plugins.PluginSkillSummary
import ai.hans.standard.plugins.PluginSourceKind
import ai.hans.standard.settings.HansSettings
import ai.hans.standard.phone.keys.ActionKeyTrigger
import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HansClientUiProjectorTest {
    private val localizationText by lazy { TestResourceTextResolver(Locale.GERMAN) }

    @Test fun dictationMuteRemainsSeparateFromPhonePresentationWhileConnectingAndWorking() {
        val local = HansLocalUiState(dictationStatus = DictationUiStatus.LISTENING,
            dictationInputMuted = true, liveVoiceInputMuted = false)
        listOf(null, snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.BUSY)).forEach { client ->
            val chat = HansClientUiProjector.project(client, local, HansSettings(), localizationText).chat
            assertTrue(chat.dictationInputMuted)
            assertFalse(chat.liveVoiceInputMuted)
            assertNull(chat.liveVoiceStatus)
            assertEquals(DictationUiStatus.LISTENING, chat.dictationStatus)
        }
    }

    @Test
    fun changingPresentationLanguagePreservesUserTextSteeringAndPendingStopProof() {
        val userText = "Menü: Stop — مرحبا — %s — https://example.test/de"
        val assistantText = "This response stays exactly as received. ÄÖÜ"
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.BUSY).copy(
            workInterrupt = ClientWorkInterruptSnapshot(ClientWorkInterruptPhase.PENDING, 19),
            problem = CodexClientProblem(ClientProblemCode.INTERRUPT_REJECTED, retryable = true),
            timeline = listOf(
                ClientTimelineItem("user-locale", ClientTimelineRole.USER, userText,
                    1, 0, true, ClientTimelineStatus.COMPLETE),
                ClientTimelineItem("hans-locale", ClientTimelineRole.HANS, assistantText,
                    2, 0, true, ClientTimelineStatus.COMPLETE),
            ),
        )
        val local = HansLocalUiState(text = "Ungesendeter Entwurf 📝")
        val english = HansClientUiProjector.project(client, local, HansSettings(),
            TestResourceTextResolver(Locale.ENGLISH))
        val german = HansClientUiProjector.project(client, local, HansSettings(), localizationText)
        assertEquals(listOf(userText, assistantText), english.chat.messages.map { it.text })
        assertEquals(english.chat.messages, german.chat.messages)
        assertEquals(local.text, english.chat.composer.text)
        assertEquals(english.chat.composer, german.chat.composer)
        assertTrue(english.chat.composer.enabled)
        assertTrue(english.chat.workInterrupt.pending)
        assertFalse(english.chat.workInterrupt.enabled)
        assertEquals(19L, english.chat.workInterrupt.revision)
        assertEquals(english.chat.workInterrupt, german.chat.workInterrupt)
        assertEquals("The interruption was not confirmed. You can try again.",
            english.chat.connectionFailureMessage)
        assertEquals("Das Unterbrechen wurde nicht bestätigt. Du kannst es erneut versuchen.",
            german.chat.connectionFailureMessage)
        assertEquals(english.settings.selectedModelId, german.settings.selectedModelId)
        assertEquals(english.settings.selectedReasoningEffortId, german.settings.selectedReasoningEffortId)
        assertEquals(ClientWorkInterruptPhase.PENDING, client.workInterrupt.phase)
    }

    @Test
    fun localizedFailedMessageSuffixDoesNotTranslateOrReplaceOriginalInput() {
        val original = "Nicht gesendet. is the literal text I wrote."
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            timeline = listOf(ClientTimelineItem("failed-locale", ClientTimelineRole.USER,
                original, 1, 0, true, ClientTimelineStatus.FAILED)),
        )
        val english = HansClientUiProjector.project(client, HansLocalUiState(), HansSettings(),
            TestResourceTextResolver(Locale.ENGLISH))
        assertEquals("$original\nNot sent.", english.chat.messages.single().text)
    }

    @Test
    fun failedVisualInspectionIsAnActionFailureNotAnUnsentMessageInBothLanguages() {
        val original = "inspect_visual_ui\nvisual_capture_content_changed"
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            timeline = listOf(ClientTimelineItem("failed-visual", ClientTimelineRole.TOOL,
                original, 1, 0, true, ClientTimelineStatus.FAILED)),
        )
        listOf(Locale.ENGLISH to "Action failed.", Locale.GERMAN to "Aktion fehlgeschlagen.")
            .forEach { (locale, caption) ->
                val projected = HansClientUiProjector.project(client, HansLocalUiState(),
                    HansSettings(), TestResourceTextResolver(locale)).chat.messages.single()
                assertEquals("$original\n$caption", projected.text)
                assertEquals(ChatMessageAuthor.SYSTEM, projected.author)
                assertTrue(projected.complete)
            }
    }

    @Test
    fun failedToolWithoutOutputIsNotDisplayedAsStillRunning() {
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            timeline = listOf(ClientTimelineItem("failed-empty-tool", ClientTimelineRole.TOOL,
                "  ", 1, 0, true, ClientTimelineStatus.FAILED)),
        )
        val projected = HansClientUiProjector.project(client, HansLocalUiState(), HansSettings(),
            TestResourceTextResolver(Locale.ENGLISH))
        assertEquals("Action failed.", projected.chat.messages.single().text)
    }

    @Test
    fun failedAssistantAndSystemItemsNeverClaimAnOutboundMessageWasNotSent() {
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            timeline = listOf(ClientTimelineRole.HANS, ClientTimelineRole.SYSTEM).mapIndexed { index, role ->
                ClientTimelineItem("failed-non-user-$index", role, "Original failure $index",
                    index.toLong(), 0, true, ClientTimelineStatus.FAILED)
            },
        )
        val projected = HansClientUiProjector.project(client, HansLocalUiState(), HansSettings(),
            TestResourceTextResolver(Locale.ENGLISH))
        assertEquals(listOf("Original failure 0", "Original failure 1"),
            projected.chat.messages.map { it.text })
    }

    @Test
    fun successfulAndRunningToolTextKeepTheirExistingPresentation() {
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.BUSY).copy(
            timeline = listOf(
                ClientTimelineItem("successful-tool", ClientTimelineRole.TOOL, "inspect_ui",
                    1, 0, true, ClientTimelineStatus.COMPLETE),
                ClientTimelineItem("running-tool", ClientTimelineRole.TOOL, "",
                    2, 0, false, ClientTimelineStatus.IN_PROGRESS),
            ),
        )
        val projected = HansClientUiProjector.project(client, HansLocalUiState(), HansSettings(),
            TestResourceTextResolver(Locale.ENGLISH))
        assertEquals(listOf("inspect_ui", "Phone action in progress"),
            projected.chat.messages.map { it.text })
    }

    @Test
    fun composerStopUsesRuntimeInterruptProofAndLeavesSteeringAndDraftsUsable() {
        val local = HansLocalUiState(text = "Noch eine Ergänzung")
        for (phase in ClientWorkInterruptPhase.entries) {
            val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.BUSY).copy(
                workInterrupt = ClientWorkInterruptSnapshot(phase, revision = 8),
            )
            val chat = HansClientUiProjector.project(client, local, HansSettings(), text = localizationText).chat
            assertEquals(phase != ClientWorkInterruptPhase.IDLE, chat.workInterrupt.visible)
            assertEquals(phase == ClientWorkInterruptPhase.AVAILABLE, chat.workInterrupt.enabled)
            assertEquals(phase == ClientWorkInterruptPhase.PENDING, chat.workInterrupt.pending)
            assertEquals(8L, chat.workInterrupt.revision)
            assertTrue(chat.composer.enabled)
            assertEquals(local.text, chat.composer.text)
        }
        val absent = HansClientUiProjector.project(null, local, HansSettings(), text = localizationText).chat
        assertFalse(absent.workInterrupt.visible)
    }

    @Test
    fun interruptRejectionIsNotMisrepresentedAsASendFailureOrConfirmedStop() {
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.BUSY).copy(
            workInterrupt = ClientWorkInterruptSnapshot(ClientWorkInterruptPhase.AVAILABLE, 1),
            problem = CodexClientProblem(ClientProblemCode.INTERRUPT_REJECTED, retryable = true),
        )
        val chat = HansClientUiProjector.project(client, HansLocalUiState(), HansSettings(), text = localizationText).chat
        assertTrue(chat.isWorking)
        assertTrue(chat.workInterrupt.enabled)
        assertEquals("Das Unterbrechen wurde nicht bestätigt. Du kannst es erneut versuchen.",
            chat.connectionFailureMessage)
    }

    @Test
    fun incomingDesktopStateComesOnlyFromRuntimeAndIdentifiesOnlyCurrentThread() {
        val remote = ai.hans.standard.remotecontrol.RemoteControlSnapshot(
            generation = 1,
            runtimeReady = true,
            capability = ai.hans.standard.remotecontrol.RemoteControlCapability.SUPPORTED,
            connection = ai.hans.standard.remotecontrol.RemoteControlConnection(
                ai.hans.standard.remotecontrol.RemoteControlStatus.CONNECTED,
                "installation-test", "Hans", "environment-test",
            ),
            statusConfirmedForCurrentRuntime = true,
            localConsentGranted = false,
        )
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(remoteControl = remote)
        val ui = HansClientUiProjector.project(client, HansLocalUiState(), HansSettings(), text = localizationText).settings
        assertEquals(remote, ui.remoteControl)
        assertEquals("thread-1", ui.remoteControlThreadId)
        assertNull(ui.remoteControlThreadName)
        assertFalse(ui.remoteControl.mayUsePhoneToolsRemotely)

        val unavailable = HansClientUiProjector.project(null, HansLocalUiState(), HansSettings(), text = localizationText).settings
        assertFalse(unavailable.remoteControl.runtimeReady)
        assertFalse(unavailable.remoteControl.localConsentGranted)
        assertNull(unavailable.remoteControlThreadId)
        assertNull(unavailable.remoteControlThreadName)
    }

    @Test
    fun codexUpdateStateUsesBundledVersionAndOnlyConfirmedReadyRuntime() {
        val checking = HansClientUiProjector.project(
            null,
            HansLocalUiState(),
            HansSettings(), text = localizationText).settings.codexUpdate
        val ready = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY),
            HansLocalUiState(),
            HansSettings(), text = localizationText).settings.codexUpdate
        val failed = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
                runtimePhase = ClientRuntimePhase.FAILED,
            ),
            HansLocalUiState(),
            HansSettings(), text = localizationText).settings.codexUpdate

        assertEquals(ai.hans.standard.BuildConfig.CODEX_RUNTIME_VERSION, checking.bundledRuntimeVersion)
        assertEquals(ai.hans.standard.BuildConfig.CODEX_RUNTIME_VERSION, ready.bundledRuntimeVersion)
        assertFalse(checking.runtimeReady)
        assertTrue(ready.runtimeReady)
        assertFalse(failed.runtimeReady)
        assertEquals(
            ai.hans.standard.BuildConfig.HANS_UPDATE_URL.isNotBlank(),
            ready.updateUrlConfigured,
        )
    }

    @Test
    fun authenticatedWorkbenchDestinationKeepsTheExplicitInventorySnapshot() {
        val workbench = WorkbenchUiState(
            workspaces = listOf(
                WorkbenchWorkspaceUiModel("a".repeat(64), fileCount = 2, byteCount = 30),
            ),
            python = WorkbenchPythonUiState(initialized = false),
        )
        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY),
            HansLocalUiState(
                requestedDestination = HansDestination.WORKBENCH,
                workbench = workbench,
            ),
            HansSettings(), text = localizationText)

        assertEquals(HansDestination.WORKBENCH, ui.destination)
        assertEquals(workbench, ui.workbench)
    }

    @Test
    fun assistantMarkdownAndDestinationsReachTheRendererWithoutMutatingHistory() {
        val user = item("u", ClientTimelineRole.USER, "Prüfe https://example.test/a", 1)
        val answer = item("a", ClientTimelineRole.HANS,
            "Sieh [diese Veranstaltung](https://example.test/event). Mehr dazu: https://example.test/secret", 2)
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY)
            .copy(timeline = listOf(user, answer))
        val visible = HansClientUiProjector.project(client, HansLocalUiState(), HansSettings(), text = localizationText).chat.messages
        assertEquals(user.text, visible.first().text)
        // The native rich-text component creates the visible name and its clickable span.
        // Losing the destination in this transport projection was the original regression.
        assertEquals(answer.text, visible.last().text)
        assertTrue(visible.last().complete)
        assertEquals(answer.text, client.timeline.last().text)

        val stream = client.copy(timeline = listOf(answer.copy(text = "Fertig. htt", complete = false)))
        val streamed = HansClientUiProjector.project(stream, HansLocalUiState(), HansSettings(), text = localizationText).chat.messages
        assertEquals("Fertig. htt", streamed.single().text)
        assertFalse(streamed.single().complete)
        assertEquals("Fertig. htt", stream.timeline.single().text)
    }

    @Test
    fun assistantCompletionReachesRichTextEvenWhenTheFinalTextIsUnchanged() {
        val answer = item("a", ClientTimelineRole.HANS, "**Erledigt**.", 1)
            .copy(complete = false, revision = 1)
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.BUSY)
            .copy(timeline = listOf(answer))
        val streaming = HansClientUiProjector.project(client, HansLocalUiState(), HansSettings(), text = localizationText)
            .chat.messages.single()
        val final = HansClientUiProjector.project(
            client.copy(timeline = listOf(answer.copy(complete = true, revision = 2))),
            HansLocalUiState(),
            HansSettings(), text = localizationText).chat.messages.single()

        assertEquals(streaming.text, final.text)
        assertFalse(streaming.complete)
        assertTrue(final.complete)
        assertTrue(final.revision > streaming.revision)
    }

    @Test
    fun liveRouteControlKeepsConfirmedRouteAvailableDuringListeningAndSpeaking() {
        val local = HansLocalUiState(
            liveVoiceStatus = LiveVoiceUiStatus.LISTENING,
            liveVoiceVoiceSelection = LiveVoiceVoiceSelection(
                requestedTtsVoice = "fable",
                effectiveRealtimeVoice = "ballad",
                resolution = LiveVoiceVoiceResolution.APPROXIMATE,
            ),
            speechAudioRoute = ai.hans.standard.voice.audio.SpeechAudioRouteState(
                active = true, effective = ai.hans.standard.voice.audio.SpeechAudioRoute.EARPIECE,
            ),
        )
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY)
        assertTrue(HansClientUiProjector.project(client, local, HansSettings(), text = localizationText).chat.speechAudioRoute.active)
        val speaking = HansClientUiProjector.project(client,
            local.copy(liveVoiceStatus = LiveVoiceUiStatus.HANS_SPEAKING), HansSettings(), text = localizationText)
        assertTrue(speaking.chat.speechAudioRoute.active)
        assertEquals(ai.hans.standard.voice.audio.SpeechAudioRoute.EARPIECE, speaking.chat.speechAudioRoute.effective)
        assertEquals(local.liveVoiceVoiceSelection, speaking.chat.liveVoiceVoiceSelection)
    }

    @Test
    fun readyLocalRuntimeStillShowsOfflineBannerAndKeepsComposerEditable() {
        val local = HansLocalUiState(
            text = "Meine Frage",
            internet = InternetSnapshot(InternetStatus.OFFLINE, 3),
        )
        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY), local, HansSettings(), text = localizationText)
        assertTrue(ui.chat.internetNotice.contains("Keine Internetverbindung"))
        assertTrue(ui.chat.composer.enabled)
        assertEquals("Meine Frage", ui.chat.composer.text)
        assertEquals(RuntimeUiStatus.ONLINE, ui.chat.runtimeStatus)
    }

    @Test
    fun connectivityLossDuringTurnIsNotSilentAndNeverClaimsTaskWasCancelled() {
        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.BUSY),
            HansLocalUiState(internet = InternetSnapshot(InternetStatus.OFFLINE, 3)),
            HansSettings(), text = localizationText)
        assertTrue(ui.chat.isWorking)
        assertTrue(ui.chat.internetNotice.contains("laufende Antwort"))
        assertTrue(ui.chat.internetNotice.contains("nicht erneut gesendet"))
        assertFalse(ui.chat.internetNotice.contains("abgebrochen"))
    }

    @Test
    fun validatedNetworkDoesNotHideActualTransportFailure() {
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            problem = CodexClientProblem(ClientProblemCode.DISPATCH_AMBIGUOUS, retryable = false),
        )
        val ui = HansClientUiProjector.project(
            client,
            HansLocalUiState(internet = InternetSnapshot(InternetStatus.ONLINE, 4)),
            HansSettings(), text = localizationText)
        assertEquals("", ui.chat.internetNotice)
        assertTrue(ui.chat.connectionFailureMessage.contains("brach beim Senden ab"))
    }

    @Test
    fun offlineLoginShowsInternetProblemInsteadOfOnlyCheckingAccount() {
        val ui = HansClientUiProjector.project(
            null, HansLocalUiState(internet = InternetSnapshot(InternetStatus.OFFLINE, 1)), HansSettings(), text = localizationText)
        assertEquals(HansDestination.AUTH_GATE, ui.destination)
        assertTrue(ui.authGate.internetNotice.contains("Keine Internetverbindung"))
    }

    @Test
    fun secureDefaultKeepsChatHiddenUntilConfirmedAccountAndThreadReady() {
        val checking = HansClientUiProjector.project(null, HansLocalUiState(), HansSettings(), text = localizationText)
        assertEquals(HansDestination.AUTH_GATE, checking.destination)
        assertEquals(AuthGateStage.CHECKING, checking.authGate.stage)

        val signedOut = HansClientUiProjector.project(
            snapshot(accountPhase = AccountPhase.SIGNED_OUT, sessionPhase = ClientSessionPhase.AUTH_REQUIRED),
            HansLocalUiState(),
            HansSettings(), text = localizationText)
        assertEquals(AuthGateStage.SIGNED_OUT, signedOut.authGate.stage)

        val signedIn = HansClientUiProjector.project(
            snapshot(accountPhase = AccountPhase.SIGNED_IN, sessionPhase = ClientSessionPhase.READY),
            HansLocalUiState(),
            HansSettings(), text = localizationText)
        assertEquals(HansDestination.CHAT, signedIn.destination)
        assertTrue(signedIn.chat.composer.enabled)
    }

    @Test
    fun deviceCodeAndOrderedMixedTimelineProjectWithoutRawFrames() {
        val login = snapshot(
            accountPhase = AccountPhase.SIGNED_OUT,
            sessionPhase = ClientSessionPhase.LOGIN_PENDING,
        ).copy(
            deviceCodeLogin = ai.hans.standard.integration.DeviceCodeLoginUi(
                "ABCD-EFGH",
                "https://auth.openai.com/device",
            ),
        )
        val loginUi = HansClientUiProjector.project(login, HansLocalUiState(), HansSettings(), text = localizationText)
        assertEquals(AuthGateStage.DEVICE_CODE_AWAITING, loginUi.authGate.stage)
        assertEquals("ABCD-EFGH", loginUi.authGate.userCode)

        val timeline = listOf(
            item("u1", ClientTimelineRole.USER, "Hallo", 1),
            item("a1", ClientTimelineRole.HANS, "Hi", 2),
            item("u2", ClientTimelineRole.USER, "Weiter", 3),
        )
        val chat = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.BUSY).copy(timeline = timeline),
            HansLocalUiState(),
            HansSettings(), text = localizationText).chat
        assertEquals(listOf("Hallo", "Hi", "Weiter"), chat.messages.map { it.text })
        assertEquals(
            listOf(ChatMessageAuthor.USER, ChatMessageAuthor.HANS, ChatMessageAuthor.USER),
            chat.messages.map { it.author },
        )
        assertTrue(chat.isWorking)
    }

    @Test
    fun confirmedLoginWithThreadFailureNamesConversationFailureAndKeepsReadinessGate() {
        val base = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.FAILED).copy(
            problem = CodexClientProblem(ClientProblemCode.THREAD_RECOVERY, retryable = true),
        )
        listOf(null, "thread-1").forEach { threadId ->
            val ui = HansClientUiProjector.project(
                base.copy(session = base.session.copy(currentThreadId = threadId)),
                HansLocalUiState(),
                HansSettings(), text = localizationText)
            assertEquals(HansDestination.AUTH_GATE, ui.destination)
            assertEquals(AuthGateStage.ERROR, ui.authGate.stage)
            assertTrue(ui.authGate.sessionRecovery)
            assertEquals(
                if (threadId == null) "Gespräch konnte nicht gestartet werden"
                else "Gespräch konnte nicht wiederhergestellt werden",
                ui.authGate.errorTitle,
            )
            assertTrue(ui.authGate.errorMessage.startsWith("Du bist angemeldet."))
            assertFalse(ui.chat.composer.enabled)
        }
    }

    @Test
    fun authenticatedRuntimeFailureIsConnectionFailureNotAnotherLogin() {
        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.FAILED).copy(
                runtimePhase = ClientRuntimePhase.FAILED,
                problem = CodexClientProblem(ClientProblemCode.RUNTIME_FAILED, retryable = true),
            ),
            HansLocalUiState(),
            HansSettings(), text = localizationText)
        assertEquals(AuthGateStage.ERROR, ui.authGate.stage)
        assertEquals("Hans konnte nicht verbunden werden", ui.authGate.errorTitle)
        assertTrue(ui.authGate.sessionRecovery)
        assertFalse(ui.chat.composer.enabled)
    }

    @Test
    fun authenticatedConversationRecoveryDoesNotPretendToRecheckLogin() {
        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.RECOVERING_THREAD),
            HansLocalUiState(),
            HansSettings(), text = localizationText)
        assertEquals(HansDestination.AUTH_GATE, ui.destination)
        assertEquals(AuthGateStage.CHECKING, ui.authGate.stage)
        assertTrue(ui.authGate.sessionRecovery)
        assertFalse(ui.chat.composer.enabled)
    }

    @Test
    fun authenticationFailureStillShowsLoginFailureEvenWithPreviouslySignedInAccount() {
        listOf(AccountPhase.SIGNED_OUT, AccountPhase.SIGNED_IN).forEach { accountPhase ->
            val ui = HansClientUiProjector.project(
                snapshot(accountPhase, ClientSessionPhase.FAILED).copy(
                    problem = CodexClientProblem(ClientProblemCode.AUTHENTICATION, retryable = true),
                ),
                HansLocalUiState(),
                HansSettings(), text = localizationText)
            assertEquals(HansDestination.AUTH_GATE, ui.destination)
            assertEquals(AuthGateStage.ERROR, ui.authGate.stage)
            assertEquals("Anmeldung nicht abgeschlossen", ui.authGate.errorTitle)
            assertFalse(ui.authGate.sessionRecovery)
            assertFalse(ui.chat.composer.enabled)
        }
    }

    @Test
    fun activeSetupAssistantTextIsSanitizedButUserTextIsPreserved() {
        val leaked = "Der aktuelle Schritt ist `intro` und wartet noch auf deine Entscheidung. " +
            "Soll ich die Hans-Einrichtung jetzt starten?"
        val ui = HansClientUiProjector.project(
            client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
                timeline = listOf(
                    item("u1", ClientTimelineRole.USER, leaked, 1),
                    item("a1", ClientTimelineRole.HANS, leaked, 2, turnId = "setup-turn"),
                ),
                setupTurnIds = setOf("setup-turn"),
            ),
            local = HansLocalUiState(),
            settings = HansSettings(), text = localizationText)

        assertEquals(leaked, ui.chat.messages.first().text)
        assertEquals(
            "Die Einrichtung ist bereit. Soll ich die Hans-Einrichtung jetzt starten?",
            ui.chat.messages.last().text,
        )
    }

    @Test
    fun activeSetupHidesInternalShellAndToolReceipts() {
        val ui = HansClientUiProjector.project(
            client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.BUSY).copy(
                timeline = listOf(
                    item(
                        "shell",
                        ClientTimelineRole.TOOL,
                        "/bin/sh -lc sed -n '1,240p' SKILL.md\nraw setup skill source",
                        1,
                        turnId = "setup-turn",
                    ),
                    item(
                        "system",
                        ClientTimelineRole.SYSTEM,
                        "hans_setup.get_setup_state returned internal state",
                        2,
                        turnId = "setup-turn",
                    ),
                    item(
                        "answer",
                        ClientTimelineRole.HANS,
                        "Die Einrichtung ist bereit. Möchtest du beginnen?",
                        3,
                        turnId = "setup-turn",
                    ),
                ),
                setupTurnIds = setOf("setup-turn"),
            ),
            local = HansLocalUiState(),
            settings = HansSettings(), text = localizationText)

        assertEquals(
            listOf("Die Einrichtung ist bereit. Möchtest du beginnen?"),
            ui.chat.messages.map { it.text },
        )
        assertEquals(listOf(ChatMessageAuthor.HANS), ui.chat.messages.map { it.author })
    }

    @Test
    fun astraIsSelectableOnlyWhenAdvertisedVisibleAndWithUsableEffort() {
        val astra = model(
            id = "gpt-6-astra",
            defaultEffort = ReasoningEffort.HIGH,
            efforts = setOf(ReasoningEffort.HIGH, ReasoningEffort.ULTRA),
        )
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY)
        val visible = HansClientUiProjector.project(
            client.copy(models = distinctEffortModels() + astra),
            HansLocalUiState(),
            HansSettings(), text = localizationText).settings
        assertEquals("Astra 6", visible.models.single { it.id == "gpt-6-astra" }.label)

        for (unavailable in listOf(
            emptyList(),
            listOf(astra.copy(hidden = true)),
            listOf(astra.copy(
                supportedEfforts = setOf(ReasoningEffort.MINIMAL),
                defaultEffort = ReasoningEffort.MINIMAL,
            )),
        )) {
            val hidden = HansClientUiProjector.project(
                client.copy(models = distinctEffortModels() + unavailable),
                HansLocalUiState(),
                HansSettings(), text = localizationText).settings
            assertFalse(hidden.models.any { it.id == "gpt-6-astra" })
        }
    }

    @Test
    fun latestModelChoicesComeOnlyFromAuthoritativeRuntimeCatalogInCurrentFirstOrder() {
        val current = HansSettings.CURRENT_MODEL_ORDER.map { model(it, ReasoningEffort.MEDIUM, setOf(ReasoningEffort.MEDIUM)) }
        val legacy = HansSettings.LEGACY_MODEL_ORDER.map { model(it, ReasoningEffort.HIGH, setOf(ReasoningEffort.HIGH)) }
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(models = (legacy + current).reversed())
        val projected = HansClientUiProjector.project(client, HansLocalUiState(), HansSettings(), localizationText).settings
        assertEquals(HansSettings.MODEL_ORDER, projected.models.map { it.id })
        assertEquals("Astra 6", projected.models.single { it.id == "gpt-6-astra" }.label)
        assertEquals("gpt-6-astra", projected.selectedModelId)
        assertEquals("medium", projected.selectedReasoningEffortId)
    }

    @Test
    fun unavailableHiddenOrUnusableLatestModelCannotBeOfferedOrOptimisticallySelected() {
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY)
        for (id in listOf("gpt-6-luna", "gpt-6.1-sol")) {
            val usable = model(id, ReasoningEffort.HIGH, setOf(ReasoningEffort.HIGH))
            for (unavailable in listOf(emptyList(), listOf(usable.copy(hidden = true)),
                    listOf(usable.copy(defaultEffort = ReasoningEffort.MINIMAL, supportedEfforts = setOf(ReasoningEffort.MINIMAL))))) {
                val projected = HansClientUiProjector.project(client.copy(models = distinctEffortModels() + unavailable),
                    HansLocalUiState(pendingModelId = id), HansSettings(), localizationText).settings
                assertFalse(projected.models.any { it.id == id })
                assertFalse(projected.selectedModelId == id)
            }
        }
    }

    @Test
    fun pendingLatestModelPreservesConfirmedLegacySelectionAndOffersOnlyActualEfforts() {
        val legacy = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MEDIUM)
        val current = model("gpt-6-luna", ReasoningEffort.HIGH, setOf(ReasoningEffort.HIGH, ReasoningEffort.MAX))
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            models = distinctEffortModels() + current, confirmedSelection = legacy,
            pendingSettingsSelection = DispatchSelection("gpt-6-luna", ReasoningEffort.MAX))
        val projected = HansClientUiProjector.project(client, HansLocalUiState(),
            HansSettings(model = legacy.model, reasoningEffort = legacy.effort.wireValue), localizationText).settings
        assertEquals(legacy.model, projected.selectedModelId)
        assertEquals(legacy.effort.wireValue, projected.selectedReasoningEffortId)
        assertEquals(listOf("high", "max"), projected.reasoningEfforts.map { it.id })
        assertTrue(projected.runtimeNotice.contains("Luna"))
    }

    @Test
    fun pendingAstraKeepsTheConfirmedModelUntilTheAppServerAcceptsIt() {
        val astra = model(
            id = "gpt-6-astra",
            defaultEffort = ReasoningEffort.HIGH,
            efforts = setOf(ReasoningEffort.HIGH, ReasoningEffort.ULTRA),
        )
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            models = distinctEffortModels() + astra,
            confirmedSelection = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MEDIUM),
        )
        val pending = HansClientUiProjector.project(
            client,
            HansLocalUiState(pendingModelId = "gpt-6-astra", pendingEffortId = "ultra"),
            HansSettings(), text = localizationText).settings
        assertEquals("gpt-5.6-luna", pending.selectedModelId)
        assertEquals("medium", pending.selectedReasoningEffortId)
        assertEquals(listOf("high", "ultra"), pending.reasoningEfforts.map { it.id })
        assertFalse(pending.fastModeAvailable)
        assertTrue(pending.runtimeNotice.contains("Astra"))

        val confirmed = HansClientUiProjector.project(
            client.copy(confirmedSelection = DispatchSelection("gpt-6-astra", ReasoningEffort.ULTRA)),
            HansLocalUiState(),
            HansSettings(model = "gpt-6-astra", reasoningEffort = "ultra"), text = localizationText).settings
        assertEquals("gpt-6-astra", confirmed.selectedModelId)
        assertEquals("ultra", confirmed.selectedReasoningEffortId)
    }

    @Test
    fun immediateSettingsConfirmationSurvivesActivityRecreationWithoutATurn() {
        val next = DispatchSelection("gpt-5.6-sol", ReasoningEffort.ULTRA)
        val base = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            models = distinctEffortModels(),
            confirmedSelection = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MEDIUM),
            pendingSettingsSelection = next,
        )
        val waiting = HansClientUiProjector.project(base, HansLocalUiState(), HansSettings(), text = localizationText).settings
        assertEquals("gpt-5.6-luna", waiting.selectedModelId)
        assertEquals("medium", waiting.selectedReasoningEffortId)
        assertEquals(listOf("max", "ultra"), waiting.reasoningEfforts.map { it.id })
        assertEquals("Sol 5.6 · Ultra wird bestätigt …", waiting.runtimeNotice)
        assertFalse(waiting.runtimeNotice.contains("nächsten Nachricht"))

        val confirmed = HansClientUiProjector.project(
            base.copy(pendingSettingsSelection = null, confirmedSelection = next),
            HansLocalUiState(), HansSettings(), text = localizationText).settings
        assertEquals("gpt-5.6-sol", confirmed.selectedModelId)
        assertEquals("ultra", confirmed.selectedReasoningEffortId)
        assertEquals("", confirmed.runtimeNotice)
    }

    @Test
    fun settingsHandshakeLeavesBusySteerInputAndExistingDraftAvailable() {
        val base = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.BUSY).copy(
            models = distinctEffortModels(),
            pendingSettingsSelection = DispatchSelection("gpt-5.6-sol", ReasoningEffort.ULTRA),
        )
        val ui = HansClientUiProjector.project(base, HansLocalUiState(text = "Nachtrag"), HansSettings(), text = localizationText)
        assertTrue(ui.chat.composer.enabled)
        assertEquals("Nachtrag", ui.chat.composer.text)
        assertFalse(ui.chat.connectionFailureMessage.contains("nicht bestätigt"))
    }

    @Test
    fun rejectedImmediateSettingsKeepConfirmedMarkersAndShowRetryNotice() {
        val base = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            models = distinctEffortModels(),
            confirmedSelection = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MEDIUM),
            problem = CodexClientProblem(ClientProblemCode.SELECTION_UPDATE, retryable = true),
        )
        val ui = HansClientUiProjector.project(base, HansLocalUiState(), HansSettings(), text = localizationText).settings
        assertEquals("gpt-5.6-luna", ui.selectedModelId)
        assertEquals("medium", ui.selectedReasoningEffortId)
        assertTrue(ui.runtimeNotice.contains("nicht bestätigt"))
        assertTrue(ui.runtimeNotice.contains("erneut auswählen"))
        assertFalse(ui.runtimeNotice.contains("Anmeldung"))
    }

    @Test
    fun pendingRuntimeChoiceIsNoticeOnlyUntilServerConfirmsIt() {
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            models = distinctEffortModels(),
            confirmedSelection = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MEDIUM),
        )
        val ui = HansClientUiProjector.project(
            client,
            HansLocalUiState(pendingModelId = "gpt-5.6-sol", pendingEffortId = "ultra"),
            HansSettings(), text = localizationText)

        assertEquals("gpt-5.6-luna", ui.settings.selectedModelId)
        assertEquals("medium", ui.settings.selectedReasoningEffortId)
        assertEquals(listOf("max", "ultra"), ui.settings.reasoningEfforts.map { it.id })
        assertTrue(ui.settings.runtimeNotice.contains("Sol"))
        assertTrue(ui.settings.runtimeNotice.contains("Ultra"))
        assertFalse(ui.settings.runtimeNotice.isBlank())
    }

    @Test
    fun modelSwitchReconcilesStaleEffortBeforeItCanBecomeSelectable() {
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            models = distinctEffortModels(),
            confirmedSelection = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MEDIUM),
        )

        val ui = HansClientUiProjector.project(
            client,
            HansLocalUiState(pendingModelId = "gpt-5.6-terra", pendingEffortId = "medium"),
            HansSettings(), text = localizationText)

        assertEquals(listOf("high", "max"), ui.settings.reasoningEfforts.map { it.id })
        assertTrue(ui.settings.runtimeNotice.contains("Terra"))
        assertTrue(ui.settings.runtimeNotice.contains("Hoch"))
        assertFalse(ui.settings.runtimeNotice.contains("Mittel"))
    }

    @Test
    fun activeTurnPendingSelectionDrivesAvailableEffortsWithoutClaimingConfirmation() {
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.BUSY).copy(
            models = distinctEffortModels(),
            confirmedSelection = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MEDIUM),
            pendingSelection = DispatchSelection("gpt-5.6-sol", ReasoningEffort.ULTRA),
        )

        val ui = HansClientUiProjector.project(client, HansLocalUiState(), HansSettings(), text = localizationText)

        assertEquals(listOf("max", "ultra"), ui.settings.reasoningEfforts.map { it.id })
        assertEquals("gpt-5.6-luna", ui.settings.selectedModelId)
        assertEquals("medium", ui.settings.selectedReasoningEffortId)
        assertTrue(ui.settings.runtimeNotice.contains("Sol"))
        assertTrue(ui.settings.runtimeNotice.contains("Ultra"))
    }

    @Test
    fun fastModeIsMarkedActiveOnlyAfterTheAppServerSelectionIsConfirmed() {
        val models = distinctEffortModels().mapIndexed { index, model ->
            if (index == 0) {
                model.copy(
                    serviceTiers = listOf(
                        ModelServiceTier(
                            HansSettings.FAST_SERVICE_TIER,
                            "Fast",
                            "1.5x speed, increased usage",
                        ),
                    ),
                )
            } else {
                model
            }
        }
        val standard = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MEDIUM)
        val fast = standard.copy(serviceTier = HansSettings.FAST_SERVICE_TIER)
        val base = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            models = models,
            confirmedSelection = standard,
        )

        val locallyStaged = HansClientUiProjector.project(
            base,
            HansLocalUiState(pendingFastModeEnabled = true),
            HansSettings(), text = localizationText).settings
        assertTrue(locallyStaged.fastModeAvailable)
        assertFalse(locallyStaged.fastModeEnabled)
        assertTrue(locallyStaged.runtimeNotice.contains("Fast"))

        val dispatched = HansClientUiProjector.project(
            base.copy(
                sessionPhase = ClientSessionPhase.BUSY,
                pendingSelection = fast,
            ),
            HansLocalUiState(),
            HansSettings(), text = localizationText).settings
        assertFalse(dispatched.fastModeEnabled)

        val confirmed = HansClientUiProjector.project(
            base.copy(confirmedSelection = fast),
            HansLocalUiState(),
            HansSettings(serviceTier = HansSettings.FAST_SERVICE_TIER), text = localizationText).settings
        assertTrue(confirmed.fastModeEnabled)
    }

    @Test
    fun advertisedModelWithoutAnyHansEffortIsNotSelectable() {
        val unusableLuna = model(
            id = "gpt-5.6-luna",
            defaultEffort = ReasoningEffort.MINIMAL,
            efforts = setOf(ReasoningEffort.MINIMAL),
            isDefault = true,
        )
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            models = listOf(unusableLuna) + distinctEffortModels().drop(1),
        )

        val ui = HansClientUiProjector.project(client, HansLocalUiState(), HansSettings(), text = localizationText)

        assertEquals(
            listOf("gpt-5.6-terra", "gpt-5.6-sol"),
            ui.settings.models.map { it.id },
        )
        assertEquals(listOf("high", "max"), ui.settings.reasoningEfforts.map { it.id })
        assertNull(ui.settings.selectedModelId)
        assertNull(ui.settings.selectedReasoningEffortId)
    }

    @Test
    fun temporarilyLockedSpeechCredentialIsNotPresentedAsMissing() {
        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY),
            HansLocalUiState(
                speechCredentialStatus = SpeechCredentialUiStatus.TEMPORARILY_UNAVAILABLE,
            ),
            HansSettings(), text = localizationText)

        assertEquals(
            SpeechCredentialUiStatus.TEMPORARILY_UNAVAILABLE,
            ui.settings.speechCredentialStatus,
        )
    }

    @Test
    fun dictationStatusIsExplicitButNeverCreatesAnIdleOrSentBanner() {
        val listening = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY),
            HansLocalUiState(dictationStatus = DictationUiStatus.LISTENING),
            HansSettings(), text = localizationText)
        assertEquals(DictationUiStatus.LISTENING, listening.chat.dictationStatus)
        assertFalse(listening.chat.composer.enabled)

        val finalizing = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY),
            HansLocalUiState(dictationStatus = DictationUiStatus.FINALIZING),
            HansSettings(), text = localizationText)
        assertTrue(finalizing.chat.composer.enabled)

        val sent = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY),
            HansLocalUiState(dictationStatus = null),
            HansSettings(), text = localizationText)
        assertEquals(null, sent.chat.dictationStatus)
        assertTrue(sent.chat.composer.enabled)
    }

    @Test
    fun actionKeyCaptureProjectionIsSeparateFromRuntimeSettings() {
        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY),
            HansLocalUiState(
                actionKeyConfigured = true,
                actionKeyCapturing = true,
                actionKeyNotice = "Warte auf eine Taste …",
            ),
            HansSettings(), text = localizationText)

        assertTrue(ui.settings.actionKey.configured)
        assertTrue(ui.settings.actionKey.capturing)
        assertEquals("Warte auf eine Taste …", ui.settings.actionKey.notice)
    }

    @Test
    fun retiredInputPreferencesArePreservedButDoNotReactivateHoldGestures() {
        val stored = HansSettings(
            dictationKeyTrigger = ActionKeyTrigger.HOLD_TO_TALK,
            cameraHoldToTalkEnabled = true,
        )
        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY),
            HansLocalUiState(),
            stored, text = localizationText)

        assertEquals(ActionKeyTrigger.PRESS, ui.settings.actionKey.dictationTrigger)
        assertFalse(ui.settings.cameraHoldToTalkEnabled)
        assertFalse(ui.chat.cameraHoldToTalkEnabled)
        assertEquals(ActionKeyTrigger.HOLD_TO_TALK, stored.dictationKeyTrigger)
        assertTrue(stored.cameraHoldToTalkEnabled)
    }

    @Test
    fun assignedPhysicalKeyHidesScreenMicrophoneEvenWhenTemporarilyGated() {
        for (client in listOf(null, snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.BUSY))) {
            for (status in listOf(null, DictationUiStatus.PREPARING, DictationUiStatus.LISTENING,
                    DictationUiStatus.FINALIZING)) {
                for (assigned in listOf(false, true)) {
                    val ui = HansClientUiProjector.project(client, HansLocalUiState(
                        actionKeyAssigned = assigned,
                        actionKeyConfigured = false,
                        dictationStatus = status,
                        dictationInputMuted = true,
                    ), HansSettings(), text = localizationText)
                    assertEquals(assigned, ui.chat.actionKeyConfigured)
                    assertEquals(assigned, ui.settings.actionKey.dictationMappingStored)
                    assertFalse(ui.settings.actionKey.configured)
                    assertEquals(status, ui.chat.dictationStatus)
                    assertTrue(ui.chat.dictationInputMuted)
                    assertEquals(status != null, ui.settings.voiceSessionActive)
                }
            }
        }
    }

    @Test
    fun phoneSessionReservesPreviewAudioEvenBeforeVoiceSelectionConfirmation() {
        for (status in LiveVoiceUiStatus.entries) {
            val ui = HansClientUiProjector.project(null, HansLocalUiState(
                liveVoiceStatus = status, liveVoiceVoiceSelection = null,
            ), HansSettings(), text = localizationText)
            assertEquals(status.isActive, ui.settings.voiceSessionActive)
            assertNull(ui.settings.activeLiveVoiceId)
        }
    }

    @Test
    fun mp01VendorConflictStateProjectsWithoutAffectingGenericActionKeys() {
        val conflict = Mp01VendorActionConflictUiState(
            detected = true,
            settingsActivityAvailable = true,
            replacementConfirmed = false,
            shortcutKinds = setOf(Mp01VendorShortcutUiKind.DICTATION),
        )
        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY),
            HansLocalUiState(
                actionKeyConfigured = false,
                mp01VendorActionConflict = conflict,
            ),
            HansSettings(), text = localizationText)

        assertEquals(conflict, ui.settings.actionKey.mp01VendorConflict)
        assertTrue(ui.settings.actionKey.mp01VendorConflict.replacementRequired)
        assertFalse(ui.settings.actionKey.configured)
        assertTrue(ui.settings.actionKey.dictationMappingStored)
        assertFalse(ActionKeyUiState(configured = true).mp01VendorConflict.detected)
    }

    @Test
    fun capabilityAccessProjectionRemainsExplicitAndRevocable() {
        val access = listOf(
            CapabilityAccessUiModel(
                CapabilityAccessUiId.NOTIFICATION_ACCESS,
                "Benachrichtigungszugriff",
                "Lokale Ereignisse",
                granted = false,
            ),
        )
        val durable = listOf(
            PersistentAndroidConsentUiModel(
                PersistentAndroidConsentDescriptor.category(
                    PersistentAndroidConsentScope.INSTALLED_APPS_READ,
                ),
                "Installierte Apps lesen",
                "Widerrufbar",
            ),
        )
        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY),
            HansLocalUiState(
                capabilityAccess = access,
                persistentAndroidConsents = durable,
            ),
            HansSettings(), text = localizationText)

        assertEquals(access, ui.settings.capabilityAccess)
        assertEquals(durable, ui.settings.persistentAndroidConsents)
    }

    @Test
    fun validatedNotificationOverlayIsVisibleButSeparateFromCodexTimeline() {
        val notification = ChatMessageUiModel(
            id = "notification:stable",
            author = ChatMessageAuthor.SYSTEM,
            text = "Dein Termin beginnt gleich.",
            localTimelineAnchorId = "a1",
            localArrivalOrder = 2,
        )
        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
                timeline = listOf(item("a1", ClientTimelineRole.HANS, "Hallo", 1)),
            ),
            HansLocalUiState(notificationMessages = listOf(notification)),
            HansSettings(), text = localizationText)

        assertEquals(listOf("Hallo", "Dein Termin beginnt gleich."), ui.chat.messages.map { it.text })
        assertEquals(ChatMessageAuthor.SYSTEM, ui.chat.messages.last().author)
    }

    @Test
    fun laterUserAndHansMessagesBecomeActualTailAfterAnOlderNotification() {
        val notification = ChatMessageUiModel(
            id = "notification:older",
            author = ChatMessageAuthor.SYSTEM,
            text = "Aeltere Benachrichtigung",
            localTimelineAnchorId = "a1",
            localArrivalOrder = 10,
        )
        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
                timeline = listOf(
                    item("a1", ClientTimelineRole.HANS, "Vorher", 1),
                    item("u2", ClientTimelineRole.USER, "Neue Frage", 2),
                    item("a2", ClientTimelineRole.HANS, "Neue Antwort", 3),
                ),
            ),
            HansLocalUiState(notificationMessages = listOf(notification)),
            HansSettings(), text = localizationText)

        assertEquals(
            listOf("Vorher", "Aeltere Benachrichtigung", "Neue Frage", "Neue Antwort"),
            ui.chat.messages.map { it.text },
        )
        assertEquals("Neue Antwort", ui.chat.messages.last().text)
    }

    @Test
    fun localOverlayAnchorsToLastActuallyVisibleItemNotFilteredEmptyMetadata() {
        val visible = item("a1", ClientTimelineRole.HANS, "Sichtbar", 1)
        val filtered = item("hidden", ClientTimelineRole.SYSTEM, "", 2)
        val rawTimeline = listOf(visible, filtered)
        val notification = ChatMessageUiModel(
            id = "notification:after-visible",
            author = ChatMessageAuthor.SYSTEM,
            text = "Danach eingetroffen",
            localTimelineAnchorId = rawTimeline.latestVisibleChatTimelineId(),
            localArrivalOrder = 1,
        )

        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
                timeline = rawTimeline,
            ),
            HansLocalUiState(notificationMessages = listOf(notification)),
            HansSettings(), text = localizationText)

        assertEquals("a1", notification.localTimelineAnchorId)
        assertEquals(listOf("Sichtbar", "Danach eingetroffen"), ui.chat.messages.map { it.text })
    }

    @Test
    fun pendingComposerDispatchDisablesDuplicateSubmitUntilCorrelatedSent() {
        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.BUSY),
            HansLocalUiState(
                text = "Noch nicht bestätigt",
                pendingComposerMessageId = "composer-pending",
            ),
            HansSettings(), text = localizationText)

        assertFalse(ui.chat.composer.enabled)
        assertEquals("Noch nicht bestätigt", ui.chat.composer.text)
    }

    @Test
    fun pluginCatalogProjectsInstalledAndInstallableCardsWithPendingTarget() {
        val installed = pluginCard("a", installed = true, installable = true)
        val available = pluginCard("b", installed = false, installable = true)
        val blocked = pluginCard("c", installed = false, installable = false)
        val plugins = PluginDomainSnapshot.EMPTY.copy(
            phase = PluginCatalogPhase.READY,
            revision = 4,
            plugins = listOf(installed, available, blocked),
            operations = listOf(
                PluginOperationSnapshot(
                    operationId = "operation-1",
                    kind = PluginOperationKind.INSTALL_PLUGIN,
                    target = available.handle,
                    status = PluginOperationStatus.PENDING,
                    retryable = false,
                    failure = null,
                ),
            ),
        )

        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(plugins = plugins),
            HansLocalUiState(selectedPluginList = PluginListKind.AVAILABLE),
            HansSettings(), text = localizationText).plugins

        assertEquals(listOf(installed.handle.value), ui.installed.map { it.id })
        assertEquals(listOf(available.handle.value), ui.available.map { it.id })
        assertEquals(available.handle.value, ui.operationPluginId)
        assertEquals("Available b", ui.available.single().name)
        assertTrue(ui.available.single().actionEnabled)
    }

    @Test
    fun remoteMcpConnectionProjectionContainsOnlySafeUserActionFields() {
        val plugins = PluginDomainSnapshot.EMPTY.copy(
            phase = PluginCatalogPhase.READY,
            connectionAction = PluginConnectionActionSnapshot(
                pluginId = "tasks-plugin",
                serverId = "tasks",
                kind = PluginConnectionActionKind.CONNECT_REMOTE_MCP,
                titleResource = ai.hans.standard.R.string.presentation_mcp_connection_required,
                messageResource = ai.hans.standard.R.string.presentation_mcp_connect_missing,
                actionLabelResource = ai.hans.standard.R.string.presentation_mcp_connect_action,
            ),
        )

        val action = checkNotNull(
            HansClientUiProjector.project(
                snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(plugins = plugins),
                HansLocalUiState(),
                HansSettings(), text = localizationText).plugins.connectionAction,
        )

        assertEquals("tasks-plugin", action.pluginId)
        assertEquals("tasks", action.serverId)
        assertEquals(PluginConnectionActionKind.CONNECT_REMOTE_MCP, action.kind)
        val projection = action.toString()
        assertFalse(projection.contains("https://"))
        assertFalse(projection.contains("handle", ignoreCase = true))
        assertFalse(projection.contains("digest", ignoreCase = true))
        assertFalse(projection.contains("token", ignoreCase = true))
        assertFalse(projection.contains("2".repeat(64)))
    }

    @Test
    fun pluginDetailsRequireExactLocalSelectionAndDropUnsafeLinks() {
        val installedA = pluginCard("a", installed = true, installable = true)
        val installedB = pluginCard("b", installed = true, installable = true)
        val detail = pluginDetail(
            handle = installedA.handle,
            apps = listOf(
                PluginAppSummary(
                    id = "drive",
                    name = "Drive",
                    description = "Dateien verbinden",
                    installUrl = "https://example.com/connect",
                ),
                PluginAppSummary(
                    id = "unsafe",
                    name = "Unsicher",
                    description = null,
                    installUrl = "http://example.com/connect",
                ),
            ),
            shareUrl = "https://example.com/plugin",
        )
        val client = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            plugins = PluginDomainSnapshot.EMPTY.copy(
                phase = PluginCatalogPhase.READY,
                plugins = listOf(installedA, installedB),
                selectedPlugin = detail,
            ),
        )

        val notLocallySelected = HansClientUiProjector.project(
            client,
            HansLocalUiState(),
            HansSettings(), text = localizationText).plugins
        assertNull(notLocallySelected.selectedPluginId)
        assertNull(notLocallySelected.selectedPlugin)

        val otherLocallySelected = HansClientUiProjector.project(
            client,
            HansLocalUiState(selectedPluginHandle = installedB.handle),
            HansSettings(), text = localizationText).plugins
        assertEquals(installedB.handle.value, otherLocallySelected.selectedPluginId)
        assertNull(otherLocallySelected.selectedPlugin)
        assertTrue(otherLocallySelected.selectedPluginLoading)

        val staleResponseAfterConfirmedOtherRead = HansClientUiProjector.project(
            client.copy(
                plugins = client.plugins.copy(
                    operations = listOf(
                        PluginOperationSnapshot(
                            operationId = "read-b-confirmed-before-late-a",
                            kind = PluginOperationKind.READ_PLUGIN,
                            target = installedB.handle,
                            status = PluginOperationStatus.SUCCESS,
                            retryable = false,
                            failure = null,
                        ),
                    ),
                ),
            ),
            HansLocalUiState(selectedPluginHandle = installedB.handle),
            HansSettings(), text = localizationText).plugins
        assertNull(staleResponseAfterConfirmedOtherRead.selectedPlugin)
        assertFalse(staleResponseAfterConfirmedOtherRead.selectedPluginLoading)
        assertFalse(staleResponseAfterConfirmedOtherRead.selectedPluginErrorMessage.isBlank())

        val selected = HansClientUiProjector.project(
            client,
            HansLocalUiState(selectedPluginHandle = installedA.handle),
            HansSettings(), text = localizationText).plugins.selectedPlugin!!
        assertEquals(installedA.handle.value, selected.id)
        assertEquals("https://example.com/plugin", selected.shareUrl)
        assertEquals("https://example.com/connect", selected.apps.first().connectionUrl)
        assertNull(selected.apps.last().connectionUrl)
        assertEquals(false, selected.skills.single().enabled)
    }

    @Test
    fun pluginProjectionKeepsConfirmedSkillStateAndClearsSupersededFailure() {
        val installed = pluginCard("d", installed = true, installable = true)
        val failed = PluginOperationSnapshot(
            operationId = "configure-failed",
            kind = PluginOperationKind.CONFIGURE_SKILL,
            target = installed.handle,
            status = PluginOperationStatus.FAILURE,
            retryable = true,
            failure = ai.hans.standard.plugins.PluginOperationFailure.REMOTE_REJECTED,
        )
        val confirmed = failed.copy(
            operationId = "configure-confirmed",
            status = PluginOperationStatus.SUCCESS,
            retryable = false,
            failure = null,
        )
        val pending = confirmed.copy(
            operationId = "configure-pending",
            status = PluginOperationStatus.PENDING,
        )
        val base = PluginDomainSnapshot.EMPTY.copy(
            phase = PluginCatalogPhase.READY,
            plugins = listOf(installed),
            selectedPlugin = pluginDetail(installed.handle),
        )

        val superseded = projectPluginDetails(
            installed,
            base.copy(operations = listOf(failed, confirmed)),
        )
        assertEquals("", superseded.operationErrorMessage)
        assertFalse(superseded.skillChangePending)

        val waiting = projectPluginDetails(
            installed,
            base.copy(operations = listOf(failed, confirmed, pending)),
        )
        assertFalse(waiting.skills.single().enabled)
        assertTrue(waiting.skillChangePending)
        assertEquals("", waiting.operationErrorMessage)
    }

    @Test
    fun uninstallAndMarketplaceRemainPendingUntilCatalogProof() {
        val installed = pluginCard("e", installed = true, installable = true)
        val uninstall = PluginOperationSnapshot(
            operationId = "uninstall-confirmed",
            kind = PluginOperationKind.UNINSTALL_PLUGIN,
            target = installed.handle,
            status = PluginOperationStatus.SUCCESS,
            retryable = false,
            failure = null,
        )
        val refresh = PluginOperationSnapshot(
            operationId = "catalog-proof",
            kind = PluginOperationKind.REFRESH_PLUGINS,
            target = null,
            status = PluginOperationStatus.PENDING,
            retryable = false,
            failure = null,
        )
        val marketplace = PluginOperationSnapshot(
            operationId = "marketplace-refresh",
            kind = PluginOperationKind.REFRESH_MARKETPLACE,
            target = null,
            status = PluginOperationStatus.PENDING,
            retryable = false,
            failure = null,
        )
        val plugins = PluginDomainSnapshot.EMPTY.copy(
            phase = PluginCatalogPhase.READY,
            plugins = listOf(installed),
            selectedPlugin = pluginDetail(installed.handle),
            operations = listOf(uninstall, refresh, marketplace),
        )
        val ui = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(plugins = plugins),
            HansLocalUiState(selectedPluginHandle = installed.handle),
            HansSettings(), text = localizationText).plugins

        assertTrue(ui.selectedPlugin!!.uninstallConfirmationPending)
        assertTrue(ui.marketplaceRefreshing)

        val removed = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
                plugins = plugins.copy(
                    plugins = emptyList(),
                    selectedPlugin = null,
                    operations = listOf(
                        uninstall,
                        refresh.copy(status = PluginOperationStatus.SUCCESS),
                    ),
                ),
            ),
            HansLocalUiState(selectedPluginHandle = installed.handle),
            HansSettings(), text = localizationText).plugins
        assertNull(removed.selectedPluginId)
        assertNull(removed.selectedPlugin)

        val stillInstalled = HansClientUiProjector.project(
            snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
                plugins = plugins.copy(
                    operations = listOf(
                        uninstall,
                        refresh.copy(status = PluginOperationStatus.SUCCESS),
                    ),
                ),
            ),
            HansLocalUiState(selectedPluginHandle = installed.handle),
            HansSettings(), text = localizationText).plugins.selectedPlugin!!
        assertFalse(stillInstalled.uninstallConfirmationPending)
        assertEquals(
            "Die Deinstallation wurde vom Plugin-Katalog nicht bestätigt.",
            stillInstalled.operationErrorMessage,
        )
    }

    @Test
    fun marketplaceRefreshWaitsForItsOwnCatalogProofAndReportsProofFailure() {
        val marketplace = PluginOperationSnapshot(
            operationId = "marketplace-refresh",
            kind = PluginOperationKind.REFRESH_MARKETPLACE,
            target = null,
            status = PluginOperationStatus.SUCCESS,
            retryable = false,
            failure = null,
        )
        val earlierRefresh = PluginOperationSnapshot(
            operationId = "unrelated-earlier-refresh",
            kind = PluginOperationKind.REFRESH_PLUGINS,
            target = null,
            status = PluginOperationStatus.SUCCESS,
            retryable = false,
            failure = null,
        )
        val waiting = snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(
            plugins = PluginDomainSnapshot.EMPTY.copy(
                phase = PluginCatalogPhase.READY,
                operations = listOf(earlierRefresh, marketplace),
            ),
        )

        val waitingUi = HansClientUiProjector.project(
            waiting,
            HansLocalUiState(),
            HansSettings(), text = localizationText).plugins
        assertTrue(waitingUi.marketplaceRefreshing)
        assertEquals("", waitingUi.marketplaceMessage)

        val failedProof = PluginOperationSnapshot(
            operationId = "marketplace-catalog-proof",
            kind = PluginOperationKind.REFRESH_PLUGINS,
            target = null,
            status = PluginOperationStatus.FAILURE,
            retryable = true,
            failure = ai.hans.standard.plugins.PluginOperationFailure.REMOTE_REJECTED,
        )
        val failedUi = HansClientUiProjector.project(
            waiting.copy(plugins = waiting.plugins.copy(operations = listOf(
                earlierRefresh,
                marketplace,
                failedProof,
            ))),
            HansLocalUiState(),
            HansSettings(), text = localizationText).plugins
        assertFalse(failedUi.marketplaceRefreshing)
        assertEquals(
            "Der Plugin-Katalog konnte nach der Marketplace-Aktualisierung nicht geladen werden.",
            failedUi.marketplaceMessage,
        )
    }

    @Test
    fun pluginHttpsValidationRejectsUserInfoMalformedAndNonHttpsUrls() {
        assertEquals(
            "https://example.com/connect?source=hans",
            validatedHttpsPluginLinkOrNull("https://example.com/connect?source=hans"),
        )
        assertNull(validatedHttpsPluginLinkOrNull("http://example.com"))
        assertNull(validatedHttpsPluginLinkOrNull("https://user@example.com"))
        assertNull(validatedHttpsPluginLinkOrNull("https:///missing-host"))
        assertNull(validatedHttpsPluginLinkOrNull("not a url"))
        assertNull(validatedHttpsPluginLinkOrNull("https://example.com\\@attacker.invalid"))
        assertNull(validatedHttpsPluginLinkOrNull("https://example.com/\u202Etxt"))
    }

    private fun projectPluginDetails(
        card: PluginCard,
        plugins: PluginDomainSnapshot,
    ): PluginDetailUiModel = HansClientUiProjector.project(
        snapshot(AccountPhase.SIGNED_IN, ClientSessionPhase.READY).copy(plugins = plugins),
        HansLocalUiState(selectedPluginHandle = card.handle),
        HansSettings(), text = localizationText).plugins.selectedPlugin!!

    private fun pluginDetail(
        handle: PluginHandle,
        apps: List<PluginAppSummary> = emptyList(),
        shareUrl: String? = null,
    ): PluginDetailSnapshot = PluginDetailSnapshot(
        handle = handle,
        description = "Vollständige Plugin-Beschreibung",
        apps = apps,
        skills = listOf(
            PluginSkillSummary(
                name = "phone-skill",
                description = "Steuert das Telefon",
                enabled = false,
                displayName = "Telefon",
                shortDescription = "Telefonfunktionen",
                iconSmallUrl = null,
            ),
        ),
        hooks = listOf(PluginHookSummary("hook-1", "sessionStart")),
        mcpServerCount = 2,
        scheduledTaskCount = 3,
        shareUrl = shareUrl,
    )

    private fun snapshot(
        accountPhase: AccountPhase,
        sessionPhase: ClientSessionPhase,
    ): CodexClientSnapshot = CodexClientSnapshot(
        runtimePhase = ClientRuntimePhase.READY,
        sessionPhase = sessionPhase,
        generation = 1,
        session = SessionUiSnapshot(
            account = AccountUiSnapshot(accountPhase, null, null, null, null, null),
            currentThreadId = if (accountPhase == AccountPhase.SIGNED_IN) "thread-1" else null,
            threads = emptyList(),
            delivery = DeliveryUiSnapshot(1, 1, null, false),
        ),
        models = emptyList(),
        deviceCodeLogin = null,
        outboundTimeline = emptyList(),
        timeline = emptyList(),
        pendingSelection = null,
        confirmedSelection = DispatchSelection(
            DispatchOptions.DEFAULT.model,
            DispatchOptions.DEFAULT.effort,
        ),
        problem = null,
    )

    private fun distinctEffortModels(): List<CodexModel> = listOf(
        model(
            id = "gpt-5.6-luna",
            defaultEffort = ReasoningEffort.MEDIUM,
            efforts = setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM),
            isDefault = true,
        ),
        model(
            id = "gpt-5.6-terra",
            defaultEffort = ReasoningEffort.HIGH,
            efforts = setOf(ReasoningEffort.HIGH, ReasoningEffort.MAX),
        ),
        model(
            id = "gpt-5.6-sol",
            defaultEffort = ReasoningEffort.ULTRA,
            efforts = setOf(ReasoningEffort.MAX, ReasoningEffort.ULTRA),
        ),
    )

    private fun model(
        id: String,
        defaultEffort: ReasoningEffort,
        efforts: Set<ReasoningEffort>,
        isDefault: Boolean = false,
    ): CodexModel = CodexModel(
        catalogId = id,
        wireModel = id,
        displayName = id,
        description = "fixture",
        hidden = false,
        isDefault = isDefault,
        defaultEffort = defaultEffort,
        supportedEfforts = efforts,
        defaultServiceTier = null,
        serviceTiers = emptyList(),
    )

    private fun item(
        id: String,
        role: ClientTimelineRole,
        text: String,
        order: Long,
        turnId: String? = null,
    ): ClientTimelineItem = ClientTimelineItem(
        id = id,
        role = role,
        text = text,
        order = order,
        revision = order,
        complete = true,
        status = ClientTimelineStatus.COMPLETE,
        turnId = turnId,
    )

    private fun pluginCard(
        suffix: String,
        installed: Boolean,
        installable: Boolean,
    ): PluginCard = PluginCard(
        handle = PluginHandle(suffix.repeat(64)),
        pluginId = "plugin-$suffix",
        name = "plugin_$suffix",
        displayName = "${if (installed) "Installed" else "Available"} $suffix",
        shortDescription = "Description $suffix",
        marketplaceDisplayName = "Hans",
        installed = installed,
        enabled = installed,
        availability = PluginAvailability.AVAILABLE,
        disabledReason = null,
        installPolicy = if (installable) {
            PluginInstallPolicy.AVAILABLE
        } else {
            PluginInstallPolicy.NOT_AVAILABLE
        },
        authPolicy = PluginAuthPolicy.ON_USE,
        sourceKind = PluginSourceKind.REMOTE,
        logoUrl = null,
        logoDarkUrl = null,
        capabilities = emptyList(),
        featured = false,
    )
}
