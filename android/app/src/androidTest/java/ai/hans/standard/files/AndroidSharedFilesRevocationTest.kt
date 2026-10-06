package ai.hans.standard.files

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import ai.hans.standard.phone.capabilities.AndroidCapabilityEnvironment
import ai.hans.standard.phone.capabilities.AndroidSpecialAccess
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** The owned emulator runner revokes storage outside instrumentation, restarting the target UID. */
class AndroidSharedFilesRevocationTest {
    private lateinit var context: Context
    private lateinit var fixture: AndroidSharedFilesTestFixture.Receipt

    @Before fun prepare() {
        AndroidSharedFilesTestFixture.requireOwnedEmulator()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        assertFalse("Runner must revoke all-files access before starting this process", Environment.isExternalStorageManager())
        fixture = AndroidSharedFilesTestFixture.read(context)
    }

    @Test fun deniedStoreAndRecoveryRejectPriorFixture() {
        @Suppress("DEPRECATION")
        val declared = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(declared.requestedPermissions.orEmpty().contains("android.permission.MANAGE_EXTERNAL_STORAGE"))
        assertFalse(AndroidCapabilityEnvironment(context).hasSpecialAccess(AndroidSpecialAccess.ALL_FILES))
        val store = AndroidSharedFiles.store(context)
        assertFalse(store.granted())
        denied { store.open(fixture.path).close() }
        denied { store.delete(fixture.path, "0".repeat(64)) {} }
        denied { store.copy(fixture.path, File(fixture.directory, "denied-move.txt").path, true, "0".repeat(64)) {} }
        denied { store.listRecovery() }
        denied { store.restoreRecovery(fixture.recovery.handle, File(fixture.directory, "denied.txt").path) {} }
        denied { store.save(File(fixture.directory, "denied-save.txt").path, "denied".byteInputStream(), 100) {} }
    }

    @Test fun deniedProviderRejectsPreviouslyIssuedUri() {
        try {
            context.contentResolver.openInputStream(fixture.uri)?.close()
            fail("A previously issued, valid URI must fail after actual storage revocation")
        } catch (expected: FileNotFoundException) {
            assertTrue(expected.message.orEmpty().contains("file_unavailable_or_permission_revoked"))
        }
    }

    private fun denied(action: () -> Unit) {
        try { action(); fail("Revoked access must fail before any file mutation") }
        catch (failure: FileAccessFailure) { assertEquals("all_files_access_required", failure.code) }
    }
}
