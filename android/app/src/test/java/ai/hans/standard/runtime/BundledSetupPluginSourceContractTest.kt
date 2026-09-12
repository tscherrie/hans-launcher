package ai.hans.standard.runtime

import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import ai.hans.standard.setup.HansSetupOptionalCapability
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledSetupPluginSourceContractTest {
    @Test
    fun sourceContainsOnlyTheValidatedManifestAndTurnBasedSkill() {
        val root = pluginRoot()
        val files = root.walkTopDown().filter(File::isFile).map {
            it.relativeTo(root).invariantSeparatorsPath
        }.toSet()
        assertEquals(
            setOf(
                ".codex-plugin/plugin.json",
                "skills/setup-hans-device/SKILL.md",
            ),
            files,
        )

        val manifest = JSONObject(File(root, ".codex-plugin/plugin.json").readText())
        assertEquals(BundledSetupPluginContract.PLUGIN_NAME, manifest.getString("name"))
        assertEquals("./skills/", manifest.getString("skills"))
        assertFalse(manifest.has("mcpServers"))
        assertFalse(manifest.has("apps"))
    }

    @Test
    fun skillPinsTheImplementedSetupToolAndStepVocabulary() {
        val skill = File(pluginRoot(), "skills/setup-hans-device/SKILL.md").readText()
        val tools = setOf(
            "get_setup_state",
            "record_choice",
            "request_step_ui",
            "begin_key_capture",
            "read_key_capture",
            "begin_live_test",
            "read_live_test",
            "verify_step",
            "accept_existing_setup",
            "advance",
        )
        tools.forEach { name -> assertTrue("missing $name", "hans_setup.$name" in skill) }
        val steps = setOf(
            "intro",
            "input_choice",
            "hardware_mapping",
            "microphone_consent",
            "microphone_access",
            "hardware_live_test",
            "camera_capture_test",
            "camera_hold_choice",
            "camera_hold_live_test",
            "app_notifications_consent",
            "app_notifications_access",
            "notification_listener_consent",
            "notification_access",
            "notification_live_test",
            "accessibility_consent",
            "accessibility_access",
            "accessibility_live_test",
            "home_role_consent",
            "home_role",
            "speech_credential_consent",
            "speech_credential_access",
            "voice_dictation_test",
            "model_reasoning",
            "optional_capabilities",
            "personal_profile",
            "review",
            "complete",
        )
        steps.forEach { step -> assertTrue("missing $step", "`$step`" in skill) }
        assertTrue("pro Antwort um genau eine Entscheidung oder Handlung" in skill)
        assertTrue("stelle dabei höchstens eine Frage" in skill)
        assertTrue("Nenne niemals interne Schrittnamen" in skill)
        assertTrue("wie ein Kennenlerngespräch anfühlen" in skill)
        assertTrue("separate Voraussetzung der Hans-App" in skill)
        assertTrue("Starte, simuliere oder beschreibe keinen Codex-gesteuerten Login" in skill)
        assertTrue("Anmeldeschritt und setzt die Einrichtung erst danach fort" in skill)
        assertTrue("ausschließlich dann durch" in skill)
        assertTrue("eine frühere Vertagung allein genügt nicht" in skill)
        assertTrue("höchstens **eine** zustandsändernde Aktion" in skill)
        assertTrue("jede weitere Android-Oberfläche" in skill)
        assertTrue("verwende `not_now`" in skill)
        assertTrue("darf den Ablauf nicht blockieren" in skill)
        assertTrue("Das bloße Öffnen ist noch kein Erfolg" in skill)
        assertTrue("Kamera- oder Speicherberechtigung" in skill)
        assertTrue("Status `verified`" in skill)
        assertTrue("echtes `LISTENING`" in skill)
        assertTrue("korrelierten `SENT`-Transkriptbeleg" in skill)
        assertTrue("nonce-gebundene Hans-Testbenachrichtigung" in skill)
        assertTrue("API-Schlüssel im Chat" in skill)
        assertTrue("maskierte, sichere Android-Eingabe" in skill)
        assertTrue("verarbeite den Schlüssel niemals selbst" in skill)
        HansSetupOptionalCapability.entries.map { it.name.lowercase() }.forEach { capability ->
            assertTrue("missing optional $capability", "`$capability`" in skill)
        }
        assertTrue("`phoneActionPolicy`" in skill)
        HansPhoneActionPolicy.entries.forEach { policy ->
            assertTrue("missing policy wire value ${policy.wireValue}", "`${policy.wireValue}`" in skill)
        }
        assertTrue("Android-Laufzeitberechtigungen" in skill)
        assertTrue("currentCapability" in skill)
        assertTrue("unabhängige Kameratest bleibt verfügbar" in skill)
        setOf(
            "read",
            "begin_interview",
            "record_answer",
            "propose_summary",
            "confirm",
        ).forEach { name -> assertTrue("missing profile $name", "hans_profile.$name" in skill) }
        assertTrue("{step:\"review\",choice:\"prepare_confirmation\"}" in skill)
        assertTrue("Erst in einem neuen Turn" in skill)
        assertTrue("confirmationNonce:<zurueckgegebener Wert>" in skill)
        assertTrue("{explicitUserConfirmation:true}" in skill)
        assertFalse("Status und `detailCode`" in skill)
        assertFalse("Sprachausgabe, Stimme" in skill)
        assertFalse("hans_setup.read_state" in skill)
    }

    @Test
    fun skillExplainsSilentArchivalClaimsWithoutPromisingNativeMemoryOrBackup() {
        val skill = File(pluginRoot(), "skills/setup-hans-device/SKILL.md")
            .readText().replace(Regex("\\s+"), " ")
        assertTrue("auch aus stillen Meldungen ohne Altersablauf" in skill)
        assertTrue("100.000 Fakten und 128 MiB Datenbank" in skill)
        assertTrue("Speichern allein löst keine Sprachausgabe aus" in skill)
        assertTrue("keine bestätigten Profilantworten" in skill)
        assertTrue("weder im Android-Backup noch im derzeitigen Hans-Backup" in skill)
        assertTrue("löscht keine bestehenden Chats" in skill)
        assertTrue("android_notification_memory.status" in skill)
        assertTrue("konkrete Nutzeranweisung" in skill)
    }

    private fun pluginRoot(): File {
        val relative = "bundled-plugins/hans-setup"
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val root = generateSequence(start) { it.parentFile }
            .map { candidate -> File(candidate, relative) }
            .firstOrNull(File::isDirectory)
        assertNotNull("Bundled Hans Setup plugin source not found", root)
        return requireNotNull(root)
    }
}
