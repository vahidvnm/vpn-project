package com.vpnproject.app.core

/** Protocols we can infer from imported configs before a real engine exists. */
enum class VpnProtocol {
    OPENVPN_TCP,
    OPENVPN_UDP,
    WIREGUARD,
    UNKNOWN
}

data class EndpointCandidate(
    val host: String,
    val port: Int,
    val protocol: VpnProtocol,
    val verifyHost: String? = null,
    val source: CandidateSource = CandidateSource.IMPORTED_CONFIG
)

enum class CandidateSource {
    IMPORTED_CONFIG,
    DNS_OVER_HTTPS,
    SIGNED_MANIFEST,
    LAST_GOOD_CACHE
}

enum class ProbeKind {
    DNS_ONLY,
    TCP_CONNECT,
    TLS_HANDSHAKE,
    UNSUPPORTED
}

data class HealthResult(
    val candidate: EndpointCandidate,
    val reachable: Boolean,
    val latencyMs: Long? = null,
    val reason: String? = null,
    val probeKind: ProbeKind = ProbeKind.TCP_CONNECT,
    val score: Int? = null,
    val checkedAtEpochMs: Long = 0L
)
