package com.vpnproject.app.vpn

import com.vpnproject.app.core.IpClassifier

data class TunAddress(
    val address: String,
    val prefixLength: Int
)

data class TunRoute(
    val address: String,
    val prefixLength: Int
)

data class VpnTunnelPolicy(
    val sessionName: String,
    val mtu: Int,
    val tunAddress: TunAddress,
    val routes: List<TunRoute>,
    val dnsServers: List<String>,
    val disallowedApplications: Set<String> = emptySet(),
    val allowIpv6: Boolean = false
) {
    fun validate(): List<String> {
        val errors = mutableListOf<String>()
        if (sessionName.isBlank()) errors += "VPN session name is empty."
        if (mtu !in 1280..9000) errors += "MTU must be between 1280 and 9000."
        if (!IpClassifier.isIpv4Literal(tunAddress.address)) {
            errors += "TUN address must be an IPv4 literal."
        }
        if (tunAddress.prefixLength !in 1..32) {
            errors += "TUN prefix length must be between 1 and 32."
        }
        routes.forEach { route ->
            if (!IpClassifier.isIpv4Literal(route.address)) {
                errors += "Route ${route.address}/${route.prefixLength} is not an IPv4 route."
            }
            if (route.prefixLength !in 0..32) {
                errors += "Route prefix for ${route.address} must be between 0 and 32."
            }
        }
        if (dnsServers.isEmpty()) errors += "At least one DNS server is required."
        dnsServers.forEach { dns ->
            if (!IpClassifier.isPublicIpv4(dns)) {
                errors += "DNS server $dns must be a public IPv4 address."
            }
        }
        return errors
    }

    companion object {
        fun defaultFullTunnel(): VpnTunnelPolicy = VpnTunnelPolicy(
            sessionName = "VPN Project bootstrap tunnel",
            mtu = 1500,
            tunAddress = TunAddress("10.111.0.2", 32),
            routes = listOf(TunRoute("0.0.0.0", 0)),
            dnsServers = listOf("1.1.1.1", "9.9.9.9")
        )
    }
}
