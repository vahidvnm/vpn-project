package com.vpnproject.app.core

/** Resolved IP tied back to the imported endpoint it came from. */
data class ResolvedEndpointCandidate(
    val endpoint: EndpointCandidate,
    val ip: String,
    val provider: DohProvider?,
    val ttlSeconds: Long,
    val expiresAtEpochMs: Long
) {
    val port: Int get() = endpoint.port
    val protocol: VpnProtocol get() = endpoint.protocol
    val originalHost: String get() = endpoint.host
    val verifyHost: String? get() = endpoint.verifyHost ?: endpoint.host.takeUnless { IpClassifier.isIpv4Literal(it) }

    fun asPinnedEndpointCandidate(): EndpointCandidate = endpoint.copy(
        host = ip,
        verifyHost = verifyHost,
        source = if (provider == null) endpoint.source else CandidateSource.DNS_OVER_HTTPS
    )
}

data class EndpointDiscoveryResult(
    val endpoint: EndpointCandidate,
    val resolved: List<ResolvedEndpointCandidate>,
    val errors: List<String> = emptyList(),
    val fromCache: Boolean = false
)

class EndpointDiscovery(
    private val resolver: DnsOverHttpsResolver = DnsOverHttpsResolver()
) {
    fun discover(endpoint: EndpointCandidate): EndpointDiscoveryResult {
        val lookup = resolver.resolveA(endpoint.host)
        return EndpointDiscoveryResult(
            endpoint = endpoint,
            resolved = lookup.addresses.map { answer ->
                ResolvedEndpointCandidate(
                    endpoint = endpoint,
                    ip = answer.ip,
                    provider = answer.provider,
                    ttlSeconds = answer.ttlSeconds,
                    expiresAtEpochMs = answer.expiresAtEpochMs
                )
            },
            errors = lookup.errors,
            fromCache = lookup.fromCache
        )
    }
}
