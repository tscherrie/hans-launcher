package ai.hans.standard.notifications

import ai.hans.standard.phone.notifications.NotificationEventKind
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Pure platform JSON/regex/pipeline parity. No account, device mutation or network is used. */
@RunWith(AndroidJUnit4::class)
class NotificationImportantActionPolicyAndroidTest {
    @Test
    fun routineAuthenticationNeverSpeaksButRealIncidentCan() {
        val summary = review("Account", "Ein unbefugter Zugriff wurde blockiert")
        listOf(
            "New login in Berlin",
            "Login successful. If this was not you, report unauthorized access.",
            "Anmeldebestätigung für dein Konto",
            "Verification complete",
        ).forEach { body ->
            assertTrue(decide(body, summary) is RestrictedTriageDecision.NotRelevant)
        }
        assertTrue(decide("New login blocked after unauthorized access was detected", summary) is RestrictedTriageDecision.SuggestUser)
    }

    @Test
    fun everyAnnouncementRequiresGroundedSafeQuestion() {
        assertTrue(decide("Your train has been cancelled", review("Railway", "Dein Zug wurde gestrichen"), "Railway") is RestrictedTriageDecision.SuggestUser)
        listOf(
            "Account: Ein unbefugter Zugriff wurde blockiert.",
            "Account: Ein Zugriff wurde blockiert – soll ich das Konto löschen?",
            review("Andere Quelle", "Ein Zugriff wurde blockiert"),
        ).forEach { summary ->
            val result = runCatching { decide("Unauthorized access detected", summary) }
            result.fold(
                onSuccess = { assertTrue(it is RestrictedTriageDecision.NotRelevant) },
                onFailure = {
                    // Malformed/secret-shaped synthesis is rejected as a protocol failure;
                    // otherwise unsupported prose is a typed silent result. Neither is speech.
                    assertTrue(it is RestrictedNotificationTriageFailure)
                    assertEquals("invalid_synthesis_result", it.message)
                },
            )
        }
    }

    @Test
    fun importantEmailDraftStaysLocalAndSourceBound() {
        val summary = "Re: Projektfreigabe: Deine Entscheidung wird heute benötigt – soll ich dazu hier einen ungesendeten E-Mail-Antwortentwurf vorbereiten?"
        val body = "Please reply by noon today so the project can proceed"
        assertTrue(decide(body, summary, "Re: Projektfreigabe", "com.google.android.gm") is RestrictedTriageDecision.SuggestUser)
        assertTrue(decide(body, summary, "Re: Projektfreigabe", "com.whatsapp") is RestrictedTriageDecision.NotRelevant)
        assertTrue(decide(body, summary, "Andere E-Mail", "com.google.android.gm") is RestrictedTriageDecision.NotRelevant)
        assertEquals("Hans", requireNotNull(ValidatedNotificationActionOfferParser.parse(summary)).app)
    }

    private fun review(source: String, fact: String) =
        "$source: $fact – soll ich mit dir die nächsten Schritte zu diesem Hinweis durchgehen?"

    private fun decide(body: String, summary: String, title: String = "Account", source: String = "com.example.security"): RestrictedTriageDecision {
        val responses = ArrayDeque(listOf(
            """{"decision":"surface_now","reason":"critical_account_event","urgency":"high","confidence":"high","entities":[]}""",
            JSONObject().put("decision", "announce").put("summary", summary).put("urgency", "high").toString(),
        ))
        return RestrictedNotificationDecisionPipeline(
            modelRunner = RestrictedNotificationModelStageRunner { responses.removeFirst() },
        ).decide(UntrustedNotificationEnvelope(
            sourceSequence = 1, kind = NotificationEventKind.POSTED, observedAtEpochMillis = 1,
            packageName = source, androidKey = "synthetic", title = title, text = body, subtext = "",
            category = "message", channelId = "messages", ongoing = false, clearable = true,
        ), NotificationRelevanceContext())
    }
}
