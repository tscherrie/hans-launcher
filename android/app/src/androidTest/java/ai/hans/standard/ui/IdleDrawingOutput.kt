package ai.hans.standard.ui

import org.json.JSONObject

/** Test-only public HardwareRenderer state lifecycle; no redraw requests or private APIs. */
internal class IdleDrawingOutput(
    private val supported: Boolean,
    private val read: () -> Boolean,
    private val write: (Boolean) -> Unit,
) {
    private var initial: Boolean? = null
    private var enabledForProbe = false
    private var before: Boolean? = null
    private var after: Boolean? = null
    private var restored: Boolean? = null
    private var restorationComplete = !supported

    fun prepare() {
        if (!supported) return
        check(initial == null) { "Drawing output already prepared" }
        initial = read()
        enabledForProbe = initial == false
        if (enabledForProbe) write(true)
        before = read()
        check(before == true) { "Public drawing output could not be enabled" }
    }

    fun requireEnabled(): Boolean? {
        if (!supported) return null
        check(initial != null) { "Drawing output was not prepared" }
        return read().also { check(it) { "Public drawing output disabled during renderer observation" } }
    }

    fun observationFinished() {
        if (!supported) return
        after = read()
        check(after == true) { "Public drawing output disabled after observation" }
    }

    fun restore() {
        if (!supported) return
        val original = requireNotNull(initial) { "Original drawing output state is unknown" }
        if (read() != original) write(original)
        restored = read()
        check(restored == original) { "Public drawing output was not restored" }
        restorationComplete = true
    }

    fun json(): JSONObject = JSONObject()
        .put("apiSupported", supported)
        .put("initialEnabled", initial ?: JSONObject.NULL)
        .put("enabledForProbe", enabledForProbe)
        .put("effectiveBeforeActivity", before ?: JSONObject.NULL)
        .put("effectiveAfterObservation", after ?: JSONObject.NULL)
        .put("restoredEnabled", restored ?: JSONObject.NULL)
        .put("restorationComplete", restorationComplete)
}
