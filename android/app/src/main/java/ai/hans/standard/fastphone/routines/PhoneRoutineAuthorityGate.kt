package ai.hans.standard.fastphone.routines

/** Closes the asynchronous settings-to-disk gap before cancellation or queued state mutation. */
internal class PhoneRoutineAuthorityGate {
    @Volatile var blocked: Boolean = false
        private set
    private var epoch = 0L

    @Synchronized fun capture(): Long? = epoch.takeUnless { blocked }
    @Synchronized fun permits(expectedEpoch: Long?): Boolean =
        expectedEpoch != null && !blocked && expectedEpoch == epoch

    @Synchronized fun beginChange(): Long {
        blocked = true
        check(epoch < Long.MAX_VALUE) { "routine_authority_epoch_exhausted" }
        return ++epoch
    }

    @Synchronized fun finishChange(expectedEpoch: Long, succeeded: Boolean) {
        if (succeeded && expectedEpoch == epoch) blocked = false
    }
}
