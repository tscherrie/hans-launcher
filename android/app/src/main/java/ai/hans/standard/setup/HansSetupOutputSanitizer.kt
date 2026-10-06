package ai.hans.standard.setup

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver

/**
 * Last-line defence for assistant text produced while the conversational Hans setup is active.
 *
 * The setup tools intentionally return machine-readable state. The model is instructed to turn
 * that state into natural language, but visible chat and TTS must not rely on prompt compliance
 * alone. Callers must pass a trustworthy setup-turn signal; applying this to unrelated turns
 * would risk changing legitimate user-facing technical conversations.
 */
object HansSetupOutputSanitizer {
    /**
     * Sanitizes assistant-authored text only when the caller has correlated it to an active setup
     * turn. Ordinary assistant text is returned byte-for-byte unchanged.
     */
    fun sanitizeAssistantText(text: String, setupActive: Boolean, resolver: HansTextResolver): String {
        if (!setupActive || text.isBlank()) return text

        val naturalized = naturalizeKnownPhrases(text, resolver)
        if (!containsInternalSetupValue(naturalized)) return naturalized

        // A partial redaction of a state/tool JSON dump can still disclose adjacent fields or
        // produce text that sounds authoritative without its original context. Fail closed and
        // retain only a bounded, demonstrably safe trailing question when one exists.
        val safeQuestion = trailingSafeQuestion(naturalized)
        return buildString {
            append(resolver.text(R.string.integration_setup_checked))
            if (safeQuestion != null) {
                append(' ')
                append(safeQuestion)
            } else {
                append(resolver.text(R.string.integration_setup_continue))
            }
        }
    }

    private fun naturalizeKnownPhrases(text: String, resolver: HansTextResolver): String {
        var output = text

        INTRO_SCREEN_CONFIRMATION.replace(
            output,
            resolver.text(R.string.integration_setup_begin),
        ).also { output = it }

        CURRENT_STEP_SENTENCE.replace(output) { match ->
            val step = match.groups[1]?.value?.lowercase()
            if (step == "intro") {
                resolver.text(R.string.integration_setup_ready)
            } else {
                val label = stepLabels(resolver)[step]
                if (label == null) resolver.text(R.string.integration_setup_next)
                else resolver.text(R.string.integration_setup_next_label, label)
            }
        }.also { output = it }

        TOOL_WITH_PREPOSITION.replace(output, resolver.text(R.string.integration_setup_internal)).also { output = it }
        QUALIFIED_TOOL.replace(output, resolver.text(R.string.integration_setup_internal_check)).also { output = it }
        UNQUALIFIED_TOOL.replace(output, resolver.text(R.string.integration_setup_internal_check)).also { output = it }
        QUOTED_AMBIGUOUS_TOOL.replace(output, resolver.text(R.string.integration_setup_internal_check))
            .also { output = it }

        // Naturalize the well-known enum values when the model mentions one in prose. Structured
        // key/value output is still rejected by containsInternalSetupValue below.
        STEP_CONTEXT.replace(output) { match ->
            stepLabels(resolver)[match.groups[1]?.value?.lowercase()]
                ?: resolver.text(R.string.integration_setup_next_step)
        }.also { output = it }
        STEP_TOKEN.replace(output) { match ->
            stepLabels(resolver)[match.value.lowercase()] ?: resolver.text(R.string.integration_setup_next_step)
        }.also { output = it }
        QUOTED_SIMPLE_STEP.replace(output) { match ->
            stepLabels(resolver)[match.groups[1]?.value?.lowercase()]
                ?: resolver.text(R.string.integration_setup_next_step)
        }.also { output = it }
        STATUS_TOKEN.replace(output) { match ->
            statusLabels(resolver)[match.groups[1]?.value?.lowercase()] ?: resolver.text(R.string.integration_setup_checking)
        }.also { output = it }

        return output
            .replace(Regex("[ \\t]+([,.!?])"), "$1")
            .replace(Regex("[ \\t]{2,}"), " ")
    }

    private fun containsInternalSetupValue(text: String): Boolean =
        RAW_STATE_KEY.containsMatchIn(text) ||
            QUALIFIED_TOOL.containsMatchIn(text) ||
            UNQUALIFIED_TOOL.containsMatchIn(text) ||
            STEP_TOKEN.containsMatchIn(text) ||
            QUOTED_SIMPLE_STEP.containsMatchIn(text) ||
            STATUS_TOKEN.containsMatchIn(text) ||
            LABELED_NONCE.containsMatchIn(text) ||
            INTERNAL_ERROR_CODE.containsMatchIn(text) ||
            RAW_STATUS_ASSIGNMENT.containsMatchIn(text) ||
            QUOTED_AMBIGUOUS_TOOL.containsMatchIn(text) ||
            RAW_STRUCTURED_SETUP_DUMP.containsMatchIn(text)

    private fun trailingSafeQuestion(text: String): String? {
        val question = TRAILING_QUESTION.find(text)?.groupValues?.get(1)?.trim() ?: return null
        if (question.length !in 2..MAX_PRESERVED_QUESTION_CHARACTERS) return null
        if (containsInternalSetupValue(question)) return null
        return question.takeIf { it.none(Char::isISOControl) }
    }

    private fun stepLabels(resolver: HansTextResolver) = mapOf(
        "intro" to resolver.text(R.string.integration_setup_intro),
        "input_choice" to resolver.text(R.string.integration_setup_input),
        "hardware_mapping" to resolver.text(R.string.integration_setup_mapping),
        "hardware_live_test" to resolver.text(R.string.integration_setup_hardware_test),
        "camera_hold_choice" to resolver.text(R.string.integration_setup_hold_choice),
        "camera_hold_live_test" to resolver.text(R.string.integration_setup_camera_test),
        "notification_access" to resolver.text(R.string.integration_setup_notification_access),
        "notification_live_test" to resolver.text(R.string.integration_setup_notification_test),
        "accessibility_access" to resolver.text(R.string.integration_setup_accessibility),
        "accessibility_live_test" to resolver.text(R.string.integration_setup_accessibility_test),
        "home_role" to resolver.text(R.string.integration_setup_home),
        "microphone_access" to resolver.text(R.string.integration_setup_microphone),
        "voice_dictation_test" to resolver.text(R.string.integration_setup_voice_test),
        "model_reasoning" to resolver.text(R.string.integration_setup_model),
        "personal_profile" to resolver.text(R.string.integration_setup_profile),
        "review" to resolver.text(R.string.integration_setup_review),
        "complete" to resolver.text(R.string.integration_setup_complete),
    )

    private fun statusLabels(resolver: HansTextResolver) = mapOf(
        "awaiting_user" to resolver.text(R.string.integration_setup_awaiting),
        "settings_opened" to resolver.text(R.string.integration_setup_settings_opened),
        "verifying" to resolver.text(R.string.integration_setup_checking),
        "verified" to resolver.text(R.string.integration_setup_verified),
        "skipped" to resolver.text(R.string.integration_setup_skipped),
        "blocked" to resolver.text(R.string.integration_setup_blocked),
    )

    private val CURRENT_STEP_SENTENCE = Regex(
        pattern = """(?i)Der\s+aktuelle\s+Schritt\s+ist\s+[`'\"]?([a-z][a-z0-9_]{1,63})[`'\"]?(?:\s+und\s+[^.!?]*)?[.]""",
    )
    private val INTRO_SCREEN_CONFIRMATION = Regex(
        pattern = """(?i)Bitte\s+bestätige\s+jetzt\s+auf\s+dem\s+Hans-Bildschirm\s+den\s+Start\s+der\s+Einrichtung[.]?""",
    )
    private val QUALIFIED_TOOL = Regex(
        """(?i)\b(?:hans_setup|hans_profile)\.(?:get_setup_state|record_choice|request_step_ui|begin_key_capture|read_key_capture|begin_live_test|read_live_test|verify_step|advance|read|begin_interview|record_answer|propose_summary|confirm)\b""",
    )
    private val UNQUALIFIED_TOOL = Regex(
        """(?i)\b(?:get_setup_state|record_choice|request_step_ui|begin_key_capture|read_key_capture|begin_live_test|read_live_test|verify_step|begin_interview|record_answer|propose_summary)\b""",
    )
    private val TOOL_WITH_PREPOSITION = Regex(
        """(?i)\b(?:mit|über|durch)\s+(?:(?:hans_setup|hans_profile)\.)?(?:get_setup_state|record_choice|request_step_ui|begin_key_capture|read_key_capture|begin_live_test|read_live_test|verify_step|advance|read|begin_interview|record_answer|propose_summary|confirm)\b""",
    )
    private val QUOTED_AMBIGUOUS_TOOL = Regex("""(?i)[`'\"](?:advance|read|confirm)[`'\"]""")
    private val STEP_CONTEXT = Regex(
        """(?i)\b(?:den\s+)?(?:aktuellen\s+)?(?:(?:Einrichtungs|Setup)-)?Schritt\s+[`'\"]?(input_choice|hardware_mapping|hardware_live_test|camera_hold_choice|camera_hold_live_test|notification_access|notification_live_test|accessibility_access|accessibility_live_test|home_role|microphone_access|voice_dictation_test|model_reasoning|personal_profile)[`'\"]?""",
    )
    private val STEP_TOKEN = Regex(
        """(?i)\b(?:input_choice|hardware_mapping|hardware_live_test|camera_hold_choice|camera_hold_live_test|notification_access|notification_live_test|accessibility_access|accessibility_live_test|home_role|microphone_access|voice_dictation_test|model_reasoning|personal_profile)\b""",
    )
    private val QUOTED_SIMPLE_STEP = Regex("""(?i)[`'\"](intro|review|complete)[`'\"]""")
    private val STATUS_TOKEN = Regex(
        """(?i)[`'\"]?\b(awaiting_user|settings_opened|verifying|verified|skipped|blocked)\b[`'\"]?""",
    )
    private val RAW_STATE_KEY = Regex(
        """(?i)\b(?:currentStep|operationNonce|confirmationNonce|detailCode|verifiedAtMillis|liveStartObserved|auxiliaryEvidenceObserved)\b""",
    )
    private val LABELED_NONCE = Regex(
        """(?i)\b(?:operation|confirmation)?[_ -]?nonce\b\s*[:=]\s*[A-Za-z0-9_-]{8,}""",
    )
    private val INTERNAL_ERROR_CODE = Regex(
        """(?i)\b(?:profile_setup_step_required|setup_profile_turn_action_limit)\b""",
    )
    private val RAW_STATUS_ASSIGNMENT = Regex(
        """(?i)\b(?:(?:status\s*[:=]\s*[`'\"]?(?:ok|failed|user_interaction_required|awaiting_user|settings_opened|verifying|verified|skipped|blocked))|(?:verified\s*[:=]\s*(?:true|false))|(?:step\s*[:=]\s*[`'\"]?(?:intro|input_choice|hardware_mapping|hardware_live_test|camera_hold_choice|camera_hold_live_test|notification_access|notification_live_test|accessibility_access|accessibility_live_test|home_role|microphone_access|voice_dictation_test|model_reasoning|personal_profile|review|complete)))\b""",
    )
    private val RAW_STRUCTURED_SETUP_DUMP = Regex(
        """(?is)[{\[][^}\]]{0,4096}[\"'](?:status|verified|step|steps|generation)[\"']\s*:""",
    )
    private val TRAILING_QUESTION = Regex("""(?s)([^\n.!?]{1,280}\?)\s*$""")
    private const val MAX_PRESERVED_QUESTION_CHARACTERS = 240
}
