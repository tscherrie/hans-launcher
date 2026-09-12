package ai.hans.standard.phone.keys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Mp01VendorActionOverrideConfirmationStoreTest {
    @Test
    fun confirmationIsDeviceLocalObservableAndBoundToMappingAndVendorState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = Mp01VendorActionOverrideConfirmationStore(
            context,
            "mp01_vendor_action_test_${UUID.randomUUID()}.json",
        )
        val mapping = mapping()
        val evidence = activeEvidence()
        val callbacks = AtomicInteger(0)
        val registration = store.observe { callbacks.incrementAndGet() }
        try {
            assertTrue(
                store.storageFileForTest().canonicalPath.startsWith(
                    context.noBackupFilesDir.canonicalPath + java.io.File.separator,
                ),
            )
            assertNull(store.confirmation())

            store.confirm(listOf(mapping), evidence)
            assertEquals(
                Mp01VendorActionOverrideConfirmation(
                    Mp01ActionMappingSetFingerprint.of(listOf(mapping)),
                    checkNotNull(Mp01VendorStateFingerprint.of(evidence)),
                ),
                store.confirmation(),
            )
            assertTrue(callbacks.get() >= 2)

            store.synchronize(
                listOf(mapping.copy(trigger = ActionKeyTrigger.HOLD_TO_TALK)),
                evidence,
            )
            assertNull(store.confirmation())
            assertFalse(store.storageFileForTest().exists())

            store.confirm(listOf(mapping), evidence)
            store.synchronize(
                listOf(mapping),
                evidence.copy(packageVersionCode = evidence.packageVersionCode + 1),
            )
            assertNull(store.confirmation())

            store.confirm(listOf(mapping), evidence)
            store.synchronize(
                listOf(mapping),
                evidence.copy(accessibilityServiceEnabled = false),
            )
            assertNull(store.confirmation())
        } finally {
            registration.close()
            store.clear()
        }
    }

    private fun mapping() = ActionKeyMapping(
        mappingId = "primary-dictation",
        device = PhysicalKeyDeviceSelector(12, 34, "a".repeat(64)),
        source = 0x101,
        scanCode = Mp01VendorActionConflictResolver.ACTION_SCAN_CODE,
        keyCode = Mp01VendorActionConflictResolver.LEGACY_REFRESH_KEY_CODE,
        metaState = 0,
        trigger = ActionKeyTrigger.PRESS,
        action = KeySemanticAction.DICTATION,
    )

    private fun activeEvidence() = Mp01VendorPackageEvidence(
        trustedSystemPackage = true,
        settingsActivityLaunchable = true,
        accessibilityServicePresent = true,
        accessibilityServiceEnabled = true,
        packageVersionCode = 12,
        packageLastUpdateTimeMillis = 1_700_000_000_000,
    )
}
