package ai.hans.standard.fastphone.provisioning

/**
 * Serializes mutation admission, not storage I/O. A deadline can close this gate immediately.
 * Storage already admitted cannot safely be canceled; its timeout receipt must say unknown.
 */
internal class FastPhoneKeyProvisioningAdmission {
    private enum class Phase { NEW, SAVING, ENABLING }
    private var phase = Phase.NEW
    private var terminal = false

    @Synchronized fun admitSave(): Boolean {
        if (terminal || phase != Phase.NEW) return false
        phase = Phase.SAVING
        return true
    }

    @Synchronized fun admitEnable(): Boolean {
        if (terminal || phase != Phase.SAVING) return false
        phase = Phase.ENABLING
        return true
    }

    @Synchronized fun timeoutCode(): String {
        terminal = true
        return if (phase == Phase.NEW) "timeout" else "outcome_unknown"
    }

    @Synchronized fun complete() { terminal = true }
}
