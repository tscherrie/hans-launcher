package ai.hans.standard.ui

/**
 * Test-only interpretation of independently read animation state. An absent Settings.Global
 * row is not zero, nor proof of a particular effective value. In AOSP Android 12/14 WMS starts
 * window/animator at 1, but the transition default is a configurable resource. We therefore
 * observe WMS's diagnostic values rather than manufacture a three-times-1 fallback.
 *
 * API 33+ additionally exposes the effective animator value through public ValueAnimator;
 * API 31/32 exposes its enabled state only. No hidden getter or setting write is used.
 */
internal data class IdleRendererAnimationEvidence(
    val scales: Map<String, Float>,
    val stored: Map<String, String?>,
    val publicAnimatorDurationScale: Float?,
    val animatorsEnabled: Boolean,
    val windowManagerLine: String,
) {
    /** WMS also prints Float.toString; avoid promotion-only diagnostic discrepancies. */
    fun scalesForReceipt(): Map<String, Double> = scales.mapValues { (_, value) ->
        value.toString().toDouble()
    }

    fun publicScaleForReceipt(): Double? = publicAnimatorDurationScale?.toString()?.toDouble()

    fun requireUnchanged(previous: IdleRendererAnimationEvidence) {
        check(scales == previous.scales && stored == previous.stored &&
            publicAnimatorDurationScale == previous.publicAnimatorDurationScale &&
            animatorsEnabled == previous.animatorsEnabled && windowManagerLine == previous.windowManagerLine) {
            "Animation state changed during renderer acceptance"
        }
    }

    companion object {
        val KEYS = setOf("animator", "transition", "window")
        const val MAX_WINDOW_DUMP_BYTES = 1_048_576
        // An accepted test-environment bound, deliberately not Android's platform maximum.
        private const val MAX_TEST_SCALE = 10f
        private val linePattern = Regex(
            "^Animation settings: disabled=(true|false) window=(\\S+) transition=(\\S+) animator=(\\S+)$",
        )
        private val decimal = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

        fun resolve(
            api: Int,
            stored: Map<String, String?>,
            publicAnimatorDurationScale: Float?,
            animatorsEnabled: Boolean,
            windowManagerDump: String,
        ): IdleRendererAnimationEvidence {
            check(api in 31..36) { "Unsupported renderer acceptance Android API" }
            check(stored.keys == KEYS) { "Incomplete stored animation-setting evidence" }
            check(windowManagerDump.toByteArray(Charsets.UTF_8).size in 1..MAX_WINDOW_DUMP_BYTES) {
                "Empty or oversized window-manager evidence"
            }
            val lines = windowManagerDump.lineSequence().map(String::trim)
                .filter { it.startsWith("Animation settings:") }.toList()
            check(lines.size == 1) { "Missing/ambiguous window-manager animation state" }
            val match = linePattern.matchEntire(lines.single())
            check(match != null) { "Unsupported window-manager animation-state format" }
            check(match.groupValues[1] == "false" && animatorsEnabled) {
                "Android animations are actually disabled"
            }
            val effective = linkedMapOf(
                "animator" to positiveScale(match.groupValues[4]),
                "transition" to positiveScale(match.groupValues[3]),
                "window" to positiveScale(match.groupValues[2]),
            )
            for ((key, raw) in stored) {
                if (raw != null) {
                    check(raw.length <= 64) { "Unbounded stored animation setting" }
                    check(positiveScale(raw) == effective.getValue(key)) {
                        "Stored $key scale disagrees with window-manager effective state"
                    }
                }
            }
            if (api >= 33) {
                check(publicAnimatorDurationScale != null && publicAnimatorDurationScale.isFinite() &&
                    publicAnimatorDurationScale > 0 && publicAnimatorDurationScale <= MAX_TEST_SCALE) {
                    "Missing/invalid public animator duration scale"
                }
                check(publicAnimatorDurationScale == effective.getValue("animator")) {
                    "Public animator duration scale disagrees with window manager"
                }
            } else {
                check(publicAnimatorDurationScale == null) { "Public duration getter unavailable before API 33" }
            }
            return IdleRendererAnimationEvidence(
                effective, stored.toMap(), publicAnimatorDurationScale, animatorsEnabled, lines.single(),
            )
        }

        private fun positiveScale(raw: String): Float {
            val normalized = raw.trim()
            check(decimal.matches(normalized)) { "Animation scale is not a decimal number" }
            val value = normalized.toFloatOrNull()
            check(value != null && value.isFinite() && value > 0 && value <= MAX_TEST_SCALE) {
                "Invalid, disabled or out-of-range animation scale"
            }
            return value
        }
    }
}
