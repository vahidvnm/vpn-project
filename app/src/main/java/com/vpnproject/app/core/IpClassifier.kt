package com.vpnproject.app.core

/** IPv4 helpers used by the pinner before any network code is trusted. */
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
}
