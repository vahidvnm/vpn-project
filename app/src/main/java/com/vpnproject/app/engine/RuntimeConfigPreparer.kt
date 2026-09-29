package com.vpnproject.app.engine

import com.vpnproject.app.core.ConfigKind
import com.vpnproject.app.core.EndpointDiscovery
import com.vpnproject.app.core.ImportedConfig
import com.vpnproject.app.core.PinnedConfigRenderer

class RuntimeConfigPreparer(
    private val endpointDiscovery: EndpointDiscovery = EndpointDiscovery()
) {
    fun prepareWireGuard(config: ImportedConfig): RuntimeConfigSelection {
        require(config.kind == ConfigKind.WIREGUARD) {
            "Only WireGuard configs can be prepared for the WireGuard engine."
        }
        val endpoint = config.endpoints.firstOrNull()
            ?: throw IllegalArgumentException("WireGuard config has no Endpoint to resolve or pin.")
        val discovery = endpointDiscovery.discover(endpoint)
        val selected = discovery.resolved.firstOrNull()
            ?: throw IllegalArgumentException(
                "No public IPv4 candidate found for ${endpoint.host}:${endpoint.port}. " +
                    discovery.errors.joinToString("; ")
            )
        val rendered = PinnedConfigRenderer.render(config, endpoint, selected.ip)
        return RuntimeConfigSelection(
            configText = rendered,
            originalHost = endpoint.host,
            pinnedIp = selected.ip,
            port = endpoint.port,
            note = "Pinned ${endpoint.host}:${endpoint.port} to ${selected.ip}:${endpoint.port} before starting WireGuard."
        )
    }
}

data class RuntimeConfigSelection(
    val configText: String,
    val originalHost: String,
    val pinnedIp: String,
    val port: Int,
    val note: String
)
