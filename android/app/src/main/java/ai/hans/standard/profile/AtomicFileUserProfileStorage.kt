package ai.hans.standard.profile

import android.content.Context
import android.util.AtomicFile
import ai.hans.standard.backup.HansBackupMaintenance
import ai.hans.standard.backup.HansBackupProcessState
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** App-private, atomic profile persistence; Android's file-based device encryption applies. */
class AtomicFileUserProfileStorage(
    context: Context,
    file: File = File(context.applicationContext.filesDir, FILE_NAME),
    private val maintenance: HansBackupMaintenance = HansBackupProcessState.maintenance,
) : UserProfileStorage {
    private val atomicFile = AtomicFile(file)

    override fun <T> withTransaction(block: () -> T): T = maintenance.withStateAccess(block)

    override fun read(): UserProfileDocument = withTransaction {
        if (!atomicFile.baseFile.exists()) return@withTransaction UserProfileDocument()
        val bytes = atomicFile.readFully()
        require(bytes.size <= MAX_PROFILE_FILE_BYTES) { "profile_storage_limit" }
        decode(bytes.toString(Charsets.UTF_8))
    }

    override fun write(document: UserProfileDocument) = withTransaction {
        val bytes = encode(document).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PROFILE_FILE_BYTES) { "profile_storage_limit" }
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (failure: Exception) {
            atomicFile.failWrite(stream)
            throw failure
        } finally {
            bytes.fill(0)
        }
    }

    override fun clear() = withTransaction {
        atomicFile.delete()
    }

    private fun encode(document: UserProfileDocument): String = JSONObject()
        .put("version", 1)
        .put("revision", document.revision)
        .put("interviewActive", document.interviewActive)
        .put(
            "draftAnswers",
            JSONArray().also { array ->
                document.draftAnswers.forEach { answer ->
                    array.put(
                        JSONObject()
                            .put("topic", answer.topic)
                            .put("question", answer.question)
                            .put("answer", answer.answer),
                    )
                }
            },
        )
        .put("proposedSummary", document.proposedSummary ?: JSONObject.NULL)
        .put("confirmationNonce", document.confirmationNonce ?: JSONObject.NULL)
        .put("confirmedSummary", document.confirmedSummary ?: JSONObject.NULL)
        .put("updatedAtMillis", document.updatedAtMillis)
        .toString()

    private fun decode(raw: String): UserProfileDocument {
        val root = JSONObject(raw)
        require(root.optInt("version") == 1) { "profile_storage_version" }
        val answers = root.getJSONArray("draftAnswers")
        require(answers.length() <= MAX_PROFILE_ANSWERS) { "profile_answer_limit" }
        return UserProfileDocument(
            revision = root.getLong("revision"),
            interviewActive = root.getBoolean("interviewActive"),
            draftAnswers = buildList {
                repeat(answers.length()) { index ->
                    val item = answers.getJSONObject(index)
                    add(
                        ProfileAnswer(
                            topic = item.getString("topic"),
                            question = item.getString("question"),
                            answer = item.getString("answer"),
                        ),
                    )
                }
            },
            proposedSummary = root.nullableString("proposedSummary"),
            confirmationNonce = root.nullableString("confirmationNonce"),
            confirmedSummary = root.nullableString("confirmedSummary"),
            updatedAtMillis = root.getLong("updatedAtMillis"),
        )
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private companion object {
        const val FILE_NAME = "hans-user-profile-v1.json"
        const val MAX_PROFILE_FILE_BYTES = 256 * 1_024
    }
}
