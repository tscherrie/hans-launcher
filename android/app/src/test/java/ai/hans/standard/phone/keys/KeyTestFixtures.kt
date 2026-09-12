package ai.hans.standard.phone.keys

internal object KeyTestFixtures {
    val DEVICE = PhysicalKeyDeviceObservation(
        runtimeDeviceId = 7,
        vendorId = 1_234,
        productId = 5_678,
        descriptorSha256 = "a".repeat(64),
    )

    val OTHER_DEVICE = PhysicalKeyDeviceObservation(
        runtimeDeviceId = 8,
        vendorId = 1_234,
        productId = 5_678,
        descriptorSha256 = "b".repeat(64),
    )

    fun event(
        phase: ObservableKeyPhase = ObservableKeyPhase.DOWN,
        eventTimeMillis: Long = 100L,
        downTimeMillis: Long = 100L,
        repeatCount: Int = 0,
        isLongPress: Boolean = false,
        scanCode: Int = 172,
        keyCode: Int = 280,
        metaState: Int = 0,
        unicodeChar: Int = 0,
        source: Int = 0x101,
        physicalDevice: PhysicalKeyDeviceObservation? = DEVICE,
    ) = ObservableAndroidKeyEvent(
        phase = phase,
        eventTimeMillis = eventTimeMillis,
        downTimeMillis = downTimeMillis,
        repeatCount = repeatCount,
        isLongPress = isLongPress,
        scanCode = scanCode,
        keyCode = keyCode,
        metaState = metaState,
        unicodeChar = unicodeChar,
        source = source,
        physicalDevice = physicalDevice,
    )

    fun request(
        session: Long = 1L,
        mappingId: String = "action-key",
        action: KeySemanticAction = KeySemanticAction.DICTATION,
        trigger: ActionKeyTrigger = ActionKeyTrigger.PRESS,
    ) = ActionKeyCaptureRequest(
        sessionId = CaptureSessionId(session),
        mappingId = mappingId,
        action = action,
        trigger = trigger,
        startedAtMillis = 0L,
        expiresAtMillis = 2_000L,
    )

    fun mapping(
        mappingId: String = "action-key",
        scanCode: Int = 172,
        keyCode: Int = 280,
        metaState: Int = 0,
        trigger: ActionKeyTrigger = ActionKeyTrigger.PRESS,
        action: KeySemanticAction = KeySemanticAction.DICTATION,
    ) = ActionKeyMapping(
        mappingId = mappingId,
        device = DEVICE.selector(),
        source = 0x101,
        scanCode = scanCode,
        keyCode = keyCode,
        metaState = metaState,
        trigger = trigger,
        action = action,
    )
}
