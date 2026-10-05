package com.vpnproject.app.profile

import com.vpnproject.app.core.ConfigKind
import com.vpnproject.app.core.EndpointCandidate
import com.vpnproject.app.core.ImportedConfig
import java.security.MessageDigest
import java.util.Locale

/**
 * Stable user-facing profile metadata for the multi-engine VPN hub.
 *
 * Secrets and raw configs are intentionally not stored in this data class. They
 * live behind SecureProfileStore so UI/debug output can safely show profile
 * metadata without accidentally leaking keys, UUIDs, passwords, or private
 * provider links.
 */
data class VpnProfile(
    val id: String,
    val name: String,
    val kind: VpnProfileKind,
    val endpoints: List<VpnProfileEndpoint>,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lastVerifiedEpochMs: Long? = null,
    val lastVerifiedNetwork: String? = null,
    val lastVerifiedLatencyMs: Long? = null,
    val lastTestedEpochMs: Long? = null,
    val lastTestKind: String? = null,
    val lastTestSuccess: Boolean? = null,
    val lastTestLatencyMs: Long? = null,
    val lastTestScore: Int? = null,
    val lastTestNetwork: String? = null,
    val testNetworkHistory: String? = null,
    val favorite: Boolean = false
) {
    val displayName: String get() = name.ifBlank { kind.displayName }

    fun summary(maxEndpoints: Int = 2): String {
        val endpointText = endpoints.take(maxEndpoints).joinToString(", ") { it.summary() }
            .ifBlank { "no endpoints" }
        val more = if (endpoints.size > maxEndpoints) ", +${endpoints.size - maxEndpoints} more" else ""
        val verifiedText = lastVerifiedEpochMs?.let { ", last verified${lastVerifiedLatencyMs?.let { latency -> " ${latency}ms" }.orEmpty()}" }.orEmpty()
        val testText = lastTestedEpochMs?.let {
            val result = if (lastTestSuccess == true) "test ok" else "test failed"
            ", last $result${lastTestLatencyMs?.let { latency -> " ${latency}ms" }.orEmpty()}"
        }.orEmpty()
        return "$displayName — ${kind.displayName}: $endpointText$more$verifiedText$testText"
    }
}

enum class VpnProfileKind(val displayName: String) {
    XRAY("V2Ray/Xray"),
    SING_BOX("sing-box"),
    CLASH("Clash"),
    WIREGUARD("WireGuard"),
    OPENVPN("OpenVPN"),
    UNKNOWN("Unknown")
}

data class VpnProfileEndpoint(
    val protocol: String,
    val host: String,
    val port: Int,
    val verifyHost: String? = null
) {
    fun summary(): String = buildString {
        append(protocol)
        append(' ')
        append(host)
        append(':')
        append(port)
        verifyHost?.takeIf { it.isNotBlank() }?.let { append(" verify ").append(it) }
    }
}

object VpnProfileFactory {
    fun fromImportedConfig(
        config: ImportedConfig,
        importedName: String? = config.name,
        nowEpochMs: Long = System.currentTimeMillis()
    ): VpnProfile {
        val name = importedName
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: config.name?.takeIf { it.isNotBlank() }
            ?: defaultProfileName(config.kind)
        return VpnProfile(
            id = stableProfileId(config, nowEpochMs),
            name = name.sanitizedProfileName(),
            kind = config.kind.toProfileKind(),
            endpoints = config.endpoints.map { it.toProfileEndpoint() },
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs
        )
    }

    private fun stableProfileId(config: ImportedConfig, nowEpochMs: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = buildString {
            append(config.kind.name)
            append('\n')
            append(config.name.orEmpty())
            append('\n')
            append(config.originalText)
            append('\n')
            append(nowEpochMs)
        }.toByteArray(Charsets.UTF_8)
        val hash = digest.digest(input).joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
        return "profile-${hash.take(16)}"
    }

    private fun defaultProfileName(kind: ConfigKind): String = when (kind) {
        ConfigKind.V2RAY -> "V2Ray/Xray profile"
        ConfigKind.SING_BOX -> "sing-box profile"
        ConfigKind.CLASH -> "Clash profile"
        ConfigKind.WIREGUARD -> "WireGuard profile"
        ConfigKind.OPENVPN -> "OpenVPN profile"
        ConfigKind.UNKNOWN -> "Imported profile"
    }

    private fun ConfigKind.toProfileKind(): VpnProfileKind = when (this) {
        ConfigKind.V2RAY -> VpnProfileKind.XRAY
        ConfigKind.SING_BOX -> VpnProfileKind.SING_BOX
        ConfigKind.CLASH -> VpnProfileKind.CLASH
        ConfigKind.WIREGUARD -> VpnProfileKind.WIREGUARD
        ConfigKind.OPENVPN -> VpnProfileKind.OPENVPN
        ConfigKind.UNKNOWN -> VpnProfileKind.UNKNOWN
    }

    private fun EndpointCandidate.toProfileEndpoint(): VpnProfileEndpoint = VpnProfileEndpoint(
        protocol = protocol.name,
        host = host,
        port = port,
        verifyHost = verifyHost
    )

    private fun String.sanitizedProfileName(): String = replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
        .ifBlank { "Imported profile" }
}
