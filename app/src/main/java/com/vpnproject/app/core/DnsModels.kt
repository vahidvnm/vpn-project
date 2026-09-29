package com.vpnproject.app.core

/** DNS-over-HTTPS providers used only to resolve user-imported hostnames. */
enum class DohProvider(val displayName: String, val endpointUrls: List<String>) {
    CLOUDFLARE(
        "Cloudflare",
        listOf(
            "https://1.1.1.1/dns-query",
            "https://1.0.0.1/dns-query",
            "https://cloudflare-dns.com/dns-query"
        )
    ),
    GOOGLE(
        "Google",
        listOf(
            "https://8.8.8.8/resolve",
            "https://8.8.4.4/resolve",
            "https://dns.google/resolve"
        )
    ),
    QUAD9(
        "Quad9",
        listOf(
            "https://9.9.9.9/dns-query",
            "https://149.112.112.112/dns-query",
            "https://dns.quad9.net/dns-query"
        )
    );

    val endpointUrl: String get() = endpointUrls.first()
}

data class ResolvedAddress(
    val hostname: String,
    val ip: String,
    val provider: DohProvider?,
    val ttlSeconds: Long,
    val expiresAtEpochMs: Long
)

data class DnsLookupResult(
    val hostname: String,
    val addresses: List<ResolvedAddress>,
    val errors: List<String> = emptyList(),
    val fromCache: Boolean = false
) {
    val hasUsableAddress: Boolean get() = addresses.isNotEmpty()
}
