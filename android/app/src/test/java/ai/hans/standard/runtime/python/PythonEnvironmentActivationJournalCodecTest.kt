package ai.hans.standard.runtime.python

import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonEnvironmentActivationJournalCodecTest {
    @Test
    fun roundTripsExactPreparedAndPreviousIdentities() {
        val record = record(PythonEnvironmentRecoveryState.PREPARED)

        assertEquals(
            record,
            PythonEnvironmentActivationJournalCodec.decode(
                PythonEnvironmentActivationJournalCodec.encode(record),
            ),
        )
    }

    @Test
    fun rejectsUnknownFieldsAndIdentityTampering() {
        val encoded = PythonEnvironmentActivationJournalCodec.encode(
            record(PythonEnvironmentRecoveryState.COMMITTED),
        )

        val unknownField = JSONObject(encoded.toString(StandardCharsets.UTF_8))
            .put("executablePath", "/data/local/tmp/plugin")
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        expectFailure("unexpected key") {
            PythonEnvironmentActivationJournalCodec.decode(unknownField)
        }

        val tampered = JSONObject(encoded.toString(StandardCharsets.UTF_8)).also { root ->
            root.getJSONObject("installed").put("environmentDigest", "f".repeat(64))
        }.toString().toByteArray(StandardCharsets.UTF_8)
        expectFailure("digest") {
            PythonEnvironmentActivationJournalCodec.decode(tampered)
        }
    }

    private fun record(state: PythonEnvironmentRecoveryState) =
        PythonEnvironmentActivationJournalRecord(
            transactionId = "pyenv-${"a".repeat(32)}",
            state = state,
            installed = identity("plugin", "b".repeat(64), "c".repeat(64)),
            previous = identity("plugin", "d".repeat(64), "e".repeat(64)),
            newlyInstalled = true,
        )

    private fun identity(
        pluginId: String,
        lockDigest: String,
        environmentDigest: String,
    ) = PythonEnvironmentActivationIdentity(
        pluginId = pluginId,
        target = PythonEnvironmentTarget(
            pythonVersion = "3.13.7",
            interpreterTag = "cp313",
            androidAbi = "arm64-v8a",
            minimumAndroidApi = 31,
        ),
        lockDigest = lockDigest,
        environmentDigest = environmentDigest,
    )

    private fun expectFailure(message: String, block: () -> Unit) {
        val thrown = try {
            block()
            null
        } catch (failure: Throwable) {
            failure
        }
        val actual = thrown ?: throw AssertionError("Expected failure containing '$message'")
        assertTrue(
            "Expected '$message' in '${actual.message}'",
            actual.message.orEmpty().contains(message, ignoreCase = true),
        )
    }
}
