package com.vpnproject.app.engine

import com.vpnproject.app.core.ConfigKind
import com.vpnproject.app.profile.VpnProfileKind

/**
 * Catalog of in-app runtimes and the explicit OpenVPN external handoff.
 * Input parsers/mappers are described separately by [ProfileExecutionRoute].
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
            description = "Embedded runtime for supported user-owned V2Ray/Xray links and mapped sing-box/Clash outbounds."
        ),
        RegisteredEngine(
            id = VpnEngineId.WIREGUARD_GO,
            engineKind = EngineKind.WIREGUARD_GO,
            displayName = "WireGuard GoBackend",
            embedded = true,
            startableInApp = true,
            priority = 30,
            description = "Embedded WireGuard runtime for user-supplied configs."
        ),
        RegisteredEngine(
            id = VpnEngineId.OPENVPN_EXTERNAL,
            engineKind = null,
            displayName = "OpenVPN handoff",
            embedded = false,
            startableInApp = false,
            priority = 40,
            description = "No in-app OpenVPN runtime; prepares a user-selected export for an external compatible client."
        )
    )

    private val routes: Map<VpnProfileKind, ProfileExecutionRoute> = mapOf(
        VpnProfileKind.XRAY to ProfileExecutionRoute(
            adapterId = ProfileAdapterId.V2RAY_XRAY_LINKS,
            runtimeEngineId = VpnEngineId.XRAY_CORE,
            executionMode = EngineExecutionMode.EMBEDDED_ENGINE,
            supportsInAppConnection = true,
            description = "Supported VLESS, VMess, Trojan, and Shadowsocks links are built into an Xray runtime config."
        ),
        VpnProfileKind.SING_BOX to ProfileExecutionRoute(
            adapterId = ProfileAdapterId.SING_BOX_JSON_MAPPER,
            runtimeEngineId = VpnEngineId.XRAY_CORE,
            executionMode = EngineExecutionMode.MAPPED_TO_XRAY,
            supportsInAppConnection = true,
            description = "Only the supported outbound subset is converted to Xray; this is not a native sing-box engine."
        ),
        VpnProfileKind.CLASH to ProfileExecutionRoute(
            adapterId = ProfileAdapterId.CLASH_YAML_MAPPER,
            runtimeEngineId = VpnEngineId.XRAY_CORE,
            executionMode = EngineExecutionMode.MAPPED_TO_XRAY,
            supportsInAppConnection = true,
            description = "Only supported Clash/Clash.Meta proxies are converted to Xray; this is not a native Clash engine."
        ),
        VpnProfileKind.WIREGUARD to ProfileExecutionRoute(
            adapterId = ProfileAdapterId.WIREGUARD_CONFIG,
            runtimeEngineId = VpnEngineId.WIREGUARD_GO,
            executionMode = EngineExecutionMode.EMBEDDED_ENGINE,
            supportsInAppConnection = true,
            description = "The imported WireGuard config is run by the embedded GoBackend."
        ),
        VpnProfileKind.OPENVPN to ProfileExecutionRoute(
            adapterId = ProfileAdapterId.OPENVPN_PROFILE,
            runtimeEngineId = VpnEngineId.OPENVPN_EXTERNAL,
            executionMode = EngineExecutionMode.EXTERNAL_HANDOFF,
            supportsInAppConnection = false,
            description = "The app prepares an export; connection is handed to an external OpenVPN-compatible client."
        ),
        VpnProfileKind.UNKNOWN to ProfileExecutionRoute.none()
    )

    fun engineFor(kind: VpnProfileKind): RegisteredEngine = engine(routeFor(kind).runtimeEngineId)

    fun engineFor(kind: ConfigKind): RegisteredEngine = engine(routeFor(kind).runtimeEngineId)

    fun routeFor(kind: VpnProfileKind): ProfileExecutionRoute = routes[kind] ?: ProfileExecutionRoute.none()

    fun routeFor(kind: ConfigKind): ProfileExecutionRoute = routeFor(
        when (kind) {
            ConfigKind.V2RAY -> VpnProfileKind.XRAY
            ConfigKind.SING_BOX -> VpnProfileKind.SING_BOX
            ConfigKind.CLASH -> VpnProfileKind.CLASH
            ConfigKind.WIREGUARD -> VpnProfileKind.WIREGUARD
            ConfigKind.OPENVPN -> VpnProfileKind.OPENVPN
            ConfigKind.UNKNOWN -> VpnProfileKind.UNKNOWN
        }
    )

    fun engine(id: VpnEngineId): RegisteredEngine = engines.firstOrNull { it.id == id }
        ?: RegisteredEngine.none()
}

enum class VpnEngineId {
    XRAY_CORE,
    WIREGUARD_GO,
    OPENVPN_EXTERNAL,
    NONE
}

enum class ProfileAdapterId(val displayName: String) {
    V2RAY_XRAY_LINKS("V2Ray/Xray link parser"),
    SING_BOX_JSON_MAPPER("sing-box JSON mapper"),
    CLASH_YAML_MAPPER("Clash/Clash.Meta YAML mapper"),
    WIREGUARD_CONFIG("WireGuard config parser"),
    OPENVPN_PROFILE("OpenVPN profile parser"),
    NONE("Unknown config")
}

enum class EngineExecutionMode {
    EMBEDDED_ENGINE,
    MAPPED_TO_XRAY,
    EXTERNAL_HANDOFF,
    UNAVAILABLE
}

data class ProfileExecutionRoute(
    val adapterId: ProfileAdapterId,
    val runtimeEngineId: VpnEngineId,
    val executionMode: EngineExecutionMode,
    val supportsInAppConnection: Boolean,
    val description: String
) {
    fun pathLabel(runtime: RegisteredEngine): String = when (executionMode) {
        EngineExecutionMode.EMBEDDED_ENGINE -> runtime.displayName
        EngineExecutionMode.MAPPED_TO_XRAY ->
            "${adapterId.displayName} → ${runtime.displayName} (supported subset)"
        EngineExecutionMode.EXTERNAL_HANDOFF ->
            "${adapterId.displayName} → external OpenVPN-compatible client"
        EngineExecutionMode.UNAVAILABLE -> "${adapterId.displayName} → no compatible runtime"
    }

    companion object {
        fun none(): ProfileExecutionRoute = ProfileExecutionRoute(
            adapterId = ProfileAdapterId.NONE,
            runtimeEngineId = VpnEngineId.NONE,
            executionMode = EngineExecutionMode.UNAVAILABLE,
            supportsInAppConnection = false,
            description = "No compatible runtime is available for this profile."
        )
    }
}

data class RegisteredEngine(
    val id: VpnEngineId,
    /** Runtime status kind; null for external handoff entries, which have no in-app service. */
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
