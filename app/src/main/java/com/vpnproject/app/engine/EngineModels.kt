package com.vpnproject.app.engine

enum class EngineKind {
    WIREGUARD_GO,
    OPENVPN_UNAVAILABLE
}

enum class EngineState {
    IDLE,
    PREPARING_CONFIG,
    CONNECTING,
    RUNNING,
    VERIFYING,
    VERIFIED,
    STOPPING,
    STOPPED,
    FAILED
}

data class EngineStatus(
    val kind: EngineKind,
    val state: EngineState,
    val message: String,
    val detail: String? = null,
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    val egressIp: String? = null,
    val verified: Boolean = false
)
