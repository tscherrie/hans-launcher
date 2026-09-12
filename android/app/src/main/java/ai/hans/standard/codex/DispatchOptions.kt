package ai.hans.standard.codex

@JvmInline
value class ReasoningEffort private constructor(val wireValue: String) {
    companion object {
        val MINIMAL = of("minimal")
        val LOW = of("low")
        val MEDIUM = of("medium")
        val HIGH = of("high")
        val XHIGH = of("xhigh")
        val MAX = of("max")
        val ULTRA = of("ultra")

        fun of(value: String): ReasoningEffort {
            requireWireToken(value, "Reasoning effort")
            return ReasoningEffort(value)
        }
    }
}

enum class ApprovalPolicy(val wireValue: String) {
    UNTRUSTED("untrusted"),
    ON_REQUEST("on-request"),
    NEVER("never"),
}

/**
 * App Server sandbox selection. `DANGER_FULL_ACCESS` is Hans Standard's YOLO
 * mode, but its boundary is still Android's ordinary application sandbox: it
 * grants no root access and no access to other apps' private data.
 */
enum class DispatchSandbox(
    val threadStartWireValue: String,
    val turnStartPolicyType: String,
) {
    READ_ONLY("read-only", "readOnly"),
    WORKSPACE_WRITE("workspace-write", "workspaceWrite"),
    DANGER_FULL_ACCESS("danger-full-access", "dangerFullAccess"),
}

enum class Personality(val wireValue: String) {
    NONE("none"),
    FRIENDLY("friendly"),
    PRAGMATIC("pragmatic"),
}

enum class ReasoningSummary(val wireValue: String) {
    AUTO("auto"),
    CONCISE("concise"),
    DETAILED("detailed"),
    NONE("none"),
}

/** App Server service-tier values used by Hans. */
object CodexServiceTier {
    /** Explicit standard routing. This is a request sentinel, not a model/list tier id. */
    const val STANDARD = "default"

    /** Fast inference as advertised by model/list. */
    const val FAST = "priority"
}

/**
 * Every sticky option that Hans can put on turn/start but cannot put on
 * turn/steer. Equality is therefore the steer-safety boundary.
 */
data class DispatchOptions(
    val model: String,
    val effort: ReasoningEffort,
    /** Always explicit so a previous or catalog-default fast tier cannot leak into this turn. */
    val serviceTier: String = CodexServiceTier.STANDARD,
    val approvalPolicy: ApprovalPolicy? = null,
    val permissionsProfile: String? = null,
    val sandbox: DispatchSandbox? = null,
    val cwd: String? = null,
    val personality: Personality? = null,
    val reasoningSummary: ReasoningSummary? = null,
) {
    init {
        requireWireToken(model, "Model")
        requireWireToken(serviceTier, "Service tier")
        permissionsProfile?.let { requireWireToken(it, "Permissions profile") }
        require(permissionsProfile == null || sandbox == null) {
            "A permissions profile cannot be combined with a sandbox selection"
        }
        cwd?.let {
            require(it.startsWith('/')) { "Working directory must be absolute" }
            require(it.length <= ProtocolLimits.MAX_PATH_CHARS) { "Working directory is too long" }
        }
    }

    companion object {
        val ASTRA_MEDIUM = DispatchOptions(
            model = "gpt-6-astra",
            effort = ReasoningEffort.MEDIUM,
            approvalPolicy = ApprovalPolicy.NEVER,
            sandbox = DispatchSandbox.DANGER_FULL_ACCESS,
        )

        val LUNA_MAX = DispatchOptions(
            model = "gpt-5.6-luna",
            effort = ReasoningEffort.MAX,
            approvalPolicy = ApprovalPolicy.NEVER,
            sandbox = DispatchSandbox.DANGER_FULL_ACCESS,
        )

        val TERRA_MAX = DispatchOptions(
            model = "gpt-5.6-terra",
            effort = ReasoningEffort.MAX,
            approvalPolicy = ApprovalPolicy.NEVER,
            sandbox = DispatchSandbox.DANGER_FULL_ACCESS,
        )

        val SOL_ULTRA = DispatchOptions(
            model = "gpt-5.6-sol",
            effort = ReasoningEffort.ULTRA,
            approvalPolicy = ApprovalPolicy.NEVER,
            sandbox = DispatchSandbox.DANGER_FULL_ACCESS,
        )

        val DEFAULT = ASTRA_MEDIUM
    }
}

data class ModelServiceTier(
    val id: String,
    val name: String,
    val description: String,
) {
    init {
        requireWireToken(id, "Service tier id")
        require(name.isNotBlank()) { "Service tier name must not be blank" }
    }
}

data class CodexModel(
    val catalogId: String,
    val wireModel: String,
    val displayName: String,
    val description: String,
    val hidden: Boolean,
    val isDefault: Boolean,
    val defaultEffort: ReasoningEffort,
    val supportedEfforts: Set<ReasoningEffort>,
    val defaultServiceTier: String?,
    val serviceTiers: List<ModelServiceTier>,
) {
    init {
        requireWireToken(catalogId, "Catalog model id")
        requireWireToken(wireModel, "Wire model")
        require(displayName.isNotBlank()) { "Model display name must not be blank" }
        require(supportedEfforts.isNotEmpty()) { "Model must advertise at least one effort" }
        require(defaultEffort in supportedEfforts) {
            "Default effort must be included in supported efforts"
        }
        defaultServiceTier?.let { defaultTier ->
            require(serviceTiers.any { it.id == defaultTier }) {
                "Default service tier must be advertised"
            }
        }
    }
}

class ModelCatalog(models: List<CodexModel>) {
    val models: List<CodexModel> = models.toList()
    private val byWireModel: Map<String, CodexModel>

    init {
        require(this.models.size <= ProtocolLimits.MAX_MODELS_TOTAL) {
            "Model catalog is too large"
        }
        require(this.models.map { it.catalogId }.distinct().size == this.models.size) {
            "Model catalog contains duplicate ids"
        }
        require(this.models.map { it.wireModel }.distinct().size == this.models.size) {
            "Model catalog contains ambiguous wire models"
        }
        byWireModel = this.models.associateBy { it.wireModel }
    }

    fun requireSupported(options: DispatchOptions): CodexModel {
        val model = byWireModel[options.model]
            ?: throw UnsupportedProtocolValueException(
                "Model '${options.model}' was not advertised by model/list",
            )
        if (options.effort !in model.supportedEfforts) {
            throw UnsupportedProtocolValueException(
                "Effort '${options.effort.wireValue}' is not supported by ${model.wireModel}",
            )
        }
        val requestedTier = options.serviceTier
        if (
            requestedTier != CodexServiceTier.STANDARD &&
            model.serviceTiers.none { it.id == requestedTier }
        ) {
                throw UnsupportedProtocolValueException(
                    "Service tier '$requestedTier' is not supported by ${model.wireModel}",
                )
        }
        return model
    }
}

internal fun requireWireToken(value: String, label: String) {
    require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))) {
        "$label is not a valid wire token"
    }
}
