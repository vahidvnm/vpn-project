package com.vpnproject.app.core

/** DNS-over-HTTPS providers used only to resolve user-imported hostnames. */
enum class DohProvider(val displayName: String, val endpointUrl: String) {
    CLOUDFLARE("Cloudflare", "https://cloudflare-dns.com/dns-query"),
    GOOGLE("Google", "https://dns.google/resolve"),
    QUAD9("Quad9", "https://dns.quad9.net/dns-query")
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
