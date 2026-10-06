package com.vpnproject.app.engine

data class IpCidrRoute(
    val address: String,
    val prefixLength: Int
)

/** Shared IP route policy used by the Android Xray VpnService and Xray routing JSON. */
object XrayRoutePolicy {
    const val TUN_IPV4_ADDRESS = "172.19.0.1"
    const val TUN_IPV4_PREFIX = 30
    const val TUN_IPV6_ADDRESS = "fd00:1111:2222:3333::1"
    const val TUN_IPV6_PREFIX = 128

    val FULL_TUNNEL_ROUTES = listOf(
        IpCidrRoute("0.0.0.0", 0),
        IpCidrRoute("::", 0)
    )

    /**
     * Local/private destinations are intentionally sent direct by the current
     * LAN-bypass policy. This does not establish DNS-leak or kill-switch safety.
     */
    val LAN_BYPASS_CIDRS = listOf(
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "::1/128",
        "fc00::/7",
        "fe80::/10"
    )
}
