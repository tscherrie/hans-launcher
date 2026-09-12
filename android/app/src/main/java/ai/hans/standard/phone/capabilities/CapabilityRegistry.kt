package ai.hans.standard.phone.capabilities

class CapabilityRegistry(
    private val environment: CapabilityEnvironment,
    private val descriptors: List<CapabilityDescriptor> = StandardCapabilities.descriptors,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(descriptors.map { it.id }.distinct().size == descriptors.size) {
            "Duplicate capability ids"
        }
    }

    fun snapshot(): CapabilityRegistrySnapshot = CapabilityRegistrySnapshot(
        generatedAtEpochMillis = clock().coerceAtLeast(0),
        capabilities = descriptors
            .sortedBy { it.id.value }
            .map { descriptor ->
                LiveCapability(
                    descriptor = descriptor,
                    availability = evaluate(descriptor.requirements),
                )
            },
    )

    fun find(id: CapabilityId): LiveCapability? = snapshot()
        .capabilities
        .firstOrNull { it.descriptor.id == id }

    private fun evaluate(requirements: AndroidCapabilityRequirements): CapabilityAvailability {
        if (environment.apiLevel < requirements.minApi) {
            return CapabilityAvailability(
                kind = CapabilityAvailabilityKind.UNSUPPORTED,
                unsupportedReasonCode = "android_api_too_old",
            )
        }
        val missingFeatures = requirements.requiredSystemFeatures
            .filterNot(environment::hasSystemFeature)
        if (missingFeatures.isNotEmpty()) {
            return CapabilityAvailability(
                kind = CapabilityAvailabilityKind.UNSUPPORTED,
                unsupportedReasonCode = "system_feature_missing",
            )
        }
        val missingPermissions = requirements.requiredPermissions
            .filterNot(environment::hasPermission)
            .toSet()
        if (missingPermissions.isNotEmpty()) {
            return CapabilityAvailability(
                kind = CapabilityAvailabilityKind.PERMISSION_REQUIRED,
                missingPermissions = missingPermissions,
            )
        }
        val missingAccess = requirements.requiredSpecialAccess
            .filterNot(environment::hasSpecialAccess)
            .toSet()
        if (missingAccess.isNotEmpty()) {
            return CapabilityAvailability(
                kind = CapabilityAvailabilityKind.SPECIAL_ACCESS_REQUIRED,
                missingSpecialAccess = missingAccess,
            )
        }
        return CapabilityAvailability(CapabilityAvailabilityKind.AVAILABLE)
    }
}

object StandardCapabilities {
    const val ACCESS_NETWORK_STATE = "android.permission.ACCESS_NETWORK_STATE"

    val descriptors: List<CapabilityDescriptor> = listOf(
        CapabilityDescriptor(
            id = CapabilityId.LIST_LAUNCHABLE_APPS,
            displayName = "Launchbare Apps auflisten",
            description = "Listet öffentliche Launcher-Aktivitäten in aktuell zugänglichen Android-Profilen; gesperrte private Apps bleiben verborgen.",
            requirements = AndroidCapabilityRequirements(minApi = 21),
            confirmationRisk = ConfirmationRisk.SENSITIVE_DATA,
            outputTrust = ObservationTrust.UNTRUSTED_EXTERNAL,
        ),
        CapabilityDescriptor(
            id = CapabilityId.LAUNCH_APP,
            displayName = "App öffnen",
            description = "Öffnet deterministisch eine öffentliche Launcher-Aktivität im ausgewählten zugänglichen Profil.",
            requirements = AndroidCapabilityRequirements(minApi = 21),
            confirmationRisk = ConfirmationRisk.USER_VISIBLE,
            outputTrust = ObservationTrust.UNTRUSTED_EXTERNAL,
        ),
        CapabilityDescriptor(
            id = CapabilityId.OPEN_VIEW,
            displayName = "Sichere Adresse öffnen",
            description = "Öffnet ausschließlich erlaubte VIEW-Schemas in einer aufgelösten App.",
            requirements = AndroidCapabilityRequirements(minApi = 21),
            confirmationRisk = ConfirmationRisk.USER_VISIBLE,
            outputTrust = ObservationTrust.UNTRUSTED_INPUT,
        ),
        CapabilityDescriptor(
            id = CapabilityId.OPEN_SETTINGS,
            displayName = "Android-Einstellung öffnen",
            description = "Öffnet eine bekannte öffentliche Android-Einstellungsseite.",
            requirements = AndroidCapabilityRequirements(minApi = 21),
            confirmationRisk = ConfirmationRisk.USER_VISIBLE,
            outputTrust = ObservationTrust.SYSTEM,
        ),
        CapabilityDescriptor(
            id = CapabilityId.READ_BATTERY,
            displayName = "Batteriestatus lesen",
            description = "Liest groben Batteriestand, Lade- und Energiesparstatus.",
            requirements = AndroidCapabilityRequirements(minApi = 21),
            confirmationRisk = ConfirmationRisk.NONE,
            outputTrust = ObservationTrust.SYSTEM,
        ),
        CapabilityDescriptor(
            id = CapabilityId.READ_NETWORK,
            displayName = "Netzstatus lesen",
            description = "Liest Konnektivität, Validierung, Kostenstatus und Transportart ohne Netzwerkadresse.",
            requirements = AndroidCapabilityRequirements(
                minApi = 23,
                requiredPermissions = setOf(ACCESS_NETWORK_STATE),
            ),
            confirmationRisk = ConfirmationRisk.NONE,
            outputTrust = ObservationTrust.SYSTEM,
        ),
    )
}
