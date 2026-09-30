package com.vpnproject.app.engine

import com.vpnproject.app.profile.VpnProfile

/**
 * User-facing connection summary for the VPN Hub. EngineStatus remains the
 * detailed per-engine diagnostic model; this class picks the best active engine
 * and translates it into simple Connected/Connecting/Failed language.
 */
data class VpnHubStatus(
    val state: VpnHubConnectionState,
    val title: String,
    val detail: String,
    val activeEngine: EngineKind? = null,
    val verified: Boolean = false,
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    val egressIp: String? = null
)

enum class VpnHubConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RUNNING_UNVERIFIED,
    STOPPED,
    FAILED
}

object VpnHubStatusMapper {
    fun from(
        wireGuard: EngineStatus,
        xray: EngineStatus,
        selectedProfile: VpnProfile? = null
    ): VpnHubStatus {
        val selectedText = selectedProfile?.displayName?.let { "Profile: $it. " }.orEmpty()
        val candidates = listOf(xray, wireGuard)

        candidates.firstOrNull { it.state == EngineState.VERIFIED && it.verified }?.let { status ->
            return VpnHubStatus(
                state = VpnHubConnectionState.CONNECTED,
                title = "Connected",
                detail = selectedText + "${status.kind.displayName()} is verified. ${status.message}",
                activeEngine = status.kind,
                verified = true,
                rxBytes = status.rxBytes,
                txBytes = status.txBytes,
                egressIp = status.egressIp
            )
        }

        candidates.firstOrNull { it.state == EngineState.CONNECTING || it.state == EngineState.VERIFYING || it.state == EngineState.PREPARING_CONFIG }?.let { status ->
            return VpnHubStatus(
                state = VpnHubConnectionState.CONNECTING,
                title = "Connecting",
                detail = selectedText + "${status.kind.displayName()} is ${status.state.name.lowercase()}. ${status.message}",
                activeEngine = status.kind,
                verified = false,
                rxBytes = status.rxBytes,
                txBytes = status.txBytes,
                egressIp = status.egressIp
            )
        }

        candidates.firstOrNull { it.state == EngineState.RUNNING }?.let { status ->
            return VpnHubStatus(
                state = VpnHubConnectionState.RUNNING_UNVERIFIED,
                title = "Running, not verified yet",
                detail = selectedText + "${status.kind.displayName()} is running but verification has not passed yet. ${status.message}",
                activeEngine = status.kind,
                verified = false,
                rxBytes = status.rxBytes,
                txBytes = status.txBytes,
                egressIp = status.egressIp
            )
        }

        candidates.firstOrNull { it.state == EngineState.FAILED }?.let { status ->
            return VpnHubStatus(
                state = VpnHubConnectionState.FAILED,
                title = "Connection failed",
                detail = selectedText + "${status.kind.displayName()} failed. ${status.message}",
                activeEngine = status.kind,
                verified = false,
                rxBytes = status.rxBytes,
                txBytes = status.txBytes,
                egressIp = status.egressIp
            )
        }

        val anyStopped = candidates.any { it.state == EngineState.STOPPED || it.state == EngineState.STOPPING }
        return VpnHubStatus(
            state = if (anyStopped) VpnHubConnectionState.STOPPED else VpnHubConnectionState.IDLE,
            title = if (anyStopped) "Disconnected" else "Idle",
            detail = selectedText + if (anyStopped) "No VPN engine is active." else "Import or select a profile to connect.",
            verified = false
        )
    }

    private fun EngineKind.displayName(): String = when (this) {
        EngineKind.XRAY_CORE -> "Embedded Xray"
        EngineKind.WIREGUARD_GO -> "WireGuard"
        EngineKind.OPENVPN_UNAVAILABLE -> "OpenVPN handoff"
    }
}
