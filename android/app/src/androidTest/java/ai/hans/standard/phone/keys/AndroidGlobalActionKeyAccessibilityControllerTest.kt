package ai.hans.standard.phone.keys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidGlobalActionKeyAccessibilityControllerTest {
    @Test
    fun legacyHoldPreferenceUsesTaskVoicePressWithoutRewritingStoredMapping() {
        val fixture = fixture("legacy-hold")
        val stored = mapping(mappingId = "dictation", scanCode = 172, keyCode = 280)
            .copy(trigger = ActionKeyTrigger.HOLD_TO_TALK)
        try {
            fixture.store.save(stored)
            fixture.controller.connect()
            assertTrue(fixture.controller.onObservedKeyEvent(event()))
            assertTrue(fixture.commands.isEmpty())
            assertTrue(fixture.controller.onObservedKeyEvent(
                event(phase = ObservableKeyPhase.UP, eventTimeMillis = 180),
            ))
            assertEquals(listOf(ActionKeyCommand.ToggleDictation), fixture.commands)
            assertEquals(listOf(stored), fixture.store.read().mappings)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun frameworkFilteringTracksEffectiveMappingsAndCaptureLeaseWithoutDisablingEither() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val preferencesName = "action-key-controller-$suffix"
        val confirmationFile = "action-key-controller-$suffix.json"
        val store = ActionKeyMappingPreferencesStore(context, preferencesName)
        val confirmation = Mp01VendorActionOverrideConfirmationStore(
            context,
            confirmationFile,
        )
        val filtering = CopyOnWriteArrayList<Boolean>()
        val commands = CopyOnWriteArrayList<ActionKeyCommand>()
        var captureLease: GlobalActionKeyCaptureLease? = null
        val controller = AndroidGlobalActionKeyAccessibilityController(
            context = context,
            setFrameworkFilteringEnabled = filtering::add,
            commandExecutor = GlobalActionKeyCommandExecutor { command ->
                commands += command
                true
            },
            mappingStore = store,
            vendorActionConfirmation = confirmation,
        )

        try {
            controller.connect()
            assertTrue(filtering.isEmpty())

            val modelOnly = mapping(
                mappingId = "model-only",
                scanCode = 99,
                keyCode = 280,
                action = KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA,
            )
            store.save(modelOnly)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(filtering.isEmpty())

            val dictation = mapping(
                mappingId = "dictation",
                scanCode = 252,
                keyCode = Mp01VendorActionConflictResolver.STOCK_AREFRESH_KEY_CODE,
            )
            store.save(dictation)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(true, filtering.last())

            captureLease = GlobalActionKeyCaptureGate.acquire(CaptureSessionId(101)) { _, _ -> Unit }
            assertEquals(true, filtering.last())
            checkNotNull(captureLease).close()
            captureLease = null
            assertEquals(true, filtering.last())

            store.remove(dictation.mappingId)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(false, filtering.last())
            assertTrue(commands.isEmpty())
        } finally {
            captureLease?.close()
            controller.close()
            store.clear()
            confirmation.clear()
            context.deleteSharedPreferences(preferencesName)
        }

        assertFalse(filtering.last())
    }

    @Test
    fun emptyMappingDatabaseStillFiltersAndRoutesCompleteCaptureDownUp() {
        val fixture = fixture("empty-capture")
        val captured = CopyOnWriteArrayList<ObservableAndroidKeyEvent>()
        val lease = GlobalActionKeyCaptureGate.acquire(CaptureSessionId(201)) { _, event ->
            captured += event
        }

        try {
            fixture.controller.connect()
            assertEquals(true, fixture.filtering.last())

            assertTrue(fixture.controller.onObservedKeyEvent(event()))
            assertTrue(
                fixture.controller.onObservedKeyEvent(
                    event(phase = ObservableKeyPhase.UP, eventTimeMillis = 180),
                ),
            )
            assertEquals(
                listOf(ObservableKeyPhase.DOWN, ObservableKeyPhase.UP),
                captured.map { it.phase },
            )
            assertTrue(fixture.commands.isEmpty())

            lease.close()
            assertEquals(false, fixture.filtering.last())
        } finally {
            lease.close()
            fixture.close()
        }
    }

    @Test
    fun replacementCapturePausesOldMappingThenAtomicallyActivatesNewMapping() {
        val fixture = fixture("replacement")
        val old = mapping(mappingId = "dictation", scanCode = 200, keyCode = 280)
        val replacement = mapping(mappingId = "dictation", scanCode = 201, keyCode = 281)
        fixture.store.save(old)
        fixture.controller.connect()
        val receipts = CopyOnWriteArrayList<GlobalActionKeyAccessibilityReceipt>()
        val receiptRegistration = GlobalActionKeyAccessibilityReceiptCenter.observe(receipts::add)
        lateinit var lease: GlobalActionKeyCaptureLease
        lease = GlobalActionKeyCaptureGate.acquire(CaptureSessionId(301)) { _, observed ->
            if (observed.phase == ObservableKeyPhase.UP) {
                fixture.store.save(replacement)
                lease.close()
            }
        }
        val releaseMainThread = CountDownLatch(1)
        val mainThreadBlocked = CountDownLatch(1)
        val mainThreadBlocker = Thread {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                mainThreadBlocked.countDown()
                releaseMainThread.await(10, TimeUnit.SECONDS)
            }
        }.apply {
            name = "replacement-mapping-main-thread-blocker"
        }

        try {
            assertTrue(
                fixture.controller.onObservedKeyEvent(
                    event(scanCode = old.scanCode, keyCode = old.keyCode),
                ),
            )
            mainThreadBlocker.start()
            try {
                assertTrue(mainThreadBlocked.await(5, TimeUnit.SECONDS))
                assertTrue(
                    fixture.controller.onObservedKeyEvent(
                        event(
                            phase = ObservableKeyPhase.UP,
                            eventTimeMillis = 180,
                            scanCode = old.scanCode,
                            keyCode = old.keyCode,
                        ),
                    ),
                )
                // The durable SharedPreferences replacement is visible here,
                // but its main-thread listener cannot have run. Lease release
                // must still atomically retire the old mapping.
                assertEquals(listOf(replacement), fixture.store.read().mappings)
                assertTrue(fixture.commands.isEmpty())
                assertTrue(receipts.isEmpty())
                assertEquals(true, fixture.filtering.last())

                assertFalse(
                    fixture.controller.onObservedKeyEvent(
                        event(eventTimeMillis = 300, downTimeMillis = 300, scanCode = 200, keyCode = 280),
                    ),
                )
                assertFalse(
                    fixture.controller.onObservedKeyEvent(
                        event(
                            phase = ObservableKeyPhase.UP,
                            eventTimeMillis = 340,
                            downTimeMillis = 300,
                            scanCode = 200,
                            keyCode = 280,
                        ),
                    ),
                )

                assertTrue(
                    fixture.controller.onObservedKeyEvent(
                        event(eventTimeMillis = 400, downTimeMillis = 400, scanCode = 201, keyCode = 281),
                    ),
                )
                assertTrue(
                    fixture.controller.onObservedKeyEvent(
                        event(
                            phase = ObservableKeyPhase.UP,
                            eventTimeMillis = 450,
                            downTimeMillis = 400,
                            scanCode = 201,
                            keyCode = 281,
                        ),
                    ),
                )
                assertEquals(listOf(ActionKeyCommand.ToggleDictation), fixture.commands)
                assertEquals(listOf("dictation"), receipts.map { it.mappingId })
            } finally {
                releaseMainThread.countDown()
                mainThreadBlocker.join(5_000)
                assertFalse(mainThreadBlocker.isAlive)
            }
        } finally {
            lease.close()
            receiptRegistration.close()
            fixture.close()
        }
    }

    @Test
    fun timeoutEndingLeaseAfterDownKeepsFilterOnlyUntilOwnedUp() {
        val fixture = fixture("timeout-tail")
        val captured = CopyOnWriteArrayList<ObservableAndroidKeyEvent>()
        val lease = GlobalActionKeyCaptureGate.acquire(CaptureSessionId(401)) { _, event ->
            captured += event
        }

        try {
            fixture.controller.connect()
            assertTrue(fixture.controller.onObservedKeyEvent(event()))

            // This is the same lease transition the launcher's timeout handler performs.
            lease.close()
            assertEquals(true, fixture.filtering.last())
            assertTrue(
                fixture.controller.onObservedKeyEvent(
                    event(phase = ObservableKeyPhase.UP, eventTimeMillis = 700),
                ),
            )
            assertEquals(listOf(ObservableKeyPhase.DOWN), captured.map { it.phase })
            assertEquals(false, fixture.filtering.last())
        } finally {
            lease.close()
            fixture.close()
        }
    }

    private fun fixture(label: String): ControllerFixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = "$label-${UUID.randomUUID()}"
        val preferencesName = "action-key-controller-$suffix"
        val confirmationFile = "action-key-controller-$suffix.json"
        val store = ActionKeyMappingPreferencesStore(context, preferencesName)
        val confirmation = Mp01VendorActionOverrideConfirmationStore(context, confirmationFile)
        val filtering = CopyOnWriteArrayList<Boolean>()
        val commands = CopyOnWriteArrayList<ActionKeyCommand>()
        val controller = AndroidGlobalActionKeyAccessibilityController(
            context = context,
            setFrameworkFilteringEnabled = filtering::add,
            commandExecutor = GlobalActionKeyCommandExecutor { command ->
                commands += command
                true
            },
            mappingStore = store,
            vendorActionConfirmation = confirmation,
        )
        return ControllerFixture(
            context = context,
            preferencesName = preferencesName,
            store = store,
            confirmation = confirmation,
            filtering = filtering,
            commands = commands,
            controller = controller,
        )
    }

    private fun event(
        phase: ObservableKeyPhase = ObservableKeyPhase.DOWN,
        eventTimeMillis: Long = 100,
        downTimeMillis: Long = 100,
        scanCode: Int = 172,
        keyCode: Int = 280,
    ) = ObservableAndroidKeyEvent(
        phase = phase,
        eventTimeMillis = eventTimeMillis,
        downTimeMillis = downTimeMillis,
        repeatCount = 0,
        isLongPress = false,
        scanCode = scanCode,
        keyCode = keyCode,
        metaState = 0,
        unicodeChar = 0,
        source = 257,
        physicalDevice = PhysicalKeyDeviceObservation(
            runtimeDeviceId = 7,
            vendorId = 1_234,
            productId = 5_678,
            descriptorSha256 = "a".repeat(64),
        ),
    )

    private data class ControllerFixture(
        val context: Context,
        val preferencesName: String,
        val store: ActionKeyMappingPreferencesStore,
        val confirmation: Mp01VendorActionOverrideConfirmationStore,
        val filtering: CopyOnWriteArrayList<Boolean>,
        val commands: CopyOnWriteArrayList<ActionKeyCommand>,
        val controller: AndroidGlobalActionKeyAccessibilityController,
    ) {
        fun close() {
            controller.close()
            store.clear()
            confirmation.clear()
            context.deleteSharedPreferences(preferencesName)
        }
    }

    private fun mapping(
        mappingId: String,
        scanCode: Int,
        keyCode: Int,
        action: KeySemanticAction = KeySemanticAction.DICTATION,
    ) = ActionKeyMapping(
        mappingId = mappingId,
        device = PhysicalKeyDeviceSelector(
            vendorId = 1_234,
            productId = 5_678,
            descriptorSha256 = "a".repeat(64),
        ),
        source = 257,
        scanCode = scanCode,
        keyCode = keyCode,
        metaState = 0,
        trigger = ActionKeyTrigger.PRESS,
        action = action,
    )
}
