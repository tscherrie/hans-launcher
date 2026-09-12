package ai.hans.standard.notifications

import ai.hans.standard.phone.notifications.NotificationEventKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationImportantActionPolicyTest {
    @Test
    fun bothPromptsExplicitlyKeepRoutineAuthenticationAndMarketingSilent() {
        listOf(
            RestrictedNotificationClassificationPrompt.DEVELOPER_INSTRUCTIONS,
            RestrictedNotificationSynthesisPrompt.DEVELOPER_INSTRUCTIONS,
        ).forEach { prompt ->
            assertTrue(prompt.contains("one-time codes"))
            assertTrue(prompt.contains("marketing"))
            assertTrue(prompt.contains("if this was not you"))
            assertTrue(prompt.contains("routine email important"))
        }
        assertTrue(RestrictedNotificationSynthesisPrompt.DEVELOPER_INSTRUCTIONS.contains("Every announcement MUST end"))
    }

    @Test
    fun routineLoginAndVerificationCannotBeSpokenEvenWhenClassifierSaysCritical() {
        listOf(
            "New login in Berlin",
            "Your sign-in was successful",
            "Login successful. If this was not you, report unauthorized access.",
            "Anmeldung erfolgreich bestätigt",
            "Anmeldebestätigung für dein Konto",
            "Verification complete. You can now use your account.",
            "Login confirmation. If an unknown device signed in, review your account.",
        ).forEach { body ->
            var calls = 0
            val result = RestrictedNotificationDecisionPipeline(
                modelRunner = RestrictedNotificationModelStageRunner {
                    calls += 1
                    criticalPlan()
                },
                enrichmentProvider = NotificationEnrichmentProvider { _, _ -> error("Routine notice must not enrich") },
            ).decide(envelope(body), NotificationRelevanceContext())
            assertEquals(body, NotificationDismissalReason.NOT_ACTIONABLE, (result as RestrictedTriageDecision.NotRelevant).reason)
            assertTrue("No synthesis is allowed: $body; calls=$calls", calls <= 1)
        }
    }

    @Test
    fun routineSpeechVetoPreservesIndependentlyValidatedMemoryCandidates() {
        val quote = "The appointment is tomorrow at nine"
        val plan = JSONObject(criticalPlan())
            .put("memoryCandidatesVersion", 1)
            .put("memoryCandidates", JSONArray().put(JSONObject()
                .put("type", "event_detail")
                .put("sourceField", "text")
                .put("quote", quote)))
            .toString()
        val result = RestrictedNotificationDecisionPipeline(
            modelRunner = RestrictedNotificationModelStageRunner { plan },
        ).decide(envelope("Login successful. $quote"), NotificationRelevanceContext()) as RestrictedTriageDecision.NotRelevant

        assertEquals(NotificationDismissalReason.NOT_ACTIONABLE, result.reason)
        assertEquals(quote, result.memoryCandidates.single().quote)
    }

    @Test
    fun explicitSuspiciousAccessSurvivesRoutineAuthVetoWithSafeReviewQuestion() {
        val summary = "Account: Ein unbefugter Zugriff wurde blockiert – soll ich mit dir die nächsten Schritte zu diesem Hinweis durchgehen?"
        val result = decide("New login blocked after unauthorized access was detected", summary)
        assertEquals(summary, (result as RestrictedTriageDecision.SuggestUser).suggestion.summary)
        assertEquals(ValidatedNotificationActionOfferKind.REVIEW_NEXT_STEPS,
            requireNotNull(ValidatedNotificationActionOfferParser.parse(summary)).kind)
    }

    @Test
    fun importantEmailOffersOnlyAnUnsentDraftHereWithoutInventingRecipientOrAppMutation() {
        val summary = "Re: Projektfreigabe: Deine Antwort wird heute bis Mittag benötigt – soll ich dazu hier einen ungesendeten E-Mail-Antwortentwurf vorbereiten?"
        listOf("com.google.android.gm", "ch.protonmail.android", "com.microsoft.office.outlook").forEach { app ->
            val result = decide("The project deadline is noon today; please reply with your decision", summary,
                title = "Re: Projektfreigabe", source = app)
            assertEquals(summary, (result as RestrictedTriageDecision.SuggestUser).suggestion.summary)
            val offer = requireNotNull(ValidatedNotificationActionOfferParser.parse(summary))
            assertEquals(ValidatedNotificationActionOfferKind.DRAFT_EMAIL_REPLY, offer.kind)
            assertEquals("Hans", offer.app)
            assertEquals("Re: Projektfreigabe", offer.target) // Source label, not an invented addressee.
        }
    }

    @Test
    fun emailDraftFromNonEmailSourceOrWrongTitleFailsClosed() {
        val summary = "Re: Projektfreigabe: Die Entscheidung wird heute benötigt – soll ich dazu hier einen ungesendeten E-Mail-Antwortentwurf vorbereiten?"
        listOf(
            "Re: Projektfreigabe" to "com.whatsapp",
            "Andere Nachricht" to "com.google.android.gm",
            "Re: Projektfreigabe" to "com.example.email.spoof",
        ).forEach { (title, source) ->
            assertTrue(decide("Please reply before noon today", summary, title, source) is RestrictedTriageDecision.NotRelevant)
        }
    }

    @Test
    fun importantTravelNoticeHasGroundedNonExecutingNextStepOffer() {
        val summary = "Railway: Your train has been cancelled – should I help you review the next steps for this notice?"
        assertTrue(decide("Your train has been cancelled", summary, "Railway", "com.example.rail") is RestrictedTriageDecision.SuggestUser)
        assertTrue(decide("Your train has been cancelled", summary, "Airline", "com.example.rail") is RestrictedTriageDecision.NotRelevant)
    }

    @Test
    fun redactedEmailTitleUsesKnownAppLabelWithoutRestoringThePrivateTitle() {
        val summary = "Gmail: Deine Entscheidung wird heute benötigt – soll ich dazu hier einen ungesendeten E-Mail-Antwortentwurf vorbereiten?"
        assertTrue(decide("Please reply by noon today", summary, "ABCDEFGHIJKLMNOP", "com.google.android.gm") is RestrictedTriageDecision.SuggestUser)
        assertTrue(decide("Please reply by noon today", summary, "ABCDEFGHIJKLMNOP", "ch.protonmail.android") is RestrictedTriageDecision.NotRelevant)
    }

    @Test
    fun actualCredentialBreachIsNotConfusedWithConditionalLoginBoilerplate() {
        val summary = "Account: Zugangsdaten wurden offengelegt – soll ich mit dir die nächsten Schritte zu diesem Hinweis durchgehen?"
        assertTrue(decide("New login. Your password was exposed in a data breach.", summary) is RestrictedTriageDecision.SuggestUser)
        assertTrue(decide("New login. If your password was exposed in a data breach, review your account.", summary) is RestrictedTriageDecision.NotRelevant)
    }

    @Test
    fun bothStagesRetainExactIncidentWordsWhenTheNoticeDoesNotLabelCredentials() {
        val requests = mutableListOf<RestrictedNotificationModelStageRequest>()
        val summary = "Account: Ein unbefugter Zugriff wurde blockiert – soll ich mit dir die nächsten Schritte zu diesem Hinweis durchgehen?"
        val responses = ArrayDeque(listOf(criticalPlan(), announcement(summary)))
        val result = RestrictedNotificationDecisionPipeline(
            modelRunner = RestrictedNotificationModelStageRunner { request ->
                requests += request
                responses.removeFirst()
            },
        ).decide(envelope("New login. Unauthorized access detected."), NotificationRelevanceContext())
        assertTrue(result is RestrictedTriageDecision.SuggestUser)
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertTrue(request.userInput.contains("Unauthorized access"))
        }
    }

    @Test
    fun incidentWordsUsedAsSplitFieldPassphrasesStillCrossTheOriginalSecretBoundary() {
        listOf(
            "Verification code" to "suspicious access",
            "Password" to "data breach",
            "Code" to "unauthorized access",
            "Verification code" to "SUSPICIOUS ACCESS",
        ).forEach { (title, credential) ->
            val safe = sanitizeRestrictedNotificationText(title, credential, "")
            assertTrue("Credential-shaped field must not survive: $title", safe == null || !safe.text.contains(credential))
            assertTrue(RestrictedNotificationSynthesisCodec.decode(announcement(credential)) !is RestrictedNotificationSynthesisResult.Announce)
        }
    }

    @Test
    fun securitySourceCanUseFixedReviewAndEmailQuestionsWithoutWeakeningFactualSecretChecks() {
        val review = "Security alert: Ein verdächtiger Zugriff wurde erkannt – soll ich mit dir die nächsten Schritte zu diesem Hinweis durchgehen?"
        assertTrue(decide("New login. Suspicious access detected.", review, "Security alert") is RestrictedTriageDecision.SuggestUser)
        val emailTitle = "Security alert: suspicious sign-in"
        val email = "$emailTitle: Ein verdächtiger Zugriff braucht deine Antwort – soll ich dazu hier einen ungesendeten E-Mail-Antwortentwurf vorbereiten?"
        assertTrue(decide("New login. Unauthorized access detected. Please reply with the incident report.", email,
            emailTitle, "com.google.android.gm") is RestrictedTriageDecision.SuggestUser)
        listOf(
            "Security alert: Dein Code ist A1B2C3 – soll ich mit dir die nächsten Schritte zu diesem Hinweis durchgehen?",
            "$emailTitle: Dein Code ist A1B2C3 – soll ich dazu hier einen ungesendeten E-Mail-Antwortentwurf vorbereiten?",
        ).forEach { summary ->
            assertEquals(null, RestrictedNotificationSynthesisCodec.decode(announcement(summary)))
        }
    }

    @Test
    fun everyAdmittedAnnouncementHasExactlyOneSafeQuestionAndNeverActionAuthority() {
        listOf(
            "Account: Ein unbefugter Zugriff wurde blockiert.",
            "Account: Ein unbefugter Zugriff wurde blockiert – soll ich das Konto löschen?",
            "Account: Eine Zahlung ist fällig – soll ich sie bezahlen?",
            "Account: Die Frist endet heute – soll ich die E-Mail senden?",
            "Account: Ein Hinweis – soll ich helfen? – soll ich mit dir die nächsten Schritte zu diesem Hinweis durchgehen?",
        ).forEach { summary ->
            assertTrue(summary, RestrictedNotificationSynthesisCodec.decode(announcement(summary)) !is RestrictedNotificationSynthesisResult.Announce)
        }
        val instructions = RestrictedNotificationSynthesisPrompt.DEVELOPER_INSTRUCTIONS
        assertTrue(instructions.contains("generic yes, okay or do-it is insufficient"))
        assertTrue(instructions.contains("This offers text here in Hans only"))
        assertFalse(instructions.contains("announce the important fact without"))
    }

    private fun decide(body: String, summary: String, title: String = "Account", source: String = "com.example.security"): RestrictedTriageDecision {
        val responses = ArrayDeque(listOf(criticalPlan(), announcement(summary)))
        return RestrictedNotificationDecisionPipeline(
            modelRunner = RestrictedNotificationModelStageRunner { responses.removeFirst() },
        ).decide(envelope(body, title, source), NotificationRelevanceContext())
    }

    private fun criticalPlan(): String =
        """{"decision":"surface_now","reason":"critical_account_event","urgency":"high","confidence":"high","entities":[]}"""

    private fun announcement(summary: String): String = JSONObject()
        .put("decision", "announce").put("summary", summary).put("urgency", "high").toString()

    private fun envelope(body: String, title: String = "Account", source: String = "com.example.security") = UntrustedNotificationEnvelope(
        sourceSequence = 1, kind = NotificationEventKind.POSTED, observedAtEpochMillis = 1,
        packageName = source, androidKey = "synthetic", title = title, text = body, subtext = "",
        category = "message", channelId = "messages", ongoing = false, clearable = true,
    )
}
