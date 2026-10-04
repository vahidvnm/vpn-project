package com.vpnproject.app.engine

import com.vpnproject.app.core.ConfigKind
import com.vpnproject.app.profile.VpnProfileKind

/**
 * Registry of engines behind the VPN Hub. It is intentionally metadata-only for
 * now; service start/stop still lives in MainActivity while the UI is migrated.
 */
object EngineRegistry {
    val engines: List<RegisteredEngine> = listOf(
        RegisteredEngine(
            id = VpnEngineId.XRAY_CORE,
            engineKind = EngineKind.XRAY_CORE,
            displayName = "Embedded Xray",
            embedded = true,
            startableInApp = true,
            priority = 10,
            description = "Primary Iran MVP path for user-owned V2Ray/Xray links."
        ),
        RegisteredEngine(
            id = VpnEngineId.WIREGUARD_GO,
            engineKind = EngineKind.WIREGUARD_GO,
            displayName = "WireGuard GoBackend",
            embedded = true,
            startableInApp = true,
            priority = 30,
            description = "Useful where UDP is not filtered; secondary for Iran."
        ),
        RegisteredEngine(
            id = VpnEngineId.OPENVPN_EXTERNAL,
            engineKind = EngineKind.OPENVPN_UNAVAILABLE,
            displayName = "OpenVPN handoff",
            embedded = false,
            startableInApp = false,
            priority = 40,
            description = "Advanced handoff: exports a TCP-preferred pinned config for an official OpenVPN-compatible client."
        )
    )

    fun engineFor(kind: VpnProfileKind): RegisteredEngine = when (kind) {
        VpnProfileKind.XRAY -> engine(VpnEngineId.XRAY_CORE)
        VpnProfileKind.WIREGUARD -> engine(VpnEngineId.WIREGUARD_GO)
        VpnProfileKind.OPENVPN -> engine(VpnEngineId.OPENVPN_EXTERNAL)
        VpnProfileKind.UNKNOWN -> RegisteredEngine.none()
    }

    fun engineFor(kind: ConfigKind): RegisteredEngine = when (kind) {
        ConfigKind.V2RAY -> engine(VpnEngineId.XRAY_CORE)
        ConfigKind.WIREGUARD -> engine(VpnEngineId.WIREGUARD_GO)
        ConfigKind.OPENVPN -> engine(VpnEngineId.OPENVPN_EXTERNAL)
        ConfigKind.UNKNOWN -> RegisteredEngine.none()
    }

    private fun engine(id: VpnEngineId): RegisteredEngine = engines.first { it.id == id }
}

enum class VpnEngineId {
    XRAY_CORE,
    WIREGUARD_GO,
    OPENVPN_EXTERNAL,
    NONE
}

data class RegisteredEngine(
    val id: VpnEngineId,
    val engineKind: EngineKind?,
    val displayName: String,
    val embedded: Boolean,
    val startableInApp: Boolean,
    val priority: Int,
    val description: String
) {
    companion object {
        fun none(): RegisteredEngine = RegisteredEngine(
            id = VpnEngineId.NONE,
            engineKind = null,
            displayName = "No engine",
            embedded = false,
            startableInApp = false,
            priority = Int.MAX_VALUE,
            description = "No compatible engine is available for this profile."
        )
    }
}
