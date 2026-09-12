package ai.hans.standard.phone.tools

import android.app.AlertDialog
import ai.hans.standard.LauncherActivity
import ai.hans.standard.phone.capabilities.CapabilityConfirmation
import ai.hans.standard.phone.capabilities.CapabilityId
import ai.hans.standard.phone.capabilities.ConfirmationRisk
import ai.hans.standard.phone.capabilities.IdempotencyKey
import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import ai.hans.standard.phone.consent.PersistentAndroidConsentStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDynamicToolConfirmationDialogTest {
    @Test
    fun oncePathMintsExactGrantWithoutPersisting() {
        runDialogTest { provider, store, worker ->
            val request = installedAppsRequest("once")
            val future = worker.submit<ai.hans.standard.phone.capabilities.CapabilityConfirmation?> {
                provider.confirmedGrant(request)
            }
            val dialog = awaitVisibleDialog(provider)
            assertNotNull(dialog)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                dialog!!.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            }

            assertEquals(request.capabilityId, future.get(5, TimeUnit.SECONDS)?.capabilityId)
            assertTrue(store.active().isEmpty())
        }
    }

    @Test
    fun durablePathPersistsAndNextExactGrantSkipsDialog() {
        val changed = AtomicInteger(0)
        runDialogTest(changed) { provider, store, worker ->
            val first = installedAppsRequest("durable-first")
            val future = worker.submit<ai.hans.standard.phone.capabilities.CapabilityConfirmation?> {
                provider.confirmedGrant(first)
            }
            val dialog = awaitVisibleDialog(provider)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            assertNotNull(future.get(5, TimeUnit.SECONDS))
            assertTrue(
                store.contains(
                    PersistentAndroidConsentDescriptor.category(
                        PersistentAndroidConsentScope.INSTALLED_APPS_READ,
                    ),
                ),
            )

            val second = installedAppsRequest("durable-second")
            val fast = worker.submit<ai.hans.standard.phone.capabilities.CapabilityConfirmation?> {
                provider.confirmedGrant(second)
            }
            assertEquals(second.idempotencyKey, fast.get(2, TimeUnit.SECONDS)?.idempotencyKey)
            assertNull(visibleDialog(provider))
            assertEquals(1, changed.get())
        }
    }

    @Test
    fun failedPersistenceDoesNotPretendDurableOrAuthorizeTheCall() {
        val store = MemoryConsentStore(failGrant = true)
        ActivityScenario.launch(LauncherActivity::class.java).use { scenario ->
            val provider = AtomicReference<AndroidDynamicToolConfirmationDialog>()
            scenario.onActivity { activity ->
                provider.set(AndroidDynamicToolConfirmationDialog(activity, store))
            }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val future = worker.submit<ai.hans.standard.phone.capabilities.CapabilityConfirmation?> {
                    provider.get().confirmedGrant(installedAppsRequest("fail"))
                }
                val dialog = awaitVisibleDialog(provider.get())
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                assertNull(future.get(5, TimeUnit.SECONDS))
                assertTrue(store.active().isEmpty())
            } finally {
                provider.get()?.close()
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun consequentialRequestNeverOffersOrStoresDurableConsent() {
        runDialogTest { provider, store, worker ->
            val request = DynamicToolConfirmationRequest(
                callId = "delete",
                capabilityId = CapabilityId("android.ui.control"),
                idempotencyKey = IdempotencyKey("call:delete-sensitive"),
                risk = ConfirmationRisk.DESTRUCTIVE,
                persistentConsent = null,
            )
            val future = worker.submit<ai.hans.standard.phone.capabilities.CapabilityConfirmation?> {
                provider.confirmedGrant(request)
            }
            val dialog = awaitVisibleDialog(provider)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals("Einmal erlauben", dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).text)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            assertNotNull(future.get(5, TimeUnit.SECONDS))
            assertTrue(store.active().isEmpty())
        }
    }

    @Test
    fun concurrentSameScopeRechecksAfterSemaphoreAndShowsOnlyOneDialog() {
        val store = MemoryConsentStore()
        ActivityScenario.launch(LauncherActivity::class.java).use { scenario ->
            val provider = AtomicReference<AndroidDynamicToolConfirmationDialog>()
            scenario.onActivity { activity ->
                provider.set(AndroidDynamicToolConfirmationDialog(activity, store))
            }
            val worker = Executors.newFixedThreadPool(2)
            try {
                val first = worker.submit<CapabilityConfirmation?> {
                    provider.get().confirmedGrant(installedAppsRequest("first"))
                }
                val dialog = awaitVisibleDialog(provider.get())
                val second = worker.submit<CapabilityConfirmation?> {
                    provider.get().confirmedGrant(installedAppsRequest("second"))
                }
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }

                assertNotNull(first.get(5, TimeUnit.SECONDS))
                assertNotNull(second.get(5, TimeUnit.SECONDS))
                assertNull(visibleDialog(provider.get()))
                assertEquals(1, store.active().size)
            } finally {
                provider.get()?.close()
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun stoppedActivityRefusesMissingGrantWithoutShowingDialog() {
        ActivityScenario.launch(LauncherActivity::class.java).use { scenario ->
            val provider = AtomicReference<AndroidDynamicToolConfirmationDialog>()
            scenario.onActivity { activity ->
                provider.set(AndroidDynamicToolConfirmationDialog(activity, MemoryConsentStore()))
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            val worker = Executors.newSingleThreadExecutor()
            try {
                val result = worker.submit<CapabilityConfirmation?> {
                    provider.get().confirmedGrant(installedAppsRequest("stopped"))
                }.get(2, TimeUnit.SECONDS)
                assertNull(result)
                assertNull(visibleDialog(provider.get()))
            } finally {
                provider.get()?.close()
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun closeWhileDialogIsVisibleRejectsAndClearsTheOutstandingRequest() {
        ActivityScenario.launch(LauncherActivity::class.java).use { scenario ->
            val provider = AtomicReference<AndroidDynamicToolConfirmationDialog>()
            scenario.onActivity { activity ->
                provider.set(AndroidDynamicToolConfirmationDialog(activity, MemoryConsentStore()))
            }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val future = worker.submit<CapabilityConfirmation?> {
                    provider.get().confirmedGrant(installedAppsRequest("close"))
                }
                assertNotNull(awaitVisibleDialog(provider.get()))

                provider.get().close()

                assertNull(future.get(2, TimeUnit.SECONDS))
                assertNull(visibleDialog(provider.get()))
            } finally {
                provider.get()?.close()
                worker.shutdownNow()
            }
        }
    }

    private fun runDialogTest(
        changed: AtomicInteger = AtomicInteger(0),
        block: (
            AndroidDynamicToolConfirmationDialog,
            MemoryConsentStore,
            java.util.concurrent.ExecutorService,
        ) -> Unit,
    ) {
        val store = MemoryConsentStore()
        ActivityScenario.launch(LauncherActivity::class.java).use { scenario ->
            val provider = AtomicReference<AndroidDynamicToolConfirmationDialog>()
            scenario.onActivity { activity ->
                provider.set(
                    AndroidDynamicToolConfirmationDialog(
                        activity = activity,
                        consentStore = store,
                        onConsentChanged = changed::incrementAndGet,
                    ),
                )
            }
            val worker = Executors.newSingleThreadExecutor()
            try {
                block(provider.get(), store, worker)
            } finally {
                provider.get()?.close()
                worker.shutdownNow()
            }
        }
    }

    private fun installedAppsRequest(suffix: String) = DynamicToolConfirmationRequest(
        callId = "apps-$suffix",
        capabilityId = CapabilityId.LIST_LAUNCHABLE_APPS,
        idempotencyKey = IdempotencyKey("call:installed-apps-$suffix"),
        risk = ConfirmationRisk.SENSITIVE_DATA,
        persistentConsent = DynamicToolPersistentConsent(
            PersistentAndroidConsentScope.INSTALLED_APPS_READ,
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun visibleDialog(provider: AndroidDynamicToolConfirmationDialog): AlertDialog? {
        val field = AndroidDynamicToolConfirmationDialog::class.java
            .getDeclaredField("visibleDialog")
            .apply { isAccessible = true }
        return (field.get(provider) as AtomicReference<AlertDialog?>).get()
    }

    private fun awaitVisibleDialog(provider: AndroidDynamicToolConfirmationDialog): AlertDialog? {
        repeat(100) {
            visibleDialog(provider)?.let { return it }
            Thread.sleep(20)
        }
        return null
    }
}

private class MemoryConsentStore(
    private val failGrant: Boolean = false,
) : PersistentAndroidConsentStore {
    private val values = linkedSetOf<PersistentAndroidConsentDescriptor>()

    @Synchronized
    override fun contains(descriptor: PersistentAndroidConsentDescriptor) = descriptor in values

    @Synchronized
    override fun grant(descriptor: PersistentAndroidConsentDescriptor): Boolean {
        if (failGrant) return false
        values += descriptor
        return descriptor in values
    }

    @Synchronized
    override fun revoke(descriptor: PersistentAndroidConsentDescriptor): Boolean {
        values -= descriptor
        return descriptor !in values
    }

    @Synchronized
    override fun revokeAll(): Boolean {
        values.clear()
        return values.isEmpty()
    }

    @Synchronized
    override fun active(): Set<PersistentAndroidConsentDescriptor> = values.toSet()
}
