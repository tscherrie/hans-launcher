package ai.hans.standard.runtime.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

fun interface HostResolver {
    fun resolve(host: String): List<InetAddress>
}

object JavaHostResolver : HostResolver {
    override fun resolve(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}

fun interface DestinationAddressPolicy {
    fun permits(address: InetAddress): Boolean
}

object PublicInternetAddressPolicy : DestinationAddressPolicy {
    override fun permits(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> permitsIpv4(address.address)
        is Inet6Address -> permitsIpv6(address.address)
        else -> false
    }

    private fun permitsIpv4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        return when {
            a == 0 || a == 10 || a == 127 -> false
            a == 100 && b in 64..127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 0 && c == 0 -> false
            a == 192 && b == 0 && c == 2 -> false
            a == 192 && b == 168 -> false
            a == 198 && b in 18..19 -> false
            a == 198 && b == 51 && c == 100 -> false
            a == 203 && b == 0 && c == 113 -> false
            a >= 224 -> false
            else -> true
        }
    }

    private fun permitsIpv6(bytes: ByteArray): Boolean {
        // Only globally routed 2000::/3 is admitted, then explicitly remove
        // documentation and transition prefixes that must never be dialled.
        val first = bytes[0].toInt() and 0xff
        if (first !in 0x20..0x3f) return false
        if (prefixMatches(bytes, byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte()), 32)) return false
        if (prefixMatches(bytes, byteArrayOf(0x20, 0x01, 0x00, 0x00), 32)) return false // Teredo
        if (prefixMatches(bytes, byteArrayOf(0x20, 0x02), 16)) return false // 6to4
        return true
    }

    private fun prefixMatches(address: ByteArray, prefix: ByteArray, bits: Int): Boolean {
        val fullBytes = bits / 8
        val remainder = bits % 8
        for (index in 0 until fullBytes) if (address[index] != prefix[index]) return false
        if (remainder == 0) return true
        val mask = (0xff shl (8 - remainder)) and 0xff
        return (address[fullBytes].toInt() and mask) == (prefix[fullBytes].toInt() and mask)
    }
}

data class ResolvedConnectTarget(
    val host: String,
    val port: Int,
    val addresses: List<InetAddress>,
)

class ConnectTargetPolicy(
    private val resolver: HostResolver = JavaHostResolver,
    private val allowedPorts: Set<Int> = setOf(443),
    private val addressPolicy: DestinationAddressPolicy = PublicInternetAddressPolicy,
) {
    init {
        require(allowedPorts.isNotEmpty() && allowedPorts.all { it in 1..65535 })
    }

    fun resolveAndValidate(target: ConnectTarget): ResolvedConnectTarget? {
        if (target.port !in allowedPorts) return null
        val resolved = runCatching { resolver.resolve(target.host) }.getOrNull().orEmpty()
        if (resolved.isEmpty() || resolved.size > MAX_RESOLVED_ADDRESSES) return null
        // Fail the entire request when DNS returns even one forbidden address.
        // The exact validated InetAddress objects are later used for Socket.connect,
        // so no second hostname lookup can rebind the destination.
        if (resolved.any { !addressPolicy.permits(it) }) return null
        val unique = resolved.distinctBy { it.address.toList() }
            .sortedWith(compareBy<InetAddress>({ it.address.size }, { it.address.toHexString() }))
        return ResolvedConnectTarget(target.host, target.port, unique)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val MAX_RESOLVED_ADDRESSES = 16
    }
}
