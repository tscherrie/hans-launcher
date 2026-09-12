package ai.hans.standard.notifications

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the actual codec; Android variant uses the platform JSONObject implementation. */
@RunWith(AndroidJUnit4::class)
class AndroidNotificationSynthesisStrictJsonTest {
    @Test fun duplicateDecisionAndSummaryAreRejected() {
        for (document in listOf(
            """{"decision":"silent","decision":"silent","reason":"not_actionable"}""",
            """{"decision":"announce","summary":"Erster Hinweis.","summary":"Zweiter Hinweis.","urgency":"normal"}""",
            """{"decision":"announce","summary":"Hinweis.","summ\u0061ry":"Anderer Hinweis.","urgency":"normal"}""",
        )) assertNull(document, RestrictedNotificationSynthesisCodec.decode(document))
    }

    @Test fun trailingTextAndLenientJsonAreRejected() {
        for (document in listOf(
            """{"decision":"silent","reason":"not_actionable"} trailing""",
            """{"decision":"silent","reason":"not_actionable"}{}""",
            """{'decision':'silent','reason':'not_actionable'}""",
            """{"decision":"silent","reason":"not_actionable",}""",
            """{/*comment*/"decision":"silent","reason":"not_actionable"}""",
        )) assertNull(document, RestrictedNotificationSynthesisCodec.decode(document))
    }

    @Test fun validSilentAndAnnounceRemainAccepted() {
        assertTrue(RestrictedNotificationSynthesisCodec.decode(
            """{"decision":"silent","reason":"not_actionable"}"""
        ) is RestrictedNotificationSynthesisResult.Silent)
        assertTrue(RestrictedNotificationSynthesisCodec.decode(
            """{"decision":"announce","summary":"Kalender: Ein wichtiger Termin wartet – soll ich mit dir die nächsten Schritte zu diesem Hinweis durchgehen?","urgency":"high"}""" + "\r\n"
        ) is RestrictedNotificationSynthesisResult.Announce)
    }

    @Test fun actionOfferRequiresAnExplicitRecipientInsteadOfAPronoun() {
        assertTrue(RestrictedNotificationSynthesisCodec.decode(
            """{"decision":"announce","summary":"Donika möchte dringend telefonieren – soll ich Donika über WhatsApp zurückrufen?","urgency":"high"}"""
        ) is RestrictedNotificationSynthesisResult.Announce)
        assertEquals(
            RestrictedNotificationSynthesisResult.Silent(
                NotificationDismissalReason.INSUFFICIENT_INFORMATION,
            ),
            RestrictedNotificationSynthesisCodec.decode(
                """{"decision":"announce","summary":"Donika möchte dringend telefonieren – soll ich sie über WhatsApp zurückrufen?","urgency":"high"}""",
            ),
        )
    }
}
