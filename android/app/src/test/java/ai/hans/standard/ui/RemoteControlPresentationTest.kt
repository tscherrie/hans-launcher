package ai.hans.standard.ui

import ai.hans.standard.remotecontrol.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class RemoteControlPresentationTest {
    private val text = ai.hans.standard.localization.TestResourceTextResolver(java.util.Locale.GERMAN)
    private fun remoteControlPresentation(state: RemoteControlSnapshot) =
        ai.hans.standard.ui.remoteControlPresentation(state, text)
    private val REMOTE_CONTROL_CONSENT: String get() = remoteControlConsent(text)

    @Test fun defaultsAreNotConsentOrAConfirmedConnection() {
        val view = remoteControlPresentation(RemoteControlSnapshot())
        assertEquals("Freigabe: nicht erlaubt", view.consent)
        assertEquals("Nicht geprüft", view.status)
        assertFalse(view.canEnable)
        assertFalse(view.canPair)
    }

    @Test fun enableRequiresSupportedAndReadyRuntime() {
        assertTrue(remoteControlPresentation(ready()).canEnable)
        for (capability in RemoteControlCapability.entries.filterNot { it == RemoteControlCapability.SUPPORTED }) {
            assertFalse(remoteControlPresentation(ready().copy(capability = capability)).canEnable)
        }
        assertFalse(remoteControlPresentation(ready().copy(runtimeReady = false)).canEnable)
    }

    @Test fun anEnableRequestCannotOptimisticallyBecomeConnected() {
        val view = remoteControlPresentation(ready().copy(
            localConsentGranted = true, pendingOperation = RemoteControlOperation.ENABLE))
        assertEquals("Aus", view.status)
        assertFalse(view.canEnable)
        assertTrue(view.canDisable)
        assertFalse(view.canPair)
    }

    @Test fun connectedServerWithoutLocalConsentDoesNotAuthorizePhoneTools() {
        val view = remoteControlPresentation(ready(RemoteControlStatus.CONNECTED))
        assertEquals("Für Desktop bereit", view.status)
        assertEquals("Freigabe: nicht erlaubt", view.consent)
        assertFalse(view.canPair)
        assertTrue(view.canEnable)
    }

    @Test fun oldUnconfirmedStatusIsUnknownAndNeverEnablesPairing() {
        val view = remoteControlPresentation(ready(RemoteControlStatus.CONNECTED).copy(
            statusConfirmedForCurrentRuntime = false, localConsentGranted = true))
        assertEquals("Nicht geprüft", view.status)
        assertFalse(view.canPair)
        assertTrue(view.canDisable)
    }

    @Test fun disableRemainsAvailableDuringAmbiguousStatusAndTransportLoss() {
        val view = remoteControlPresentation(ready(RemoteControlStatus.CONNECTED).copy(
            runtimeReady = false, statusConfirmedForCurrentRuntime = false,
            localConsentGranted = true, issue = RemoteControlIssue.TIMED_OUT))
        assertTrue(view.canDisable)
        assertFalse(view.canEnable)
        assertFalse(view.canPair)
    }

    @Test fun allConfirmedRpcStatesHaveDistinctTruthfulLabels() {
        val labels = RemoteControlStatus.entries.map { remoteControlPresentation(ready(it)).status }
        assertEquals(RemoteControlStatus.entries.size, labels.distinct().size)
    }

    @Test fun permissionIsSeparateFromServiceReadinessAndNeverClaimsADesktopConnection() {
        listOf(java.util.Locale.ENGLISH, java.util.Locale.GERMAN).forEach { locale ->
            val resolver = ai.hans.standard.localization.TestResourceTextResolver(locale)
            val views = RemoteControlStatus.entries.map { status ->
                ai.hans.standard.ui.remoteControlPresentation(ready(status).copy(localConsentGranted = true), resolver)
            }
            assertEquals(1, views.map { it.consent }.distinct().size)
            assertTrue(views.none { it.status.contains("connected", ignoreCase = true) || it.status.contains("verbunden", ignoreCase = true) })
            val ready = ai.hans.standard.ui.remoteControlPresentation(
                ready(RemoteControlStatus.CONNECTED).copy(localConsentGranted = true), resolver)
            assertEquals(if (locale.language == "de") "Für Desktop bereit" else "Ready for desktop", ready.status)
            assertTrue(ready.canPair)
        }
    }

    @Test fun listAndRevokeNeedAnExistingEnvironmentButNotCurrentConsent() {
        assertTrue(remoteControlPresentation(ready()).canManageClients)
        assertFalse(remoteControlPresentation(ready().copy(connection = null)).canManageClients)
        assertFalse(remoteControlPresentation(ready().copy(pendingOperation = RemoteControlOperation.REVOKE)).canManageClients)
    }

    @Test fun expiryAndPairingSecretsStayWithCoordinatorNotUiSavedStateOrPolling() {
        val source = sequenceOf(
            File("src/main/java/ai/hans/standard/ui/RemoteControlSettings.kt"),
            File("android/app/src/main/java/ai/hans/standard/ui/RemoteControlSettings.kt"),
        ).first(File::isFile).readText()
        assertFalse(source.contains("rememberSaveable("))
        assertFalse(source.contains("LaunchedEffect("))
        assertFalse(source.contains("delay("))
        assertTrue(source.contains(".clickable(role = Role.Button, onClickLabel = copyLabel)"))
        assertTrue(source.contains("ClipDescription.EXTRA_IS_SENSITIVE"))
        assertTrue(source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU"))
        assertTrue(source.contains("sensitive = true"))
        assertFalse(source.contains("Log."))
        assertTrue(source.contains("pairing.manualPairingCode ?: pairing.pairingCode"))
    }

    @Test fun consentNamesPrivateDataDirectionLifetimeAndLimits() {
        assertTrue(REMOTE_CONTROL_CONSENT.contains("private Hans-Daten"))
        assertTrue(REMOTE_CONTROL_CONSENT.contains("Neustart oder Beenden von Hans"))
        assertTrue(REMOTE_CONTROL_CONSENT.contains("Root-Rechte"))
        assertTrue(REMOTE_CONTROL_CONSENT.contains("entsperren"))
    }

    private fun ready(status: RemoteControlStatus = RemoteControlStatus.DISABLED) = RemoteControlSnapshot(
        generation = 1, runtimeReady = true, capability = RemoteControlCapability.SUPPORTED,
        connection = RemoteControlConnection(status, "installation-test", "Hans", "environment-test"),
        statusConfirmedForCurrentRuntime = true,
    )
}
