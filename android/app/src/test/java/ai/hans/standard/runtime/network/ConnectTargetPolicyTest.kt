package ai.hans.standard.runtime.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

class ConnectTargetPolicyTest {
    @Test
    fun resolvesOnceAndReturnsOnlyValidatedAddressObjects() {
        val resolutions = AtomicInteger(0)
        val public = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        val policy = ConnectTargetPolicy(
            resolver = HostResolver {
                resolutions.incrementAndGet()
                listOf(public)
            },
        )

        val target = policy.resolveAndValidate(ConnectTarget("api.openai.com", 443))

        assertEquals(1, resolutions.get())
        assertEquals(listOf(public), target?.addresses)
    }

    @Test
    fun dnsResponseFailsClosedWhenAnyAddressIsForbidden() {
        val policy = ConnectTargetPolicy(
            resolver = HostResolver {
                listOf(
                    InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)),
                    InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
                )
            },
        )

        assertNull(policy.resolveAndValidate(ConnectTarget("rebind.invalid", 443)))
    }

    @Test
    fun blocksPrivateReservedAndNonHttpsDestinations() {
        val forbidden = listOf(
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.0.2.1",
            "192.168.0.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "255.255.255.255",
            "::1",
            "fc00::1",
            "fe80::1",
            "2001:db8::1",
            "2002::1",
        ).map(InetAddress::getByName)

        assertTrue(forbidden.all { !PublicInternetAddressPolicy.permits(it) })
        assertTrue(PublicInternetAddressPolicy.permits(InetAddress.getByName("8.8.8.8")))
        assertTrue(PublicInternetAddressPolicy.permits(InetAddress.getByName("2606:4700:4700::1111")))

        val portPolicy = ConnectTargetPolicy(
            resolver = HostResolver { listOf(InetAddress.getByName("8.8.8.8")) },
        )
        assertNull(portPolicy.resolveAndValidate(ConnectTarget("example.com", 80)))
    }
}
