package com.vpnproject.app.engine

enum class EngineKind {
    WIREGUARD_GO,
    XRAY_CORE
}

enum class EngineState {
    IDLE,
    PREPARING_CONFIG,
    CONNECTING,
    RUNNING,
    VERIFYING,
    VERIFIED,
    RECONNECTING,
    STOPPING,
    STOPPED,
    FAILED
}

enum class VerificationScope {
    NONE,
    XRAY_PROXY_EGRESS,
    WIREGUARD_TUNNEL_TRAFFIC_AND_EGRESS
}

data class EngineStatus(
    val kind: EngineKind,
    val state: EngineState,
    val message: String,
    val detail: String? = null,
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    val egressIp: String? = null,
    val latencyMs: Long? = null,
    val verified: Boolean = false,
    val verificationScope: VerificationScope = VerificationScope.NONE,
    val profileId: String? = null
)
