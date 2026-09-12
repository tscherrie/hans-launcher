package ai.hans.standard.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HansSetupOutputSanitizerTest {
    @Test
    fun observedIntroLeakBecomesNaturalGermanAndKeepsQuestion() {
        val output = HansSetupOutputSanitizer.sanitizeAssistantText(
            "Der aktuelle Schritt ist `intro` und wartet noch auf deine Entscheidung. " +
                "Soll ich die Hans-Einrichtung jetzt starten?",
            setupActive = true,
        )

        assertEquals(
            "Die Einrichtung ist bereit. Soll ich die Hans-Einrichtung jetzt starten?",
            output,
        )
        assertFalse(output.contains("intro", ignoreCase = true))
    }

    @Test
    fun ordinaryNonSetupAssistantMessageIsByteForByteUnchanged() {
        val original = "In der Intro-Datei steht detailCode als Beispiel.\n`review`"

        assertEquals(
            original,
            HansSetupOutputSanitizer.sanitizeAssistantText(original, setupActive = false),
        )
    }

    @Test
    fun naturalSetupConversationIsNotRewritten() {
        val original = "Als Nächstes brauche ich den Mikrofonzugriff. Soll ich die Einstellung öffnen?"

        assertEquals(
            original,
            HansSetupOutputSanitizer.sanitizeAssistantText(original, setupActive = true),
        )
    }

    @Test
    fun introNeverAsksForANonexistentScreenConfirmation() {
        assertEquals(
            "Möchtest du die Einrichtung jetzt starten?",
            HansSetupOutputSanitizer.sanitizeAssistantText(
                "Bitte bestätige jetzt auf dem Hans-Bildschirm den Start der Einrichtung.",
                setupActive = true,
            ),
        )
    }

    @Test
    fun structuredStateDumpFailsClosedWithoutLeakingNonceOrRawStatus() {
        val raw = """
            {"currentStep":"notification_access","steps":[{"status":"verifying",
            "operationNonce":"setup_nonce_123456789","detailCode":"listener_pending",
            "verified":false}]}
        """.trimIndent()

        val output = HansSetupOutputSanitizer.sanitizeAssistantText(raw, setupActive = true)

        assertEquals(
            "Ich habe den Einrichtungsstand intern geprüft. " +
                "Möchtest du mit der Einrichtung fortfahren?",
            output,
        )
        listOf(
            "currentStep",
            "notification_access",
            "operationNonce",
            "setup_nonce_123456789",
            "detailCode",
            "listener_pending",
            "verified",
        ).forEach { forbidden -> assertFalse(output.contains(forbidden, ignoreCase = true)) }
    }

    @Test
    fun profileConfirmationNonceAndToolNameFailClosedButSafeQuestionSurvives() {
        val raw = "hans_profile.confirm confirmationNonce=ABCD_1234567890. " +
            "Soll ich diese Zusammenfassung so speichern?"

        val output = HansSetupOutputSanitizer.sanitizeAssistantText(raw, setupActive = true)

        assertEquals(
            "Ich habe den Einrichtungsstand intern geprüft. " +
                "Soll ich diese Zusammenfassung so speichern?",
            output,
        )
        assertFalse(output.contains("confirm", ignoreCase = true))
        assertFalse(output.contains("nonce", ignoreCase = true))
        assertFalse(output.contains("ABCD_1234567890"))
    }

    @Test
    fun proseToolAndStepNamesAreNaturalizedWithoutDiscardingSafeText() {
        val output = HansSetupOutputSanitizer.sanitizeAssistantText(
            "Ich prüfe mit hans_setup.get_setup_state den Schritt microphone_access. " +
                "Darf ich die Mikrofoneinstellung öffnen?",
            setupActive = true,
        )

        assertEquals(
            "Ich prüfe intern den Mikrofonzugriff. " +
                "Darf ich die Mikrofoneinstellung öffnen?",
            output,
        )
        assertFalse(output.contains("hans_setup"))
        assertFalse(output.contains("microphone_access"))
    }

    @Test
    fun rawStatusTokenIsTranslatedForChatAndTtsSafeText() {
        val output = HansSetupOutputSanitizer.sanitizeAssistantText(
            "Der Mikrofontest ist `verified`. Möchtest du weitermachen?",
            setupActive = true,
        )

        assertEquals(
            "Der Mikrofontest ist bestätigt. Möchtest du weitermachen?",
            output,
        )
        assertFalse(output.contains("verified", ignoreCase = true))
        assertTrue(output.endsWith("Möchtest du weitermachen?"))
    }

    @Test
    fun unbracedRawVerificationAssignmentFailsClosed() {
        val output = HansSetupOutputSanitizer.sanitizeAssistantText(
            "status=ok, verified=false, detailCode=listener_pending",
            setupActive = true,
        )

        assertEquals(
            "Ich habe den Einrichtungsstand intern geprüft. " +
                "Möchtest du mit der Einrichtung fortfahren?",
            output,
        )
        assertFalse(output.contains("status", ignoreCase = true))
        assertFalse(output.contains("verified", ignoreCase = true))
        assertFalse(output.contains("detailCode", ignoreCase = true))
    }

    @Test
    fun quotedAmbiguousProfileToolDoesNotLeak() {
        val output = HansSetupOutputSanitizer.sanitizeAssistantText(
            "Ich nutze jetzt `confirm`. Soll ich dein Profil speichern?",
            setupActive = true,
        )

        assertEquals(
            "Ich nutze jetzt die interne Einrichtungsprüfung. Soll ich dein Profil speichern?",
            output,
        )
        assertFalse(output.contains("confirm", ignoreCase = true))
    }

    @Test
    fun profileSequenceErrorCodeNeverReachesChatOrSpeech() {
        val output = HansSetupOutputSanitizer.sanitizeAssistantText(
            "profile_setup_step_required. Soll ich zuerst die technische Einrichtung fortsetzen?",
            setupActive = true,
        )

        assertEquals(
            "Ich habe den Einrichtungsstand intern geprüft. " +
                "Soll ich zuerst die technische Einrichtung fortsetzen?",
            output,
        )
        assertFalse(output.contains("profile_setup_step_required"))
    }
}
