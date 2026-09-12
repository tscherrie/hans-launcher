package ai.hans.standard.phone.capabilities

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashMap

data class CapabilityConfirmation(
    val capabilityId: CapabilityId,
    val idempotencyKey: IdempotencyKey,
    val risk: ConfirmationRisk,
)

class CapabilityBroker(
    private val registry: CapabilityRegistry,
    private val adapter: PublicAndroidActionAdapter,
    private val idempotencyLedger: IdempotencyLedger = BoundedInMemoryIdempotencyLedger(),
) {
    @Synchronized
    fun execute(
        command: CapabilityCommand,
        confirmation: CapabilityConfirmation? = null,
    ): CapabilityExecutionResult {
        val fingerprint = CommandFingerprint.of(command)
        idempotencyLedger.find(command.idempotencyKey)?.let { previous ->
            if (previous.requestFingerprint != fingerprint) {
                return rejected(command, "idempotency_key_conflict")
            }
            // A profile may have locked since the first read. Never replay private inventory;
            // re-read the live profile state and inventory for every list observation.
            if (command !is CapabilityCommand.ListLaunchableApps) {
                return previous.result.copy(replayed = true)
            }
        }

        validate(command)?.let { return rejected(command, it) }
        val liveCapability = registry.find(command.capabilityId)
            ?: return rejected(command, "capability_unknown")
        if (liveCapability.availability.kind != CapabilityAvailabilityKind.AVAILABLE) {
            return rejected(
                command,
                "capability_${liveCapability.availability.kind.name.lowercase()}",
            )
        }
        val risk = liveCapability.descriptor.confirmationRisk
        if (risk != ConfirmationRisk.NONE && !confirmation.matches(command, risk)) {
            return rejected(command, "confirmation_required_${risk.name.lowercase()}")
        }

        val result = executeAvailable(command)
        val ledgerResult = if (command is CapabilityCommand.ListLaunchableApps) {
            // Preserve conflict detection without retaining any private app inventory.
            result.copy(observation = null)
        } else {
            result
        }
        idempotencyLedger.store(
            command.idempotencyKey,
            IdempotencyRecord(fingerprint, ledgerResult),
        )
        return result
    }

    private fun executeAvailable(command: CapabilityCommand): CapabilityExecutionResult = when (command) {
        is CapabilityCommand.ListLaunchableApps -> adapter.listLaunchableAppCatalog().fold(
            onSuccess = { rawCatalog ->
                val catalog = rawCatalog.privacyFiltered()
                success(
                    command,
                    observation = CapabilityObservation.LaunchableApps(
                        apps = catalog.apps.sortedWith(APP_ORDER),
                        profiles = catalog.profiles,
                    ),
                    postcondition = CapabilityPostcondition(
                        PostconditionKind.LIVE_LIST_OBSERVED,
                        PostconditionStatus.VERIFIED,
                        "launcher_query_completed",
                    ),
                )
            },
            onFailure = { failed(command, it) },
        )

        is CapabilityCommand.LaunchApp -> adapter.launchAppInProfile(
            command.packageName,
            command.profileId,
        ).fold(
            onSuccess = {
                dispatchSuccess(command, it, ObservationTrust.UNTRUSTED_EXTERNAL, "launch_dispatched")
            },
            onFailure = { failed(command, it) },
        )

        is CapabilityCommand.OpenView -> adapter.openSafeView(command.uri).fold(
            onSuccess = {
                dispatchSuccess(command, it, ObservationTrust.UNTRUSTED_INPUT, "view_dispatched")
            },
            onFailure = { failed(command, it) },
        )

        is CapabilityCommand.OpenSettings -> adapter.openSettings(
            command.destination,
            command.packageName,
        ).fold(
            onSuccess = {
                dispatchSuccess(command, it, ObservationTrust.SYSTEM, "settings_dispatched")
            },
            onFailure = { failed(command, it) },
        )

        is CapabilityCommand.ReadBattery -> adapter.readBattery().fold(
            onSuccess = {
                success(
                    command,
                    it,
                    CapabilityPostcondition(
                        PostconditionKind.SYSTEM_STATE_OBSERVED,
                        PostconditionStatus.VERIFIED,
                        "battery_state_observed",
                    ),
                )
            },
            onFailure = { failed(command, it) },
        )

        is CapabilityCommand.ReadNetwork -> adapter.readNetwork().fold(
            onSuccess = {
                success(
                    command,
                    it,
                    CapabilityPostcondition(
                        PostconditionKind.SYSTEM_STATE_OBSERVED,
                        PostconditionStatus.VERIFIED,
                        "network_state_observed",
                    ),
                )
            },
            onFailure = { failed(command, it) },
        )
    }

    private fun dispatchSuccess(
        command: CapabilityCommand,
        dispatch: DispatchObservation,
        trust: ObservationTrust,
        detailCode: String,
    ): CapabilityExecutionResult = success(
        command = command,
        observation = CapabilityObservation.DispatchAccepted(
            targetPackage = dispatch.targetPackage,
            targetComponent = dispatch.targetComponent,
            trust = trust,
        ),
        postcondition = CapabilityPostcondition(
            kind = PostconditionKind.ACTIVITY_START_ACCEPTED,
            status = PostconditionStatus.OBSERVED_NOT_VERIFIED,
            detailCode = detailCode,
        ),
    )

    private fun success(
        command: CapabilityCommand,
        observation: CapabilityObservation,
        postcondition: CapabilityPostcondition,
    ): CapabilityExecutionResult = CapabilityExecutionResult(
        capabilityId = command.capabilityId,
        idempotencyKey = command.idempotencyKey,
        status = CapabilityExecutionStatus.SUCCEEDED,
        replayed = false,
        observation = observation,
        postcondition = postcondition,
    )

    private fun failed(
        command: CapabilityCommand,
        adapterCode: String,
    ): CapabilityExecutionResult = CapabilityExecutionResult(
        capabilityId = command.capabilityId,
        idempotencyKey = command.idempotencyKey,
        status = CapabilityExecutionStatus.FAILED,
        replayed = false,
        observation = null,
        postcondition = CapabilityPostcondition(
            PostconditionKind.ADAPTER_OPERATION,
            PostconditionStatus.FAILED,
            "adapter_failed",
        ),
        errorCode = safeCode(adapterCode),
    )

    private fun rejected(
        command: CapabilityCommand,
        code: String,
    ): CapabilityExecutionResult = CapabilityExecutionResult(
        capabilityId = command.capabilityId,
        idempotencyKey = command.idempotencyKey,
        status = CapabilityExecutionStatus.REJECTED,
        replayed = false,
        observation = null,
        postcondition = CapabilityPostcondition(
            PostconditionKind.REQUEST_NOT_EXECUTED,
            PostconditionStatus.NOT_EVALUATED,
            "not_executed",
        ),
        errorCode = safeCode(code),
    )

    private fun validate(command: CapabilityCommand): String? = when (command) {
        is CapabilityCommand.LaunchApp -> when {
            !isPackageName(command.packageName) -> "invalid_package_name"
            command.profileId != null && !isLaunchProfileId(command.profileId) ->
                "invalid_profile_id"
            else -> null
        }
        is CapabilityCommand.OpenView -> if (SafeViewUriPolicy.accept(command.uri) != null) {
            null
        } else {
            "view_uri_not_allowed"
        }
        is CapabilityCommand.OpenSettings -> if (
            command.packageName == null || isPackageName(command.packageName)
        ) {
            null
        } else {
            "invalid_package_name"
        }
        else -> null
    }

    private fun CapabilityConfirmation?.matches(
        command: CapabilityCommand,
        requiredRisk: ConfirmationRisk,
    ): Boolean = this != null &&
        capabilityId == command.capabilityId &&
        idempotencyKey == command.idempotencyKey &&
        risk == requiredRisk

    private fun isPackageName(value: String): Boolean =
        value.length <= 255 && PACKAGE_NAME.matches(value)

    private fun safeCode(value: String): String = value
        .takeIf { it.matches(ERROR_CODE) }
        ?: "unspecified_failure"

    private fun <T> AndroidAdapterResult<T>.fold(
        onSuccess: (T) -> CapabilityExecutionResult,
        onFailure: (String) -> CapabilityExecutionResult,
    ): CapabilityExecutionResult = when (this) {
        is AndroidAdapterResult.Success -> onSuccess(value)
        is AndroidAdapterResult.Failure -> onFailure(code)
    }

    companion object {
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val ERROR_CODE = Regex("[a-z][a-z0-9_]{2,79}")
        private val APP_ORDER = compareBy<LaunchableApp>(
            { it.profileType != LaunchProfileType.PERSONAL },
            { it.profileType.ordinal },
            { it.label.lowercase() },
            { it.packageName },
            { it.componentName },
        )
    }
}

class BoundedInMemoryIdempotencyLedger(
    private val maxEntries: Int = 64,
) : IdempotencyLedger {
    init {
        require(maxEntries in 1..1_024)
    }

    private val records = object : LinkedHashMap<IdempotencyKey, IdempotencyRecord>(
        maxEntries,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<IdempotencyKey, IdempotencyRecord>?,
        ): Boolean = size > maxEntries
    }

    @Synchronized
    override fun find(key: IdempotencyKey): IdempotencyRecord? = records[key]

    @Synchronized
    override fun store(key: IdempotencyKey, record: IdempotencyRecord) {
        records[key] = record
    }
}

private object CommandFingerprint {
    fun of(command: CapabilityCommand): String {
        val canonical = when (command) {
            is CapabilityCommand.ListLaunchableApps -> command.capabilityId.value
            is CapabilityCommand.LaunchApp ->
                "${command.capabilityId.value}|${command.packageName}|${command.profileId.orEmpty()}"
            is CapabilityCommand.OpenView -> "${command.capabilityId.value}|${command.uri}"
            is CapabilityCommand.OpenSettings ->
                "${command.capabilityId.value}|${command.destination.name}|${command.packageName.orEmpty()}"
            is CapabilityCommand.ReadBattery -> command.capabilityId.value
            is CapabilityCommand.ReadNetwork -> command.capabilityId.value
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
