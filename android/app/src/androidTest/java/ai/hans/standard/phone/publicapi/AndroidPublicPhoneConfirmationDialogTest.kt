package ai.hans.standard.phone.publicapi

import android.app.AlertDialog
import ai.hans.standard.LauncherActivity
import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import ai.hans.standard.phone.consent.PersistentAndroidConsentStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPublicPhoneConfirmationDialogTest {
    @Test
    fun visibleExternalMutationDialogReturnsOnlyTheExactCorrelatedGrant() {
        ActivityScenario.launch(LauncherActivity::class.java).use { scenario ->
            val provider = AtomicReference<AndroidPublicPhoneConfirmationDialog>()
            scenario.onActivity { activity ->
                provider.set(AndroidPublicPhoneConfirmationDialog(activity, timeoutSeconds = 10))
            }
            val request = PublicPhoneConfirmationRequest(
                callId = "dialog-call",
                tool = "reply_notification",
                risk = PublicPhoneRisk.EXTERNAL_MUTATION,
                argumentFingerprint = "a".repeat(64),
                displaySummary = "Diese Nachricht jetzt senden: 'Bin unterwegs'",
            )
            val worker = Executors.newSingleThreadExecutor()
            try {
                val future: Future<PublicPhoneConfirmationGrant?> = worker.submit<PublicPhoneConfirmationGrant?> {
                    provider.get().confirm(request)
                }
                val dialog = awaitVisibleDialog(provider.get())
                assertNotNull(dialog)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }

                assertEquals(
                    PublicPhoneConfirmationGrant(
                        request.callId,
                        request.tool,
                        request.risk,
                        request.argumentFingerprint,
                    ),
                    future.get(5, TimeUnit.SECONDS),
                )
            } finally {
                provider.get()?.close()
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun sensitiveReadCanPersistButOneTimeStillDoesNot() {
        ActivityScenario.launch(LauncherActivity::class.java).use { scenario ->
            val store = PublicMemoryConsentStore()
            val provider = AtomicReference<AndroidPublicPhoneConfirmationDialog>()
            scenario.onActivity { activity ->
                provider.set(
                    AndroidPublicPhoneConfirmationDialog(
                        activity = activity,
                        timeoutSeconds = 10,
                        consentStore = store,
                    ),
                )
            }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val once = readRequest("once")
                val onceFuture = worker.submit<PublicPhoneConfirmationGrant?> {
                    provider.get().confirm(once)
                }
                val onceDialog = awaitVisibleDialog(provider.get())
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    onceDialog!!.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                }
                assertNotNull(onceFuture.get(5, TimeUnit.SECONDS))
                assertTrue(store.active().isEmpty())

                val durable = readRequest("durable")
                val durableFuture = worker.submit<PublicPhoneConfirmationGrant?> {
                    provider.get().confirm(durable)
                }
                val durableDialog = awaitVisibleDialog(provider.get())
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    durableDialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                }
                assertNotNull(durableFuture.get(5, TimeUnit.SECONDS))
                assertTrue(
                    store.contains(
                        PersistentAndroidConsentDescriptor.category(
                            PersistentAndroidConsentScope.READ_LOCATION,
                        ),
                    ),
                )

                val fast = worker.submit<PublicPhoneConfirmationGrant?> {
                    provider.get().confirm(readRequest("fast"))
                }
                assertNotNull(fast.get(2, TimeUnit.SECONDS))
                assertNull(visibleDialog(provider.get()))
            } finally {
                provider.get()?.close()
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun concurrentSameReadScopePersistsOnceAndSecondCallFastPaths() {
        ActivityScenario.launch(LauncherActivity::class.java).use { scenario ->
            val store = PublicMemoryConsentStore()
            val provider = AtomicReference<AndroidPublicPhoneConfirmationDialog>()
            scenario.onActivity { activity ->
                provider.set(
                    AndroidPublicPhoneConfirmationDialog(
                        activity,
                        timeoutSeconds = 10,
                        consentStore = store,
                    ),
                )
            }
            val worker = Executors.newFixedThreadPool(2)
            try {
                val first = worker.submit<PublicPhoneConfirmationGrant?> {
                    provider.get().confirm(readRequest("first"))
                }
                val dialog = awaitVisibleDialog(provider.get())
                val second = worker.submit<PublicPhoneConfirmationGrant?> {
                    provider.get().confirm(readRequest("second"))
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
    fun stoppedActivityRefusesMissingReadGrantWithoutDialog() {
        ActivityScenario.launch(LauncherActivity::class.java).use { scenario ->
            val provider = AtomicReference<AndroidPublicPhoneConfirmationDialog>()
            scenario.onActivity { activity ->
                provider.set(AndroidPublicPhoneConfirmationDialog(activity, timeoutSeconds = 10))
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            val worker = Executors.newSingleThreadExecutor()
            try {
                assertNull(
                    worker.submit<PublicPhoneConfirmationGrant?> {
                        provider.get().confirm(readRequest("stopped"))
                    }
                        .get(2, TimeUnit.SECONDS),
                )
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
            val provider = AtomicReference<AndroidPublicPhoneConfirmationDialog>()
            scenario.onActivity { activity ->
                provider.set(AndroidPublicPhoneConfirmationDialog(activity, timeoutSeconds = 10))
            }
            val worker = Executors.newSingleThreadExecutor()
            try {
                val future = worker.submit<PublicPhoneConfirmationGrant?> {
                    provider.get().confirm(readRequest("close"))
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

    private fun readRequest(suffix: String) = PublicPhoneConfirmationRequest(
        callId = "location-$suffix",
        tool = "read_location",
        risk = PublicPhoneRisk.SENSITIVE_READ,
        argumentFingerprint = when (suffix) {
            "once" -> "a".repeat(64)
            "durable" -> "b".repeat(64)
            "fast" -> "c".repeat(64)
            "first" -> "d".repeat(64)
            "second" -> "e".repeat(64)
            else -> "f".repeat(64)
        },
        displaySummary = "Standort lesen.",
        persistentConsentScope = PersistentAndroidConsentScope.READ_LOCATION,
    )

    @Suppress("UNCHECKED_CAST")
    private fun awaitVisibleDialog(
        provider: AndroidPublicPhoneConfirmationDialog,
    ): AlertDialog? {
        repeat(100) {
            visibleDialog(provider)?.let { return it }
            Thread.sleep(20)
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun visibleDialog(
        provider: AndroidPublicPhoneConfirmationDialog,
    ): AlertDialog? {
        val field = AndroidPublicPhoneConfirmationDialog::class.java
            .getDeclaredField("visibleDialog")
            .apply { isAccessible = true }
        val reference = field.get(provider) as AtomicReference<AlertDialog?>
        return reference.get()
    }
}

private class PublicMemoryConsentStore : PersistentAndroidConsentStore {
    private val values = linkedSetOf<PersistentAndroidConsentDescriptor>()
    override fun contains(descriptor: PersistentAndroidConsentDescriptor) = descriptor in values
    override fun grant(descriptor: PersistentAndroidConsentDescriptor): Boolean =
        values.add(descriptor) || descriptor in values
    override fun revoke(descriptor: PersistentAndroidConsentDescriptor): Boolean =
        values.remove(descriptor) || descriptor !in values
    override fun revokeAll(): Boolean {
        values.clear()
        return true
    }
    override fun active(): Set<PersistentAndroidConsentDescriptor> = values.toSet()
}
