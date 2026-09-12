package ai.hans.standard.phone.keys

import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityManager
import androidx.annotation.RequiresApi
import java.io.Closeable

/**
 * Accessibility-facing lifecycle shell. It requests framework key filtering
 * while either a capture lease or an effective user-captured dictation mapping
 * exists. Capture owns and forwards the full DOWN/UP stream while ordinary
 * mappings are paused.
 * Current stock MP01 firmware may coexist only with a completed short-press
 * toggle; hold-to-talk remains withheld. Legacy Accessibility-based firmware
 * additionally requires an acknowledgement bound to its build and mapping.
 */
class AndroidGlobalActionKeyAccessibilityController(
    context: Context,
    private val setFrameworkFilteringEnabled: (Boolean) -> Unit,
    private val commandExecutor: GlobalActionKeyCommandExecutor =
        AndroidGlobalActionKeyCommandExecutor(context.applicationContext),
    private val mappingStore: ActionKeyMappingPreferencesStore =
        ActionKeyMappingPreferencesStore(context.applicationContext),
    private val vendorActionRemediation: AndroidMp01VendorActionRemediation =
        AndroidMp01VendorActionRemediation(context.applicationContext),
    private val vendorActionConfirmation: Mp01VendorActionOverrideConfirmationStore =
        Mp01VendorActionOverrideConfirmationStore(context.applicationContext),
) : Closeable {
    private val applicationContext = context.applicationContext
    private val accessibilityManager =
        applicationContext.getSystemService(AccessibilityManager::class.java)
    private val engine = GlobalActionKeyDispatchEngine()
    private val captureRouting = GlobalActionKeyCaptureRoutingEngine()
    private var latestMappings = ActionKeyMappingSet.empty()
    private var captureSnapshot = GlobalActionKeyCaptureGate.snapshot()
    private var mappingRegistration: Closeable? = null
    private var vendorConfirmationRegistration: Closeable? = null
    private var captureRegistration: Closeable? = null
    private var accessibilityServicesStateRegistration: Closeable? = null
    private var frameworkFilteringEnabled = false
    private var connected = false

    @Synchronized
    fun connect() {
        if (connected) return
        connected = true
        mappingRegistration = mappingStore.observe { mappings ->
            replaceLatestMappings(mappings)
        }
        vendorConfirmationRegistration = vendorActionConfirmation.observe {
            synchronized(this) {
                if (connected) applyEffectiveMappings()
            }
        }
        captureRegistration = GlobalActionKeyCaptureGate.observe { snapshot ->
            setCaptureSnapshot(snapshot)
        }
        registerAccessibilityServicesStateListener()
        applyEffectiveMappings()
    }

    @Synchronized
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!connected) return false
        val observed = AndroidKeyEventObserver.observe(event) ?: return false
        return onObservedKeyEvent(observed)
    }

    @Synchronized
    internal fun onObservedKeyEvent(observed: ObservableAndroidKeyEvent): Boolean {
        if (!connected) return false
        if (Mp01VendorActionConflictResolver.recognizesPhysicalEvent(observed)) {
            // API 31/32 lack AccessibilityServicesStateChangeListener, and an
            // event can race even the API 33+ callback. Publicly re-probe just
            // before this exact physical event reaches cached mappings.
            applyEffectiveMappings()
        }
        val captureDecision = captureRouting.onDeliveredEvent(
            event = observed,
            captureActive = captureSnapshot.active,
        )
        if (captureDecision.consume) {
            // A mapping press accepted immediately before capture began may
            // still own its tail. Drain that reducer without dispatching a
            // stale command; applyEffectiveMappings already paused mappings.
            engine.onDeliveredEvent(observed)
            if (captureDecision.deliverToSink) {
                GlobalActionKeyCaptureGate.deliver(observed)
            }
            updateFrameworkFiltering()
            return true
        }
        // A mapping can be removed (or capture can start) between a consumed
        // DOWN and its UP. Keep routing only that owned tail through the pure
        // reducer until the stream is complete.
        if (!engine.requiresFrameworkFiltering()) return false
        val decision = engine.onDeliveredEvent(observed)
        execute(decision, observed)
        updateFrameworkFiltering()
        return decision.consume
    }

    @Synchronized
    override fun close() {
        if (!connected) return
        connected = false
        mappingRegistration?.close()
        mappingRegistration = null
        vendorConfirmationRegistration?.close()
        vendorConfirmationRegistration = null
        captureRegistration?.close()
        captureRegistration = null
        unregisterAccessibilityServicesStateListener()
        execute(engine.close().commands)
        captureRouting.close()
        updateFrameworkFiltering()
    }

    @Synchronized
    private fun replaceLatestMappings(mappings: ActionKeyMappingSet) {
        latestMappings = mappings
        if (connected) applyEffectiveMappings()
    }

    @Synchronized
    private fun setCaptureSnapshot(snapshot: GlobalActionKeyCaptureSnapshot) {
        if (captureSnapshot.revision >= snapshot.revision) return
        val captureEnded = captureSnapshot.active && !snapshot.active
        captureSnapshot = snapshot
        if (connected) {
            if (captureEnded) {
                // SharedPreferences dispatches a listener on the main thread
                // when capture persists its replacement from another thread.
                // Commit has already completed before the lease closes, so
                // reload it here rather than briefly restoring the stale
                // pre-capture mapping until that callback runs.
                latestMappings = mappingStore.read()
            }
            applyEffectiveMappings()
        }
    }

    private fun applyEffectiveMappings() {
        val evidence = vendorActionRemediation.probe()
        var gate = Mp01VendorActionConflictResolver.gate(
            mappings = latestMappings,
            vendorEvidence = evidence,
            confirmation = vendorActionConfirmation.confirmation(),
        )
        runCatching {
            vendorActionConfirmation.synchronize(gate.conflict?.mappings.orEmpty(), evidence)
        }.onSuccess {
            gate = Mp01VendorActionConflictResolver.gate(
                mappings = latestMappings,
                vendorEvidence = evidence,
                confirmation = vendorActionConfirmation.confirmation(),
            )
        }
        val effective = if (captureSnapshot.active) {
            ActionKeyMappingSet.empty()
        } else {
            gate.effectiveMappings
        }
        execute(engine.replaceMappings(effective).commands)
        updateFrameworkFiltering()
    }

    private fun updateFrameworkFiltering() {
        val required = connected && (
            captureRouting.requiresFrameworkFiltering(captureSnapshot.active) ||
                engine.requiresFrameworkFiltering()
        )
        if (frameworkFilteringEnabled == required) return
        frameworkFilteringEnabled = required
        setFrameworkFilteringEnabled(required)
    }

    private fun execute(commands: List<ActionKeyCommand>) {
        commands.forEach { command -> runCatching { commandExecutor.execute(command) } }
    }

    private fun execute(
        decision: GlobalActionKeyDecision,
        event: ObservableAndroidKeyEvent,
    ) {
        execute(decision.commands)
        val mappingId = decision.mappingId ?: return
        decision.commands.forEach { command ->
            GlobalActionKeyAccessibilityReceiptCenter.publish(
                GlobalActionKeyAccessibilityReceipt(
                    mappingId = mappingId,
                    command = command,
                    event = event,
                ),
            )
        }
    }

    private fun registerAccessibilityServicesStateListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val manager = accessibilityManager ?: return
        accessibilityServicesStateRegistration = runCatching {
            Api33AccessibilityServicesStateObserver.register(
                manager = manager,
                context = applicationContext,
            ) {
                synchronized(this) {
                    if (connected) applyEffectiveMappings()
                }
            }
        }.getOrNull()
    }

    private fun unregisterAccessibilityServicesStateListener() {
        accessibilityServicesStateRegistration?.close()
        accessibilityServicesStateRegistration = null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33AccessibilityServicesStateObserver {
        fun register(
            manager: AccessibilityManager,
            context: Context,
            onChanged: () -> Unit,
        ): Closeable {
            val listener = AccessibilityManager.AccessibilityServicesStateChangeListener {
                onChanged()
            }
            manager.addAccessibilityServicesStateChangeListener(
                context.mainExecutor,
                listener,
            )
            return Closeable {
                runCatching { manager.removeAccessibilityServicesStateChangeListener(listener) }
            }
        }
    }
}
