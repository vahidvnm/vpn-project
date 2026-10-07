package com.vpnproject.app.engine

import java.util.Locale

/**
 * Conservative diagnosis based only on failure messages emitted by this app.
 * It intentionally does not infer censorship, provider blocking, or server
 * health from a generic connection failure.
 */
enum class EngineFailureCategory(
    val label: String,
    val suggestedAction: String
) {
    VPN_PERMISSION(
        label = "Android VPN permission",
        suggestedAction = "Grant the Android VPN permission when prompted, then retry."
    ),
    IPV6_ROUTE_POLICY(
        label = "IPv6 route policy",
        suggestedAction = "Use a provider-supplied configuration that routes IPv6 through the tunnel; this message is a leak-prevention guard, not evidence of blocking."
    ),
    CONFIGURATION(
        label = "Profile or configuration",
        suggestedAction = "Check the user-supplied profile and runtime compatibility. Keep credentials and raw configuration private."
    ),
    NETWORK_RECOVERY(
        label = "Network recovery",
        suggestedAction = "Wait for the Wi-Fi or mobile network to stabilize, then retry. A rebind error alone does not identify censorship or server failure."
    ),
    STOP_OPERATION(
        label = "Tunnel stop",
        suggestedAction = "The engine reported a stop error. Do not hand off to another engine until the previous tunnel is confirmed stopped."
    ),
    ENGINE_START(
        label = "Engine start",
        suggestedAction = "Review the engine detail and supplied profile; verify Android VPN permission and runtime compatibility."
    ),
    UNKNOWN(
        label = "Cause undetermined",
        suggestedAction = "The available status does not establish a root cause. Do not infer censorship or server failure from this message alone."
    )
}

object EngineFailureClassifier {
    fun classify(status: EngineStatus): EngineFailureCategory? {
        if (status.state != EngineState.FAILED) return null
        return classifyFailure(status.message, status.detail)
    }

    fun classifyFailure(message: String, detail: String? = null): EngineFailureCategory {
        val normalizedMessage = message.trim().lowercase(Locale.ROOT)
        val normalizedDetail = detail.orEmpty().trim().lowercase(Locale.ROOT)
        val combined = "$normalizedMessage\n$normalizedDetail"

        return when {
            "allowedips lacks ::/0" in combined || "ipv6 could bypass the tunnel" in combined ->
                EngineFailureCategory.IPV6_ROUTE_POLICY
            "vpn permission" in combined && ("not granted" in combined || "missing" in combined || "required" in combined) ->
                EngineFailureCategory.VPN_PERMISSION
            "stop failed" in combined || "failed to stop" in combined ->
                EngineFailureCategory.STOP_OPERATION
            "rebind" in combined || "network recovery" in combined ->
                EngineFailureCategory.NETWORK_RECOVERY
            "config is missing" in combined ||
                "runtime config is empty" in combined ||
                "unsupported transport" in combined ||
                "invalid configuration" in combined ||
                "invalid config" in combined ||
                "failed to parse config" in combined ||
                "runtime config failed" in combined ->
                EngineFailureCategory.CONFIGURATION
            normalizedMessage.startsWith("xray startup failed:") ||
                normalizedMessage.startsWith("wireguard failed:") ||
                " start failed:" in normalizedMessage ||
                "did not enter up state" in normalizedMessage ->
                EngineFailureCategory.ENGINE_START
            else -> EngineFailureCategory.UNKNOWN
        }
    }
}
