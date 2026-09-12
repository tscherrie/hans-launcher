package ai.hans.standard.setup

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Device-local state: grants and physical-key evidence must never cloud-restore to another phone. */
class AtomicFileHansSetupStorage(
    context: Context,
    file: File = File(context.applicationContext.noBackupFilesDir, FILE_NAME),
) : HansSetupStorage {
    private val atomicFile = AtomicFile(file)

    @Synchronized
    override fun read(): HansSetupDocument {
        if (!atomicFile.baseFile.exists()) return HansSetupDocument()
        val bytes = atomicFile.readFully()
        require(bytes.size <= MAX_FILE_BYTES) { "setup_storage_limit" }
        return decode(bytes.toString(Charsets.UTF_8))
    }

    @Synchronized
    override fun write(document: HansSetupDocument) {
        val bytes = encode(document).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "setup_storage_limit" }
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (failure: Exception) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    private fun encode(document: HansSetupDocument): String = JSONObject()
        .put("version", STORAGE_VERSION)
        .put("revision", document.revision)
        .put("started", document.started)
        .put("currentStep", document.currentStep.name)
        .put(
            "steps",
            JSONObject().also { root ->
                document.steps.entries.sortedBy { it.key.ordinal }.forEach { (step, record) ->
                    root.put(
                        step.name,
                        JSONObject()
                            .put("status", record.status.name)
                            .put("generation", record.generation)
                            .put("operationNonce", record.operationNonce ?: JSONObject.NULL)
                            .put("detailCode", record.detailCode ?: JSONObject.NULL)
                            .put("requestedAtMillis", record.requestedAtMillis ?: JSONObject.NULL)
                            .put("verifiedAtMillis", record.verifiedAtMillis ?: JSONObject.NULL)
                            .put("liveStartObserved", record.liveStartObserved)
                            .put("auxiliaryEvidenceObserved", record.auxiliaryEvidenceObserved),
                    )
                }
            },
        )
        .put("inputChoice", document.inputChoice?.name ?: JSONObject.NULL)
        .put("cameraHoldEnabled", document.cameraHoldEnabled ?: JSONObject.NULL)
        .put("requestedModel", document.requestedModel ?: JSONObject.NULL)
        .put("requestedReasoningEffort", document.requestedReasoningEffort ?: JSONObject.NULL)
        .put("effectiveModel", document.effectiveModel ?: JSONObject.NULL)
        .put("effectiveReasoningEffort", document.effectiveReasoningEffort ?: JSONObject.NULL)
        .put(
            "optionalCapabilities",
            JSONObject().also { root ->
                document.optionalCapabilities.entries
                    .sortedBy { it.key.ordinal }
                    .forEach { (capability, record) ->
                        root.put(
                            capability.name,
                            JSONObject()
                                .put("decision", record.decision?.name ?: JSONObject.NULL)
                                .put("status", record.status.name)
                                .put("effective", record.effective ?: JSONObject.NULL),
                        )
                    }
            },
        )
        .put("profileConfirmed", document.profileConfirmed)
        .put("updatedAtMillis", document.updatedAtMillis)
        .toString()

    private fun decode(raw: String): HansSetupDocument {
        val root = JSONObject(raw)
        require(root.optInt("version") == STORAGE_VERSION) { "setup_storage_version" }
        val encodedSteps = root.getJSONObject("steps")
        require(encodedSteps.length() <= HansSetupStep.entries.size) { "setup_step_limit" }
        val steps = buildMap {
            val names = encodedSteps.keys()
            while (names.hasNext()) {
                val step = HansSetupStep.valueOf(names.next())
                val item = encodedSteps.getJSONObject(step.name)
                put(
                    step,
                    HansSetupStepRecord(
                        status = HansSetupStepStatus.valueOf(item.getString("status")),
                        generation = item.getLong("generation"),
                        operationNonce = item.nullableString("operationNonce"),
                        detailCode = item.nullableString("detailCode"),
                        requestedAtMillis = item.nullableLong("requestedAtMillis"),
                        verifiedAtMillis = item.nullableLong("verifiedAtMillis"),
                        liveStartObserved = item.getBoolean("liveStartObserved"),
                        auxiliaryEvidenceObserved =
                            item.optBoolean("auxiliaryEvidenceObserved", false),
                    ),
                )
            }
        }
        val encodedOptional = root.optJSONObject("optionalCapabilities")
        require(
            encodedOptional == null ||
                encodedOptional.length() <= HansSetupOptionalCapability.entries.size,
        ) { "setup_optional_capability_limit" }
        val optionalCapabilities = buildMap {
            if (encodedOptional != null) {
                val names = encodedOptional.keys()
                while (names.hasNext()) {
                    val capability = HansSetupOptionalCapability.valueOf(names.next())
                    val item = encodedOptional.getJSONObject(capability.name)
                    put(
                        capability,
                        HansSetupOptionalCapabilityRecord(
                            decision = item.nullableString("decision")
                                ?.let(HansSetupCapabilityDecision::valueOf),
                            status = HansSetupStepStatus.valueOf(item.getString("status")),
                            effective = item.nullableBoolean("effective"),
                        ),
                    )
                }
            }
        }
        return HansSetupDocument(
            revision = root.getLong("revision"),
            started = root.getBoolean("started"),
            currentStep = HansSetupStep.valueOf(root.getString("currentStep")),
            steps = steps,
            inputChoice = root.nullableString("inputChoice")?.let(HansSetupInputChoice::valueOf),
            cameraHoldEnabled = root.nullableBoolean("cameraHoldEnabled"),
            requestedModel = root.nullableString("requestedModel"),
            requestedReasoningEffort = root.nullableString("requestedReasoningEffort"),
            effectiveModel = root.nullableString("effectiveModel"),
            effectiveReasoningEffort = root.nullableString("effectiveReasoningEffort"),
            optionalCapabilities = optionalCapabilities,
            profileConfirmed = root.getBoolean("profileConfirmed"),
            updatedAtMillis = root.getLong("updatedAtMillis"),
        )
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun JSONObject.nullableLong(name: String): Long? =
        if (isNull(name)) null else getLong(name)

    private fun JSONObject.nullableBoolean(name: String): Boolean? =
        if (isNull(name)) null else getBoolean(name)

    companion object {
        const val FILE_NAME = "hans-setup-state-v1.json"
        private const val STORAGE_VERSION = 1
        private const val MAX_FILE_BYTES = 128 * 1_024
    }
}
