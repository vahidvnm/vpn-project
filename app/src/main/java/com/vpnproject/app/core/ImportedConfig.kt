package com.vpnproject.app.core

enum class ConfigKind {
    OPENVPN,
    WIREGUARD,
    V2RAY,
    SING_BOX,
    CLASH,
    UNKNOWN
}

data class ImportedConfig(
    val kind: ConfigKind,
    val name: String? = null,
    val originalText: String,
    val endpoints: List<EndpointCandidate>,
    val hasAuthUserPass: Boolean = false,
    val warnings: List<String> = emptyList()
) {
    val canBePinned: Boolean get() = endpoints.isNotEmpty()
}

class ConfigParseException(message: String) : IllegalArgumentException(message)
