package ai.hans.standard.runtime.python

import android.content.ComponentName
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonRuntimeProcessContractTest {
    @Test
    fun pythonWorkerIsPrivateAndRunsOutsideTheLauncherAndAppServerProcesses() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getServiceInfo(
            ComponentName(context, PythonRuntimeService::class.java),
            0,
        )

        assertFalse(info.exported)
        assertEquals("${context.packageName}:python", info.processName)
        assertEquals(
            ServiceInfo.FLAG_ISOLATED_PROCESS,
            info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS,
        )
    }
}
