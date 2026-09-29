package com.vpnproject.app.core

import java.net.Inet6Address
import java.net.InetAddress

/** IP helpers used by pinners/resolvers before any network code is trusted. */
object IpClassifier {
    fun isIpv4Literal(value: String): Boolean = parseIpv4(value) != null

    fun isPublicIpv4(value: String): Boolean {
        val parts = parseIpv4(value) ?: return false
        return !isReserved(parts)
    }

    fun isReservedIpv4(value: String): Boolean {
        val parts = parseIpv4(value) ?: return false
        return isReserved(parts)
    }

    fun isIpv6Literal(value: String): Boolean = parseIpv6(value) != null

    fun isPublicIpv6(value: String): Boolean {
        val bytes = parseIpv6(value) ?: return false
        return isGlobalUnicastIpv6(bytes) && !isDocumentationIpv6(bytes)
    }

    private fun parseIpv4(value: String): IntArray? {
        val pieces = value.trim().split('.')
        if (pieces.size != 4) return null
        val out = IntArray(4)
        for ((index, piece) in pieces.withIndex()) {
            if (piece.isEmpty() || piece.length > 3 || piece.any { it !in '0'..'9' }) return null
            val n = piece.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[index] = n
        }
        return out
    }

    private fun parseIpv6(value: String): ByteArray? {
        val trimmed = value.trim().trim('[', ']').substringBefore('%')
        if (!trimmed.contains(':')) return null
        return try {
            val address = InetAddress.getByName(trimmed)
            if (address is Inet6Address) address.address else null
        } catch (_: Exception) {
            null
        }
    }

    private fun isReserved(parts: IntArray): Boolean {
        val a = parts[0]
        val b = parts[1]
        val c = parts[2]
        return when {
            a == 0 -> true
            a == 10 -> true
            a == 100 && b in 64..127 -> true // carrier-grade NAT
            a == 127 -> true
            a == 169 && b == 254 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 0 && c == 0 -> true // IETF protocol assignments
            a == 192 && b == 0 && c == 2 -> true // documentation TEST-NET-1
            a == 192 && b == 88 && c == 99 -> true // deprecated 6to4 relay anycast
            a == 192 && b == 168 -> true
            a == 198 && b in 18..19 -> true // benchmark networks
            a == 198 && b == 51 && c == 100 -> true // documentation TEST-NET-2
            a == 203 && b == 0 && c == 113 -> true // documentation TEST-NET-3
            a >= 224 -> true
            else -> false
        }
    }

    private fun isGlobalUnicastIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xFF
        return (first and 0xE0) == 0x20 // 2000::/3
    }

    private fun isDocumentationIpv6(bytes: ByteArray): Boolean =
        (bytes[0].toInt() and 0xFF) == 0x20 &&
            (bytes[1].toInt() and 0xFF) == 0x01 &&
            (bytes[2].toInt() and 0xFF) == 0x0D &&
            (bytes[3].toInt() and 0xFF) == 0xB8 // 2001:db8::/32
}
