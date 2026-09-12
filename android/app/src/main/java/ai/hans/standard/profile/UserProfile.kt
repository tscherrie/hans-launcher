package ai.hans.standard.profile

import java.util.UUID

data class ProfileAnswer(
    val topic: String,
    val question: String,
    val answer: String,
) {
    init {
        requireBoundedProfileText(topic, 80, "profile_topic")
        requireBoundedProfileText(question, 1_000, "profile_question")
        requireBoundedProfileText(answer, 8_000, "profile_answer")
    }
}

data class UserProfileDocument(
    val revision: Long = 0,
    val interviewActive: Boolean = false,
    val draftAnswers: List<ProfileAnswer> = emptyList(),
    val proposedSummary: String? = null,
    val confirmationNonce: String? = null,
    val confirmedSummary: String? = null,
    val updatedAtMillis: Long = 0,
) {
    init {
        require(revision >= 0)
        require(draftAnswers.size <= MAX_PROFILE_ANSWERS)
        proposedSummary?.let { requireBoundedProfileText(it, MAX_PROFILE_SUMMARY_CHARS, "profile_summary") }
        confirmedSummary?.let { requireBoundedProfileText(it, MAX_PROFILE_SUMMARY_CHARS, "profile_summary") }
        confirmationNonce?.let {
            require(it.matches(Regex("[a-zA-Z0-9_-]{16,128}")))
        }
        require(updatedAtMillis >= 0)
    }

    val hasConfirmedProfile: Boolean
        get() = !confirmedSummary.isNullOrBlank()
}

interface UserProfileStorage {
    /** Shared Android adapters exclude a backup rollback for this entire read/modify/write. */
    fun <T> withTransaction(block: () -> T): T = synchronized(this) { block() }

    fun read(): UserProfileDocument

    fun write(document: UserProfileDocument)

    fun clear()
}

fun interface ProfileClock {
    fun nowMillis(): Long
}

fun interface ProfileNonceSource {
    fun next(): String

    companion object {
        val UUIDS = ProfileNonceSource { UUID.randomUUID().toString().replace("-", "") }
    }
}

class UserProfileRepository(
    private val storage: UserProfileStorage,
    private val clock: ProfileClock = ProfileClock(System::currentTimeMillis),
    private val nonces: ProfileNonceSource = ProfileNonceSource.UUIDS,
) {
    @Synchronized
    fun read(): UserProfileDocument = storage.read()

    @Synchronized
    fun beginInterview(): UserProfileDocument = mutate { current ->
        current.copy(interviewActive = true)
    }

    @Synchronized
    fun recordAnswer(answer: ProfileAnswer): UserProfileDocument = mutate { current ->
        require(current.interviewActive) { "profile_interview_not_active" }
        val withoutTopic = current.draftAnswers.filterNot { it.topic == answer.topic }
        require(withoutTopic.size < MAX_PROFILE_ANSWERS) { "profile_answer_limit" }
        current.copy(
            draftAnswers = withoutTopic + answer,
            proposedSummary = null,
            confirmationNonce = null,
        )
    }

    @Synchronized
    fun proposeSummary(summary: String): UserProfileDocument = mutate { current ->
        require(current.interviewActive) { "profile_interview_not_active" }
        require(current.draftAnswers.isNotEmpty()) { "profile_draft_empty" }
        requireBoundedProfileText(summary, MAX_PROFILE_SUMMARY_CHARS, "profile_summary")
        current.copy(
            proposedSummary = summary.trim(),
            confirmationNonce = nonces.next(),
        )
    }

    @Synchronized
    fun confirm(nonce: String, explicitUserConfirmation: Boolean): UserProfileDocument =
        mutate { current ->
            require(explicitUserConfirmation) { "profile_confirmation_required" }
            require(nonce == current.confirmationNonce) { "profile_confirmation_stale" }
            val summary = current.proposedSummary?.takeIf(String::isNotBlank)
                ?: error("profile_summary_missing")
            current.copy(
                interviewActive = false,
                confirmedSummary = summary,
                proposedSummary = null,
                confirmationNonce = null,
            )
        }

    @Synchronized
    fun delete(explicitUserConfirmation: Boolean) {
        require(explicitUserConfirmation) { "profile_confirmation_required" }
        storage.withTransaction { storage.clear() }
    }

    private fun mutate(block: (UserProfileDocument) -> UserProfileDocument): UserProfileDocument =
        storage.withTransaction {
        val current = storage.read()
        val changed = block(current)
        if (changed == current) return@withTransaction current
        val next = changed.copy(
            revision = current.revision + 1,
            updatedAtMillis = clock.nowMillis(),
        )
        storage.write(next)
        next
    }
}

internal const val MAX_PROFILE_ANSWERS = 48
internal const val MAX_PROFILE_SUMMARY_CHARS = 32_000

internal fun requireBoundedProfileText(value: String, maxCharacters: Int, name: String) {
    require(value.isNotBlank()) { "$name must not be blank" }
    require(value.length <= maxCharacters) { "$name is too long" }
    require(value.none { it == '\u0000' }) { "$name contains a null character" }
}
