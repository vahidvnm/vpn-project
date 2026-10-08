package com.vpnproject.app.vpn

import android.content.Context
import android.content.Intent
import android.os.Build
import com.vpnproject.app.core.ConfigKind
import com.vpnproject.app.engine.EnginePreparationRequest
import com.vpnproject.app.engine.EngineRuntimeSnapshot
import com.vpnproject.app.engine.EngineStatus
import com.vpnproject.app.engine.PreparedEngineStart
import com.vpnproject.app.engine.PreparedWireGuardEngineStart
import com.vpnproject.app.engine.PreparedXrayEngineStart
import com.vpnproject.app.engine.RuntimeConfigPreparer
import com.vpnproject.app.engine.V2RayRuntimeConfigBuilder
import com.vpnproject.app.engine.VpnEngineAdapter
import com.vpnproject.app.engine.VpnEngineId
import java.net.InetAddress
import java.net.ServerSocket

/** Android service bridge implementing the shared lifecycle/query contract. */
class AndroidVpnEngineAdapterRegistry(
    context: Context,
    runtimeConfigPreparer: RuntimeConfigPreparer
) {
    private val appContext = context.applicationContext
    private val adapters: Map<VpnEngineId, VpnEngineAdapter> = listOf<VpnEngineAdapter>(
        XrayVpnEngineAdapter(appContext),
        WireGuardVpnEngineAdapter(appContext, runtimeConfigPreparer)
    ).associateBy { it.engineId }

    fun adapterFor(engineId: VpnEngineId): VpnEngineAdapter? = adapters[engineId]

    /** Start only the selected engine; callers must await any conflicting stop first. */
    fun start(prepared: PreparedEngineStart) {
        val selected = adapters[prepared.engineId]
            ?: throw IllegalArgumentException("No in-app adapter registered for ${prepared.engineId}.")
        selected.start(prepared)
    }

    fun stop(engineId: VpnEngineId) {
        val selected = adapters[engineId]
            ?: throw IllegalArgumentException("No in-app adapter registered for $engineId.")
        selected.stop()
    }

    fun stopAll() {
        adapters.values.forEach { it.stop() }
    }

    fun status(engineId: VpnEngineId): EngineStatus = adapterFor(engineId)?.status()
        ?: throw IllegalArgumentException("No in-app adapter registered for $engineId.")

    fun snapshot(engineId: VpnEngineId): EngineRuntimeSnapshot = adapterFor(engineId)?.snapshot()
        ?: throw IllegalArgumentException("No in-app adapter registered for $engineId.")
}

private class XrayVpnEngineAdapter(
    private val context: Context
) : VpnEngineAdapter {
    override val engineId: VpnEngineId = VpnEngineId.XRAY_CORE

    override fun prepare(request: EnginePreparationRequest): PreparedEngineStart {
        val route = com.vpnproject.app.engine.EngineRegistry.routeFor(request.config.kind)
        require(route.runtimeEngineId == engineId) {
            "${request.config.kind} does not map to the embedded Xray engine."
        }
        val options = request.options
        val runtime = V2RayRuntimeConfigBuilder.build(
            config = request.config,
            dnsServers = options.dnsServers,
            sniffingEnabled = options.sniffingEnabled,
            muxEnabled = options.muxEnabled,
            muxConcurrency = options.muxConcurrency,
            logLevel = options.logLevel,
            localHttpProxyPort = allocateLocalProxyPort(),
            localDnsEnabled = options.localDnsEnabled,
            fakeDnsEnabled = options.fakeDnsEnabled
        )
        return PreparedXrayEngineStart(
            runtime = runtime,
            profileId = request.profileId,
            dnsServers = options.dnsServers.toList(),
            bypassPackages = options.bypassPackages.toList()
        )
    }

    override fun start(prepared: PreparedEngineStart) {
        require(prepared is PreparedXrayEngineStart && prepared.engineId == engineId) {
            "Xray adapter received a prepared config for another engine."
        }
        val runtime = prepared.runtime
        val intent = Intent(context, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_START
            putExtra(XrayVpnService.EXTRA_CONFIG_JSON, runtime.configJson)
            putExtra(XrayVpnService.EXTRA_PROFILE_NAME, safeTunnelName(runtime.profileName))
            putExtra(XrayVpnService.EXTRA_NOTE, runtime.note)
            prepared.profileId?.let { putExtra(XrayVpnService.EXTRA_PROFILE_ID, it) }
            runtime.localHttpProxyPort?.let { putExtra(XrayVpnService.EXTRA_LOCAL_HTTP_PROXY_PORT, it) }
            putStringArrayListExtra(XrayVpnService.EXTRA_DNS_SERVERS, ArrayList(prepared.dnsServers))
            putStringArrayListExtra(XrayVpnService.EXTRA_BYPASS_PACKAGES, ArrayList(prepared.bypassPackages))
        }
        context.startVpnForegroundService(intent)
    }

    override fun stop() {
        context.startService(Intent(context, XrayVpnService::class.java).apply { action = XrayVpnService.ACTION_STOP })
    }

    override fun status(): EngineStatus = XrayVpnService.lastStatus
}

private class WireGuardVpnEngineAdapter(
    private val context: Context,
    private val runtimeConfigPreparer: RuntimeConfigPreparer
) : VpnEngineAdapter {
    override val engineId: VpnEngineId = VpnEngineId.WIREGUARD_GO

    override fun prepare(request: EnginePreparationRequest): PreparedEngineStart {
        require(request.config.kind == ConfigKind.WIREGUARD) {
            "Only WireGuard configs can be prepared for the WireGuard engine."
        }
        val selection = runtimeConfigPreparer.prepareWireGuard(request.config)
        return PreparedWireGuardEngineStart(
            selection = selection,
            tunnelName = safeTunnelName(request.config.name),
            profileId = request.profileId
        )
    }

    override fun start(prepared: PreparedEngineStart) {
        require(prepared is PreparedWireGuardEngineStart && prepared.engineId == engineId) {
            "WireGuard adapter received a prepared config for another engine."
        }
        val intent = Intent(context, WireGuardVpnService::class.java).apply {
            action = WireGuardVpnService.ACTION_START
            putExtra(WireGuardVpnService.EXTRA_CONFIG_TEXT, prepared.selection.configText)
            putExtra(WireGuardVpnService.EXTRA_TUNNEL_NAME, prepared.tunnelName)
            putExtra(WireGuardVpnService.EXTRA_NOTE, prepared.selection.note)
            prepared.profileId?.let { putExtra(WireGuardVpnService.EXTRA_PROFILE_ID, it) }
        }
        context.startVpnForegroundService(intent)
    }

    override fun stop() {
        context.startService(Intent(context, WireGuardVpnService::class.java).apply { action = WireGuardVpnService.ACTION_STOP })
    }

    override fun status(): EngineStatus = WireGuardVpnService.lastStatus
}

private fun Context.startVpnForegroundService(intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(intent)
    } else {
        startService(intent)
    }
}

private fun safeTunnelName(name: String?): String = name
    ?.substringBeforeLast('.')
    ?.replace(Regex("[^A-Za-z0-9_=+.-]"), "-")
    ?.take(30)
    ?.takeIf { it.isNotBlank() }
    ?: "vpn-project-wg"

private fun allocateLocalProxyPort(): Int? = runCatching {
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
        socket.localPort.takeIf { it in 1024..65535 }
    }
}.getOrNull()
