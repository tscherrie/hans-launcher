package ai.hans.standard.fastphone.provisioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPhoneKeyProvisioningAdmissionTest {
    @Test fun timeoutBeforeStorageRejectsBothMutations() {
        val admission = FastPhoneKeyProvisioningAdmission()
        assertEquals("timeout", admission.timeoutCode())
        assertFalse(admission.admitSave())
        assertFalse(admission.admitEnable())
    }

    @Test fun saveOverrunReportsUnknownAndCannotEnableAfterTimeout() {
        val admission = FastPhoneKeyProvisioningAdmission()
        assertTrue(admission.admitSave())
        assertEquals("outcome_unknown", admission.timeoutCode())
        assertFalse(admission.admitEnable())
        assertFalse(admission.admitSave())
    }

    @Test fun enableAlreadyInFlightCannotFalselyReportDefiniteFailure() {
        val admission = FastPhoneKeyProvisioningAdmission()
        assertTrue(admission.admitSave())
        assertTrue(admission.admitEnable())
        assertEquals("outcome_unknown", admission.timeoutCode())
        assertFalse(admission.admitEnable())
    }

    @Test fun successfulAdmissionIsOneShotAndCompletionRejectsFurtherWork() {
        val admission = FastPhoneKeyProvisioningAdmission()
        assertFalse(admission.admitEnable())
        assertTrue(admission.admitSave())
        assertFalse(admission.admitSave())
        assertTrue(admission.admitEnable())
        admission.complete()
        assertFalse(admission.admitSave())
        assertFalse(admission.admitEnable())
    }
}
