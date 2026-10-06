package ai.hans.standard.backup

import ai.hans.standard.automations.AutomationDefinition
import ai.hans.standard.automations.AutomationId
import ai.hans.standard.automations.AutomationRetryPolicy
import ai.hans.standard.automations.AutomationSchedule
import ai.hans.standard.automations.AutomationTimeZone
import ai.hans.standard.automations.CodexAutomationTarget
import ai.hans.standard.automations.MissedRunPolicy
import ai.hans.standard.plugins.PluginSourceKind
import ai.hans.standard.settings.HansSettings
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HansBackupDocumentCodecTest {
    @Test
    fun `Codex voice survives portable backup independently from API and speech voices`() {
        val input = payload().let { it.copy(settings = it.settings.copy(codexLiveVoice = "ember")) }
        val restored = HansBackupDocumentCodec.decode(HansBackupDocumentCodec.encode(input, 1L)).payload
        assertEquals(input, restored)
        assertEquals("ember", restored.settings.codexLiveVoice)
        assertEquals("willow", restored.settings.liveVoice)
        assertEquals("nova", restored.settings.voice)
    }

    @Test
    fun `round trip contains only portable state`() {
        val payload = payload()

        val bytes = HansBackupDocumentCodec.encode(payload, 123_456L)
        val restored = HansBackupDocumentCodec.decode(bytes)

        assertEquals(123_456L, restored.createdAtEpochMillis)
        assertEquals(payload, restored.payload)
        val raw = bytes.toString(Charsets.UTF_8)
        listOf(
            "access_token",
            "refresh_token",
            "api_key",
            "keystore",
            "notification",
            "transcript",
            "session",
            "lease",
            "receipt",
            "/data/user/",
        ).forEach { forbidden -> assertFalse(forbidden, raw.contains(forbidden, ignoreCase = true)) }
    }

    @Test
    fun `round trip preserves live Willow independently from TTS Nova`() {
        val bytes = HansBackupDocumentCodec.encode(payload(), 1L)
        val serialized = JSONObject(bytes.toString(Charsets.UTF_8))
            .getJSONObject("payload").getJSONObject("settings")
        val restored = HansBackupDocumentCodec.decode(bytes).payload.settings

        assertEquals("willow", serialized.getString("liveVoice"))
        assertEquals("nova", serialized.getString("voice"))
        assertEquals("willow", restored.liveVoice)
        assertEquals("nova", restored.voice)
        assertEquals(payload().settings, restored)
    }

    @Test
    fun `payload tampering fails integrity before import`() {
        val root = JSONObject(
            HansBackupDocumentCodec.encode(payload(), 1L).toString(Charsets.UTF_8),
        )
        root.getJSONObject("payload")
            .getJSONObject("settings")
            .put("voice", "alloy")

        val error = assertThrows(HansBackupException::class.java) {
            HansBackupDocumentCodec.decode(root.toString().toByteArray())
        }

        assertEquals("backup_integrity_mismatch", error.errorCode)
    }

    @Test
    fun `oversize document fails before json parsing`() {
        val error = assertThrows(HansBackupException::class.java) {
            HansBackupDocumentCodec.decode(ByteArray(HansBackupLimits.MAX_DOCUMENT_BYTES + 1))
        }

        assertEquals("backup_document_oversize", error.errorCode)
    }

    @Test
    fun `unknown fields cannot smuggle credential material`() {
        val root = JSONObject(
            HansBackupDocumentCodec.encode(payload(), 1L).toString(Charsets.UTF_8),
        )
        root.put("access_token", "not-an-exportable-field")

        val error = assertThrows(HansBackupException::class.java) {
            HansBackupDocumentCodec.decode(root.toString().toByteArray())
        }

        assertEquals("backup_document_fields", error.errorCode)
    }

    @Test
    fun `credential shaped user text is rejected instead of leaked`() {
        val unsafe = payload().copy(
            automations = listOf(
                automation(
                    instruction = "Call service with api_key = sk-proj-abcdefghijklmnopqrstuv",
                ),
            ),
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            HansBackupDocumentCodec.encode(unsafe, 1L)
        }

        assertTrue(failure.message.orEmpty().contains("backup_forbidden_material"))
    }

    @Test
    fun `thread target is retained only as safe retarget notice without session id`() {
        val threadId = "thread-private-12345"
        val source = automation(target = CodexAutomationTarget.ThreadBound(threadId))
        val payload = payload().copy(
            automations = listOf(source.copy(target = CodexAutomationTarget.Independent)),
            retargetedThreadBoundAutomationIds = setOf(source.id.value),
        )

        val bytes = HansBackupDocumentCodec.encode(payload, 1L)
        val restored = HansBackupDocumentCodec.decode(bytes).payload

        assertFalse(bytes.toString(Charsets.UTF_8).contains(threadId))
        assertEquals(CodexAutomationTarget.Independent, restored.automations.single().target)
        assertEquals(setOf(source.id.value), restored.retargetedThreadBoundAutomationIds)
    }

    @Test
    fun `legacy missing or null service tier migrates to explicit default`() {
        listOf(false, true).forEach { retainNullField ->
            val root = JSONObject(
                HansBackupDocumentCodec.encode(payload(), 1L).toString(Charsets.UTF_8),
            )
            val settings = root.getJSONObject("payload").getJSONObject("settings")
            if (retainNullField) {
                settings.put("serviceTier", JSONObject.NULL)
            } else {
                settings.remove("serviceTier")
            }
            val restored = HansBackupDocumentCodec.decode(resealDocument(root))
            assertEquals(
                HansSettings.DEFAULT_SERVICE_TIER,
                restored.payload.settings.serviceTier,
            )
            assertEquals("willow", restored.payload.settings.liveVoice)
            assertEquals("nova", restored.payload.settings.voice)
        }
    }

    @Test
    fun `legacy missing or null live voice defaults to Ripple independently of service tier`() {
        listOf(false, true).forEach { retainNullLiveVoice ->
            listOf("missing", "null", "present").forEach { serviceTierShape ->
                val source = payload().copy(
                    settings = payload().settings.copy(serviceTier = HansSettings.FAST_SERVICE_TIER),
                )
                val root = JSONObject(
                    HansBackupDocumentCodec.encode(source, 1L).toString(Charsets.UTF_8),
                )
                val settings = root.getJSONObject("payload").getJSONObject("settings")
                if (retainNullLiveVoice) {
                    settings.put("liveVoice", JSONObject.NULL)
                } else {
                    settings.remove("liveVoice")
                }
                when (serviceTierShape) {
                    "missing" -> settings.remove("serviceTier")
                    "null" -> settings.put("serviceTier", JSONObject.NULL)
                }

                val restored = HansBackupDocumentCodec.decode(resealDocument(root)).payload
                val expectedServiceTier = if (serviceTierShape == "present") {
                    HansSettings.FAST_SERVICE_TIER
                } else {
                    HansSettings.DEFAULT_SERVICE_TIER
                }
                val case = "liveVoice null=$retainNullLiveVoice, serviceTier=$serviceTierShape"
                assertEquals(case, "ripple", restored.settings.liveVoice)
                assertEquals(case, "nova", restored.settings.voice)
                assertEquals(
                    case,
                    source.copy(settings = source.settings.copy(
                        liveVoice = "ripple",
                        serviceTier = expectedServiceTier,
                    )),
                    restored,
                )
            }
        }
    }

    @Test
    fun `invalid live voice with valid integrity is rejected rather than copied from TTS`() {
        listOf("nova", "Willow", "", "unknown-live-voice").forEach { invalidLiveVoice ->
            val root = JSONObject(
                HansBackupDocumentCodec.encode(payload(), 1L).toString(Charsets.UTF_8),
            )
            root.getJSONObject("payload").getJSONObject("settings")
                .put("liveVoice", invalidLiveVoice)

            val error = assertThrows(HansBackupException::class.java) {
                HansBackupDocumentCodec.decode(resealDocument(root))
            }

            assertEquals(invalidLiveVoice, "backup_document_invalid", error.errorCode)
        }
    }

    private fun payload(): HansBackupPayload = HansBackupPayload(
        settings = HansSettings(
            model = "gpt-5.6-terra",
            reasoningEffort = "high",
            voice = "nova",
            speechRate = 1.5f,
            liveVoice = "willow",
        ),
        confirmedProfileSummary = "Mag Wandern und kurze, warme Antworten.",
        automations = listOf(automation()),
        plugins = listOf(
            BackupPluginReference("calendar", "Hans Katalog", PluginSourceKind.REMOTE, true),
        ),
        skills = listOf(BackupSkillChoice("calendar:daily-brief", true)),
    )

    private fun automation(
        instruction: String = "Fasse jeden Morgen meinen Kalender zusammen.",
        target: CodexAutomationTarget = CodexAutomationTarget.Independent,
    ): AutomationDefinition = AutomationDefinition(
        id = AutomationId("automation.morning"),
        revision = 3,
        enabled = true,
        schedule = AutomationSchedule(
            dtStartLocal = LocalDateTime.parse("2026-08-24T08:00:00"),
            timeZone = AutomationTimeZone.Fixed("Europe/Oslo"),
            rrule = "FREQ=DAILY;INTERVAL=1",
        ),
        missedRunPolicy = MissedRunPolicy(),
        retryPolicy = AutomationRetryPolicy(),
        target = target,
        instruction = instruction,
        updatedAt = Instant.parse("2026-08-24T06:00:00Z"),
    )

    private fun resealDocument(root: JSONObject): ByteArray {
        root.getJSONObject("integrity").put(
            "payloadSha256",
            sha256(canonicalJson(root.getJSONObject("payload")).toByteArray(Charsets.UTF_8)),
        )
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{",
            postfix = "}",
            separator = ",",
        ) { key -> "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}" }
        is JSONArray -> (0 until value.length()).joinToString(
            prefix = "[",
            postfix = "]",
            separator = ",",
        ) { index -> canonicalJson(value.get(index)) }
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> value.toString()
        else -> error("unsupported test JSON value")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
