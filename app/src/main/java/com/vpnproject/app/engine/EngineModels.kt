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
    STOPPING,
    STOPPED,
    FAILED
}

data class EngineStatus(
    val kind: EngineKind,
    val state: EngineState,
    val message: String,
    val detail: String? = null
)
