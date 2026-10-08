package com.vpnproject.app.engine

import com.vpnproject.app.core.ImportedConfig

/**
 * Inputs needed to prepare a runtime. The imported config contains credentials,
 * so this class deliberately avoids data-class-generated stringification.
 */
class EnginePreparationRequest(
    val config: ImportedConfig,
    val profileId: String? = null,
    val options: EngineRuntimeOptions = EngineRuntimeOptions()
) {
    override fun toString(): String =
        "EnginePreparationRequest(kind=${config.kind}, profileId=${profileId ?: "none"}, config=<redacted>)"
}

data class EngineRuntimeOptions(
    val dnsServers: List<String> = emptyList(),
    val bypassPackages: List<String> = emptyList(),
    val sniffingEnabled: Boolean = true,
    val muxEnabled: Boolean = false,
    val muxConcurrency: Int = 8,
    val logLevel: String = "warning",
    val localDnsEnabled: Boolean = true,
    val fakeDnsEnabled: Boolean = false
)

/** Prepared payloads remain engine-specific and are never rendered to diagnostics. */
sealed interface PreparedEngineStart {
    val engineId: VpnEngineId
    val profileId: String?
    val note: String
}

class PreparedXrayEngineStart internal constructor(
    val runtime: V2RayRuntimeConfig,
    override val profileId: String?,
    val dnsServers: List<String>,
    val bypassPackages: List<String>
) : PreparedEngineStart {
    override val engineId: VpnEngineId = VpnEngineId.XRAY_CORE
    override val note: String get() = runtime.note

    override fun toString(): String =
        "PreparedXrayEngineStart(profileId=${profileId ?: "none"}, runtime=<redacted>)"
}

class PreparedWireGuardEngineStart internal constructor(
    val selection: RuntimeConfigSelection,
    val tunnelName: String,
    override val profileId: String?
) : PreparedEngineStart {
    override val engineId: VpnEngineId = VpnEngineId.WIREGUARD_GO
    override val note: String get() = selection.note

    override fun toString(): String =
        "PreparedWireGuardEngineStart(profileId=${profileId ?: "none"}, config=<redacted>)"
}

data class EngineVerificationSnapshot(
    val state: EngineState,
    val verified: Boolean,
    val scope: VerificationScope,
    val message: String,
    val profileId: String?,
    val egressIp: String?,
    val latencyMs: Long?
)

data class EngineFailureExplanation(
    val engineId: VpnEngineId,
    val category: EngineFailureCategory,
    val summary: String,
    val detail: String,
    val suggestedAction: String
)

data class EngineRuntimeSnapshot(
    val engineId: VpnEngineId,
    val status: EngineStatus,
    val stats: EngineTrafficStats,
    val verification: EngineVerificationSnapshot,
    val failure: EngineFailureExplanation?
)

/**
 * Common lifecycle/query API for in-app engines.
 *
 * `prepare` may perform network resolution and must be called off the main
 * thread. Services perform their verifier after startup; `verify()` is a
 * read-only snapshot and never starts a probe by itself.
 */
interface VpnEngineAdapter {
    val engineId: VpnEngineId

    fun prepare(request: EnginePreparationRequest): PreparedEngineStart

    fun start(prepared: PreparedEngineStart)

    fun stop()

    fun status(): EngineStatus

    fun stats(): EngineTrafficStats {
        val current = status()
        return EngineTrafficStats(rxBytes = current.rxBytes, txBytes = current.txBytes)
    }

    fun verify(): EngineVerificationSnapshot = verificationFor(status())

    fun verificationFor(status: EngineStatus): EngineVerificationSnapshot = EngineVerificationSnapshot(
        state = status.state,
        verified = status.state == EngineState.VERIFIED && status.verified,
        scope = status.verificationScope,
        message = status.detail?.takeIf { it.isNotBlank() } ?: status.message,
        profileId = status.profileId,
        egressIp = status.egressIp,
        latencyMs = status.latencyMs
    )

    fun explainFailure(currentStatus: EngineStatus = status()): EngineFailureExplanation? {
        if (currentStatus.state != EngineState.FAILED) return null
        val detail = listOfNotNull(
            currentStatus.message.takeIf { it.isNotBlank() },
            currentStatus.detail?.takeIf { it.isNotBlank() }
        ).distinct().joinToString("\n")
        val category = EngineFailureClassifier.classify(currentStatus) ?: EngineFailureCategory.UNKNOWN
        return EngineFailureExplanation(
            engineId = engineId,
            category = category,
            summary = "${category.label}: ${engineId.displayName()} connection failed",
            detail = detail.ifBlank { "The engine reported a failure without additional details." },
            suggestedAction = category.suggestedAction
        )
    }

    fun snapshot(): EngineRuntimeSnapshot {
        val current = status()
        return EngineRuntimeSnapshot(
            engineId = engineId,
            status = current,
            stats = EngineTrafficStats(rxBytes = current.rxBytes, txBytes = current.txBytes),
            verification = verificationFor(current),
            failure = explainFailure(current)
        )
    }
}

private fun VpnEngineId.displayName(): String = when (this) {
    VpnEngineId.XRAY_CORE -> "Embedded Xray"
    VpnEngineId.WIREGUARD_GO -> "WireGuard"
    VpnEngineId.OPENVPN_EXTERNAL -> "OpenVPN handoff"
    VpnEngineId.NONE -> "Unknown engine"
}
