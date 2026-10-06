package ai.hans.standard.phone.keys

import android.Manifest
import android.content.pm.ApplicationInfo
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp01VendorActionConflictTest {
    @Test
    fun upgradedHoldUsesTheSamePressFingerprintForDisplayConfirmationAndDispatch() {
        val oldMapping = mapping(keyCode = Mp01VendorActionConflictResolver.LEGACY_REFRESH_KEY_CODE,
            trigger = ActionKeyTrigger.HOLD_TO_TALK)
        val stored = ActionKeyMappingSet.of(listOf(oldMapping))
        val evidence = legacyEvidence()
        val display = checkNotNull(Mp01VendorActionConflictResolver.gate(
            stored.forTaskVoiceControls(), evidence, null,
        ).conflict)
        val confirmation = checkNotNull(Mp01VendorActionConflictResolver.resolve(
            stored.forTaskVoiceControls(), evidence, null,
        ))
        assertTrue(confirmation.userConfirmationAllowed)
        assertEquals(Mp01ActionMappingSetFingerprint.of(display.mappings),
            Mp01ActionMappingSetFingerprint.of(confirmation.mappings))
        val acknowledged = Mp01VendorActionOverrideConfirmation(
            Mp01ActionMappingSetFingerprint.of(confirmation.mappings),
            checkNotNull(Mp01VendorStateFingerprint.of(evidence)),
        )
        val gate = Mp01VendorActionConflictResolver.gate(
            stored.forTaskVoiceControls(), evidence, acknowledged,
        )
        assertEquals(listOf(oldMapping.copy(trigger = ActionKeyTrigger.PRESS)),
            gate.effectiveMappings.mappings)
        assertEquals(listOf(oldMapping), stored.mappings)
    }

    @Test
    fun stockShortPressToggleCoexistsButHoldToTalkIsPermanentlyBlocked() {
        val toggle = mapping(
            keyCode = Mp01VendorActionConflictResolver.STOCK_AREFRESH_KEY_CODE,
            trigger = ActionKeyTrigger.PRESS,
        )
        val stock = stockEvidence()

        val allowed = Mp01VendorActionConflictResolver.gate(
            ActionKeyMappingSet.of(listOf(toggle)),
            stock,
            null,
        )

        assertEquals(listOf(toggle), allowed.effectiveMappings.mappings)
        assertTrue(allowed.conflict?.stockPressToggleCompatible == true)
        assertFalse(allowed.conflict?.replacementRequired == true)
        assertFalse(allowed.conflict?.replacementConfirmed == true)
        assertFalse(allowed.conflict?.userConfirmationAllowed == true)

        val hold = toggle.copy(trigger = ActionKeyTrigger.HOLD_TO_TALK)
        val forgedConfirmation = Mp01VendorActionOverrideConfirmation(
            Mp01ActionMappingSetFingerprint.of(listOf(hold)),
            "a".repeat(64),
        )
        val blocked = Mp01VendorActionConflictResolver.gate(
            ActionKeyMappingSet.of(listOf(hold)),
            stock,
            forgedConfirmation,
        )

        assertTrue(blocked.conflict?.replacementRequired == true)
        assertFalse(blocked.conflict?.replacementConfirmed == true)
        assertTrue(blocked.effectiveMappings.mappings.isEmpty())
        assertNull(Mp01VendorStateFingerprint.of(stock))

        val unknownFirmwarePress = Mp01VendorActionConflictResolver.gate(
            ActionKeyMappingSet.of(listOf(toggle)),
            Mp01VendorPackageEvidence.ABSENT,
            null,
        )
        assertNull(unknownFirmwarePress.conflict)
        assertEquals(listOf(toggle), unknownFirmwarePress.effectiveMappings.mappings)

        val unknownFirmwareHold = Mp01VendorActionConflictResolver.gate(
            ActionKeyMappingSet.of(listOf(hold)),
            Mp01VendorPackageEvidence.ABSENT,
            forgedConfirmation,
        )
        assertTrue(unknownFirmwareHold.conflict?.replacementRequired == true)
        assertTrue(unknownFirmwareHold.effectiveMappings.mappings.isEmpty())

        val mismatchedLegacyProbe = Mp01VendorActionConflictResolver.gate(
            ActionKeyMappingSet.of(listOf(hold)),
            legacyEvidence(),
            forgedConfirmation,
        )
        assertTrue(mismatchedLegacyProbe.conflict?.replacementRequired == true)
        assertTrue(mismatchedLegacyProbe.effectiveMappings.mappings.isEmpty())
    }

    @Test
    fun stockToggleRejectsAReleaseAtOrBeyondTheSystemLongPressThreshold() {
        val toggle = mapping(
            keyCode = Mp01VendorActionConflictResolver.STOCK_AREFRESH_KEY_CODE,
            trigger = ActionKeyTrigger.PRESS,
        )
        val dispatcher = ActionKeyDispatcher(ActionKeyMappingSet.of(listOf(toggle)))

        assertTrue(
            dispatcher.onDeliveredEvent(eventFor(toggle, eventTime = 100)) is
                ActionKeyDispatchResult.AwaitingRelease,
        )
        assertEquals(
            ActionKeyIgnoreReason.STOCK_MP01_PRESS_TOO_LONG,
            (
                dispatcher.onDeliveredEvent(
                    eventFor(
                        toggle,
                        eventTime = 500,
                        phase = ObservableKeyPhase.UP,
                        downTime = 100,
                    ),
                ) as ActionKeyDispatchResult.Ignored
                ).reason,
        )

        assertTrue(
            dispatcher.onDeliveredEvent(eventFor(toggle, eventTime = 1_000)) is
                ActionKeyDispatchResult.AwaitingRelease,
        )
        assertEquals(
            ActionKeyCommand.ToggleDictation,
            (
                dispatcher.onDeliveredEvent(
                    eventFor(
                        toggle,
                        eventTime = 1_399,
                        phase = ObservableKeyPhase.UP,
                        downTime = 1_000,
                    ),
                ) as ActionKeyDispatchResult.Command
                ).command,
        )

        val global = GlobalActionKeyDispatchEngine(ActionKeyMappingSet.of(listOf(toggle)))
        assertTrue(global.onDeliveredEvent(eventFor(toggle, eventTime = 2_000)).consume)
        global.replaceMappings(ActionKeyMappingSet.of(listOf(toggle.copy())))
        val longRelease = global.onDeliveredEvent(
            eventFor(
                toggle,
                eventTime = 2_400,
                phase = ObservableKeyPhase.UP,
                downTime = 2_000,
            ),
        )
        assertTrue(longRelease.consume)
        assertTrue(longRelease.commands.isEmpty())
    }

    @Test
    fun lostLongPressReleaseSelfHealsOnTheNextShortPress() {
        val toggle = mapping(
            keyCode = Mp01VendorActionConflictResolver.STOCK_AREFRESH_KEY_CODE,
        )
        val set = ActionKeyMappingSet.of(listOf(toggle))
        val foreground = ActionKeyDispatcher(set)

        // Minimal moves focus after 400 ms, so Hans never receives this UP.
        assertTrue(foreground.onDeliveredEvent(eventFor(toggle, eventTime = 100)) is
            ActionKeyDispatchResult.AwaitingRelease)
        assertTrue(foreground.onDeliveredEvent(eventFor(toggle, eventTime = 1_000,
            downTime = 1_000)) is ActionKeyDispatchResult.AwaitingRelease)
        val foregroundRelease = foreground.onDeliveredEvent(
            eventFor(toggle, 1_120, ObservableKeyPhase.UP, downTime = 1_000),
        )
        assertEquals(
            ActionKeyCommand.ToggleDictation,
            (foregroundRelease as ActionKeyDispatchResult.Command).command,
        )

        val global = GlobalActionKeyDispatchEngine(set)
        assertTrue(global.onDeliveredEvent(eventFor(toggle, eventTime = 2_000)).consume)
        val recoveredDown = global.onDeliveredEvent(
            eventFor(toggle, eventTime = 3_000, downTime = 3_000),
        )
        val recoveredUp = global.onDeliveredEvent(
            eventFor(toggle, 3_120, ObservableKeyPhase.UP, downTime = 3_000),
        )
        assertTrue(recoveredDown.consume)
        assertTrue(recoveredUp.consume)
        assertEquals(listOf(ActionKeyCommand.ToggleDictation), recoveredUp.commands)
    }

    @Test
    fun everyLegacyVariantIsBlockedUntilExactSetAndVendorStateAreConfirmed() {
        val refresh = mapping(
            mappingId = "refresh-dictation",
            keyCode = Mp01VendorActionConflictResolver.LEGACY_REFRESH_KEY_CODE,
        )
        val legacyF9 = mapping(
            mappingId = "legacy-dictation",
            keyCode = KeyEvent.KEYCODE_F9,
            device = KeyTestFixtures.OTHER_DEVICE,
        )
        val semanticVariant = mapping(
            mappingId = "refresh-model-toggle",
            keyCode = Mp01VendorActionConflictResolver.LEGACY_REFRESH_KEY_CODE,
            metaState = KeyEvent.META_SHIFT_ON,
            action = KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA,
        )
        val generic = mapping(
            mappingId = "generic-toggle",
            scanCode = 99,
            keyCode = KeyEvent.KEYCODE_F8,
            action = KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA,
        )
        val mappings = ActionKeyMappingSet.of(
            listOf(refresh, legacyF9, semanticVariant, generic),
        )
        val evidence = legacyEvidence()

        val blocked = Mp01VendorActionConflictResolver.gate(mappings, evidence, null)

        assertEquals(
            setOf(refresh, legacyF9, semanticVariant),
            blocked.conflict?.mappings?.toSet(),
        )
        assertTrue(blocked.conflict?.replacementRequired == true)
        assertEquals(listOf(generic), blocked.effectiveMappings.mappings)

        val confirmation = confirmationFor(
            listOf(refresh, legacyF9, semanticVariant),
            evidence,
        )
        val confirmed = Mp01VendorActionConflictResolver.gate(
            mappings,
            evidence,
            confirmation,
        )
        assertTrue(confirmed.conflict?.replacementConfirmed == true)
        assertEquals(mappings.mappings, confirmed.effectiveMappings.mappings)
    }

    @Test
    fun eventTimeRegatingBlocksNewDispatchButFinishesAnOwnedKeyStream() {
        val mapping = mapping(
            keyCode = Mp01VendorActionConflictResolver.LEGACY_REFRESH_KEY_CODE,
        )
        val raw = ActionKeyMappingSet.of(listOf(mapping))
        val foreground = ActionKeyDispatcher(ActionKeyMappingSet.empty())
        val accessibility = GlobalActionKeyDispatchEngine(ActionKeyMappingSet.empty())

        replaceWithFreshGate(foreground, accessibility, raw, legacyEvidence())
        assertIgnored(foreground, mapping, 100)
        assertFalse(accessibility.onDeliveredEvent(eventFor(mapping, eventTime = 100)).consume)

        replaceWithFreshGate(
            foreground,
            accessibility,
            raw,
            legacyEvidence(serviceEnabled = false),
        )
        assertTrue(foreground.onDeliveredEvent(eventFor(mapping, eventTime = 500)) is
            ActionKeyDispatchResult.AwaitingRelease)
        assertTrue(accessibility.onDeliveredEvent(eventFor(mapping, eventTime = 500)).consume)

        replaceWithFreshGate(foreground, accessibility, raw, legacyEvidence())
        assertIgnored(
            foreground,
            mapping,
            550,
            ObservableKeyPhase.UP,
            downTime = 500,
        )
        val ownedRelease = accessibility.onDeliveredEvent(
            eventFor(mapping, 550, ObservableKeyPhase.UP, downTime = 500),
        )
        assertTrue(ownedRelease.consume)
        assertTrue(ownedRelease.commands.isEmpty())
        assertFalse(accessibility.requiresFrameworkFiltering())
    }

    @Test
    fun legacyConflictNeedsExactEnabledVendorAccessibilityService() {
        val mappings = ActionKeyMappingSet.of(
            listOf(mapping(keyCode = Mp01VendorActionConflictResolver.LEGACY_REFRESH_KEY_CODE)),
        )

        assertNull(Mp01VendorActionConflictResolver.resolve(
            mappings,
            Mp01VendorPackageEvidence.ABSENT,
            null,
        ))
        assertNull(Mp01VendorActionConflictResolver.resolve(
            mappings,
            legacyEvidence(serviceEnabled = false),
            null,
        ))
        assertNull(Mp01VendorActionConflictResolver.resolve(
            mappings,
            legacyEvidence(servicePresent = false, serviceEnabled = false),
            null,
        ))
        assertNotNull(Mp01VendorActionConflictResolver.resolve(
            mappings,
            legacyEvidence(),
            null,
        ))
    }

    @Test
    fun physicalEventRecognitionCoversStockAndLegacyVariantsOnly() {
        listOf(
            Mp01VendorActionConflictResolver.STOCK_AREFRESH_KEY_CODE,
            Mp01VendorActionConflictResolver.LEGACY_REFRESH_KEY_CODE,
            KeyEvent.KEYCODE_F9,
        ).forEach { keyCode ->
            assertTrue(
                Mp01VendorActionConflictResolver.recognizesPhysicalEvent(
                    eventFor(mapping(keyCode = keyCode)),
                ),
            )
        }
        assertFalse(
            Mp01VendorActionConflictResolver.recognizesPhysicalEvent(
                eventFor(mapping(scanCode = 99, keyCode = KeyEvent.KEYCODE_F9)),
            ),
        )
    }

    @Test
    fun stockTrustRequiresExactDeviceSystemPackageCertificateAndActivity() {
        val trusted = evaluatedStockEvidence()
        assertTrue(trusted.trustedSystemPackage)
        assertTrue(trusted.settingsActivityLaunchable)
        assertEquals(
            Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY,
            trusted.conflictKind,
        )

        assertFalse(evaluatedStockEvidence(applicationFlags = 0).trustedSystemPackage)
        assertFalse(evaluatedStockEvidence(manufacturer = "imposter").trustedSystemPackage)
        assertFalse(
            evaluatedStockEvidence(signatures = setOf("0".repeat(64))).trustedSystemPackage,
        )
        val hidden = evaluatedStockEvidence(activityExported = false)
        assertTrue(hidden.trustedSystemPackage)
        assertFalse(hidden.settingsActivityLaunchable)
    }

    @Test
    fun legacyTrustRejectsUserInstalledAndImposterServices() {
        val userInstalled = evaluatedLegacyEvidence(applicationFlags = 0)
        assertFalse(userInstalled.trustedSystemPackage)
        assertFalse(userInstalled.accessibilityServicePresent)

        val wrongService = evaluatedLegacyEvidence(
            accessibilityServiceName = "com.lmqr.hMP01_comp_service.ImposterService",
        )
        assertTrue(wrongService.trustedSystemPackage)
        assertFalse(wrongService.accessibilityServicePresent)

        val wrongPermission = evaluatedLegacyEvidence(
            accessibilityServicePermission = "android.permission.INTERNET",
        )
        assertFalse(wrongPermission.accessibilityServicePresent)
    }

    @Test
    fun settingsJumpUsesTheExactTrustedRoute() {
        val stock = Mp01VendorSettingsLaunchPolicy.explicitLaunchSpec(stockEvidence())
        assertEquals(Mp01StockSystemPolicyTrustPolicy.PACKAGE_NAME, stock?.packageName)
        assertEquals(
            Mp01StockSystemPolicyTrustPolicy.SETTINGS_ACTIVITY_NAME,
            stock?.activityName,
        )

        val legacy = Mp01VendorSettingsLaunchPolicy.explicitLaunchSpec(legacyEvidence())
        assertEquals(Mp01VendorPackageTrustPolicy.PACKAGE_NAME, legacy?.packageName)
        assertEquals(Mp01VendorPackageTrustPolicy.SETTINGS_ACTIVITY_NAME, legacy?.activityName)
        assertNull(
            Mp01VendorSettingsLaunchPolicy.explicitLaunchSpec(
                evaluatedStockEvidence(activityExported = false),
            ),
        )
    }

    @Test
    fun legacyConfirmationInvalidatesAfterMappingOrVendorBuildChange() {
        val original = mapping(
            keyCode = Mp01VendorActionConflictResolver.LEGACY_REFRESH_KEY_CODE,
        )
        val second = mapping(
            mappingId = "legacy",
            keyCode = KeyEvent.KEYCODE_F9,
            device = KeyTestFixtures.OTHER_DEVICE,
        )
        val evidence = legacyEvidence()
        val confirmation = confirmationFor(listOf(original, second), evidence)
        val remapped = second.copy(metaState = KeyEvent.META_SHIFT_ON)

        assertNotEquals(
            Mp01ActionMappingSetFingerprint.of(listOf(original, second)),
            Mp01ActionMappingSetFingerprint.of(listOf(original, remapped)),
        )
        assertTrue(
            Mp01VendorActionConflictResolver.resolve(
                ActionKeyMappingSet.of(listOf(original, remapped)),
                evidence,
                confirmation,
            )?.replacementRequired == true,
        )

        val updated = evidence.copy(
            packageVersionCode = evidence.packageVersionCode + 1,
            packageLastUpdateTimeMillis = evidence.packageLastUpdateTimeMillis + 1,
        )
        assertNotEquals(
            Mp01VendorStateFingerprint.of(evidence),
            Mp01VendorStateFingerprint.of(updated),
        )
        assertTrue(
            Mp01VendorActionConflictResolver.resolve(
                ActionKeyMappingSet.of(listOf(original, second)),
                updated,
                confirmation,
            )?.replacementRequired == true,
        )
    }

    private fun replaceWithFreshGate(
        foreground: ActionKeyDispatcher,
        accessibility: GlobalActionKeyDispatchEngine,
        raw: ActionKeyMappingSet,
        evidence: Mp01VendorPackageEvidence,
    ) {
        val effective = Mp01VendorActionConflictResolver.gate(raw, evidence, null)
            .effectiveMappings
        foreground.replaceMappings(effective)
        accessibility.replaceMappings(effective)
    }

    private fun assertIgnored(
        dispatcher: ActionKeyDispatcher,
        mapping: ActionKeyMapping,
        eventTime: Long,
        phase: ObservableKeyPhase = ObservableKeyPhase.DOWN,
        downTime: Long = eventTime,
    ) {
        assertEquals(
            ActionKeyIgnoreReason.NO_MAPPING,
            (
                dispatcher.onDeliveredEvent(
                    eventFor(mapping, eventTime, phase, downTime),
                ) as ActionKeyDispatchResult.Ignored
                ).reason,
        )
    }

    private fun confirmationFor(
        mappings: List<ActionKeyMapping>,
        evidence: Mp01VendorPackageEvidence,
    ) = Mp01VendorActionOverrideConfirmation(
        mappingSetFingerprint = Mp01ActionMappingSetFingerprint.of(mappings),
        vendorStateFingerprint = checkNotNull(Mp01VendorStateFingerprint.of(evidence)),
    )

    private fun stockEvidence(): Mp01VendorPackageEvidence = evaluatedStockEvidence()

    private fun legacyEvidence(
        servicePresent: Boolean = true,
        serviceEnabled: Boolean = true,
    ) = Mp01VendorPackageEvidence(
        trustedSystemPackage = true,
        settingsActivityLaunchable = true,
        accessibilityServicePresent = servicePresent,
        accessibilityServiceEnabled = serviceEnabled,
        packageVersionCode = 17,
        packageLastUpdateTimeMillis = 1_700_000_000_000,
        conflictKind = Mp01VendorActionConflictKind.LEGACY_ACCESSIBILITY,
    )

    @Suppress("LongParameterList")
    private fun evaluatedStockEvidence(
        manufacturer: String = "ALONG",
        applicationFlags: Int = ApplicationInfo.FLAG_SYSTEM,
        signatures: Set<String> = setOf(
            Mp01StockSystemPolicyTrustPolicy.SIGNING_CERTIFICATE_SHA256,
        ),
        activityExported: Boolean = true,
    ) = Mp01StockSystemPolicyTrustPolicy.evaluate(
        manufacturer = manufacturer,
        brand = "Minimal_Phone",
        model = "MP01",
        device = "MP01",
        packageName = Mp01StockSystemPolicyTrustPolicy.PACKAGE_NAME,
        applicationFlags = applicationFlags,
        applicationEnabled = true,
        signingCertificateSha256s = signatures,
        packageVersionCode = 1,
        packageLastUpdateTimeMillis = 1_700_000_000_000,
        activityPackageName = Mp01StockSystemPolicyTrustPolicy.PACKAGE_NAME,
        activityName = Mp01StockSystemPolicyTrustPolicy.SETTINGS_ACTIVITY_NAME,
        activityExported = activityExported,
        activityEnabled = true,
    )

    @Suppress("LongParameterList")
    private fun evaluatedLegacyEvidence(
        applicationFlags: Int = ApplicationInfo.FLAG_SYSTEM,
        accessibilityServiceName: String =
            Mp01VendorPackageTrustPolicy.ACCESSIBILITY_SERVICE_NAME,
        accessibilityServicePermission: String =
            Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
    ) = Mp01VendorPackageTrustPolicy.evaluate(
        packageName = Mp01VendorPackageTrustPolicy.PACKAGE_NAME,
        applicationFlags = applicationFlags,
        applicationEnabled = true,
        packageVersionCode = 17,
        packageLastUpdateTimeMillis = 1_700_000_000_000,
        activityPackageName = Mp01VendorPackageTrustPolicy.PACKAGE_NAME,
        activityName = Mp01VendorPackageTrustPolicy.SETTINGS_ACTIVITY_NAME,
        activityExported = true,
        activityEnabled = true,
        accessibilityServicePackageName = Mp01VendorPackageTrustPolicy.PACKAGE_NAME,
        accessibilityServiceName = accessibilityServiceName,
        accessibilityServicePermission = accessibilityServicePermission,
        accessibilityServiceDeclaredEnabled = true,
        accessibilityServiceReportedEnabled = true,
    )

    private fun mapping(
        keyCode: Int,
        mappingId: String = "primary-dictation",
        scanCode: Int = Mp01VendorActionConflictResolver.ACTION_SCAN_CODE,
        metaState: Int = 0,
        device: PhysicalKeyDeviceObservation = KeyTestFixtures.DEVICE,
        action: KeySemanticAction = KeySemanticAction.DICTATION,
        trigger: ActionKeyTrigger = ActionKeyTrigger.PRESS,
    ) = ActionKeyMapping(
        mappingId = mappingId,
        device = device.selector(),
        source = 0x101,
        scanCode = scanCode,
        keyCode = keyCode,
        metaState = metaState,
        trigger = trigger,
        action = action,
    )

    private fun eventFor(
        mapping: ActionKeyMapping,
        eventTime: Long = 100,
        phase: ObservableKeyPhase = ObservableKeyPhase.DOWN,
        downTime: Long = eventTime,
    ) = KeyTestFixtures.event(
        phase = phase,
        eventTimeMillis = eventTime,
        downTimeMillis = downTime,
        scanCode = mapping.scanCode,
        keyCode = mapping.keyCode,
        metaState = mapping.metaState,
        source = mapping.source,
        physicalDevice = if (mapping.device == KeyTestFixtures.OTHER_DEVICE.selector()) {
            KeyTestFixtures.OTHER_DEVICE
        } else {
            KeyTestFixtures.DEVICE
        },
    )
}
