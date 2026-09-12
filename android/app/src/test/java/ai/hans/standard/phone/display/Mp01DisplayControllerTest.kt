package ai.hans.standard.phone.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp01DisplayControllerTest {
    @Test
    fun exactTrustedMp01WithReachableSocketIsAvailable() {
        val transport = RecordingTransport(connectable = true)
        val controller = controller(transport = transport)

        val result = controller.probe()

        assertTrue(result.available)
        assertEquals(Mp01DisplayAvailability.AVAILABLE, result.availability)
        assertEquals(1, transport.probes)
        assertTrue(transport.commands.isEmpty())
    }

    @Test
    fun genericDeviceNeverTouchesVendorSocket() {
        val transport = RecordingTransport(connectable = true)
        val controller = controller(
            evidence = trustedEvidence.copy(
                device = trustedEvidence.device.copy(model = "Pixel 8", device = "shiba"),
            ),
            transport = transport,
        )

        val result = controller.probe()

        assertEquals(Mp01DisplayAvailability.NOT_MP01, result.availability)
        assertEquals(0, transport.probes)
    }

    @Test
    fun untrustedVendorPackageFailsClosedBeforeSocket() {
        val transport = RecordingTransport(connectable = true)
        val controller = controller(
            evidence = trustedEvidence.copy(trustedMinimalSystemPackage = false),
            transport = transport,
        )

        val result = controller.setProfile(Mp01EinkProfile.BALANCED)

        assertEquals(Mp01DisplayCommandStatus.UNAVAILABLE, result.status)
        assertEquals(Mp01DisplayAvailability.VENDOR_UNTRUSTED, result.availability)
        assertEquals(0, transport.probes)
        assertTrue(transport.commands.isEmpty())
    }

    @Test
    fun profileAndRefreshUseOnlyDocumentedCommandsAfterFreshProbe() {
        val transport = RecordingTransport(connectable = true)
        val controller = controller(transport = transport)

        val balanced = controller.setProfile(Mp01EinkProfile.BALANCED)
        val smooth = controller.setProfile(Mp01EinkProfile.SMOOTH)
        val speed = controller.setProfile(Mp01EinkProfile.SPEED)
        val refresh = controller.fullRefresh()

        assertEquals(4, transport.probes)
        assertEquals(
            listOf('b', 's', 'p', 'r').map { it.code.toByte() },
            transport.commands,
        )
        listOf(balanced, smooth, speed, refresh).forEach {
            assertEquals(Mp01DisplayCommandStatus.SENT_UNVERIFIED, it.status)
            assertEquals(Mp01DisplayAvailability.AVAILABLE, it.availability)
        }
    }

    @Test
    fun unreachableOrFailingTransportNeverClaimsApplication() {
        val unreachable = RecordingTransport(connectable = false)
        val unavailable = controller(transport = unreachable).fullRefresh()
        assertEquals(Mp01DisplayCommandStatus.UNAVAILABLE, unavailable.status)
        assertTrue(unreachable.commands.isEmpty())

        val failing = RecordingTransport(connectable = true, failSend = true)
        val failed = controller(transport = failing).setProfile(Mp01EinkProfile.SPEED)
        assertEquals(Mp01DisplayCommandStatus.FAILED, failed.status)
        assertEquals(Mp01DisplayAvailability.SOCKET_UNREACHABLE, failed.availability)
        assertFalse(failed.status == Mp01DisplayCommandStatus.SENT_UNVERIFIED)
    }

    private fun controller(
        evidence: Mp01DisplayTrustEvidence = trustedEvidence,
        transport: RecordingTransport,
    ) = Mp01DisplayController(
        evidenceSource = Mp01DisplayTrustEvidenceSource { evidence },
        transport = transport,
    )

    private class RecordingTransport(
        private val connectable: Boolean,
        private val failSend: Boolean = false,
    ) : Mp01EinkSocketTransport {
        var probes = 0
        val commands = mutableListOf<Byte>()

        override fun canConnect(): Boolean {
            probes += 1
            return connectable
        }

        override fun send(command: Byte) {
            if (failSend) error("send failed")
            commands += command
        }
    }

    private companion object {
        val trustedEvidence = Mp01DisplayTrustEvidence(
            device = AndroidDeviceIdentity(
                manufacturer = "ALONG",
                brand = "Minimal_Phone",
                model = "MP01",
                device = "MP01",
            ),
            trustedMinimalSystemPackage = true,
        )
    }
}
