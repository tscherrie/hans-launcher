package ai.hans.standard

import ai.hans.standard.phone.keys.ActionKeyMapping
import ai.hans.standard.phone.keys.ActionKeyMappingPreferencesStore
import ai.hans.standard.phone.keys.ActionKeyTrigger
import ai.hans.standard.phone.keys.KeySemanticAction
import ai.hans.standard.phone.keys.PhysicalKeyDeviceSelector
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActionKeyMappingPreferencesStoreTest {
    @Test
    fun roundTripsGenericPhysicalMappingAndCanRemoveIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferencesName = "action_key_test_${UUID.randomUUID()}"
        val store = ActionKeyMappingPreferencesStore(context, preferencesName)
        val observed = CopyOnWriteArrayList<List<ActionKeyMapping>>()
        val cleared = AtomicReference<CountDownLatch?>()
        val registration = store.observe {
            observed += it.mappings
            if (it.mappings.isEmpty()) cleared.get()?.countDown()
        }
        val mapping = ActionKeyMapping(
            mappingId = "primary-dictation",
            device = PhysicalKeyDeviceSelector(
                vendorId = 123,
                productId = 456,
                descriptorSha256 = "a".repeat(64),
            ),
            source = 0x101,
            scanCode = 42,
            keyCode = 84,
            metaState = 0,
            trigger = ActionKeyTrigger.PRESS,
            action = KeySemanticAction.DICTATION,
        )
        try {
            store.savePrimaryDictation(mapping)
            assertEquals(listOf(mapping), store.read().mappings)
            assertTrue(awaitObserved(observed) { it == listOf(mapping) })
            val modelToggle = mapping.copy(
                mappingId = "model-preset-toggle",
                scanCode = 173,
                keyCode = 63,
                action = KeySemanticAction.TOGGLE_LUNA_MAX_SOL_ULTRA,
            )
            store.save(modelToggle)
            assertEquals(
                listOf(modelToggle, mapping).sortedBy(ActionKeyMapping::mappingId),
                store.read().mappings,
            )
            assertTrue(awaitObserved(observed) { it.size == 2 })
            store.remove(modelToggle.mappingId)
            assertEquals(listOf(mapping), store.read().mappings)
            val clearedNotification = CountDownLatch(1)
            cleared.set(clearedNotification)
            store.clear()
            assertTrue(store.read().mappings.isEmpty())
            assertTrue(clearedNotification.await(2, TimeUnit.SECONDS))
            assertTrue(observed.last().isEmpty())
        } finally {
            registration.close()
            store.clear()
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun awaitObserved(
        observed: List<List<ActionKeyMapping>>,
        predicate: (List<ActionKeyMapping>) -> Boolean,
    ): Boolean {
        repeat(100) {
            if (observed.any(predicate)) return true
            Thread.sleep(20)
        }
        return observed.any(predicate)
    }
}
