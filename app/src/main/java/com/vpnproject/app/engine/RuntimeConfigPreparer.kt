package com.vpnproject.app.engine

import com.vpnproject.app.core.ConfigKind
import com.vpnproject.app.core.EndpointDiscovery
import com.vpnproject.app.core.ImportedConfig
import com.vpnproject.app.core.IpClassifier
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

        if (IpClassifier.isPublicIpv4(endpoint.host)) {
            return RuntimeConfigSelection(
                configText = config.originalText,
                originalHost = endpoint.host,
                selectedEndpointHost = endpoint.host,
                port = endpoint.port,
                note = "Using imported public IPv4 endpoint ${endpoint.host}:${endpoint.port}; DNS pinning is not needed.",
                wasPinned = false
            )
        }

        if (IpClassifier.isPublicIpv6(endpoint.host)) {
            return RuntimeConfigSelection(
                configText = config.originalText,
                originalHost = endpoint.host,
                selectedEndpointHost = endpoint.host,
                port = endpoint.port,
                note = "Using imported public IPv6 endpoint [${endpoint.host}]:${endpoint.port}; DNS pinning is not needed. If the mobile network has no IPv6 path, try an IPv4 WireGuard endpoint.",
                wasPinned = false
            )
        }

        if (IpClassifier.isIpv4Literal(endpoint.host) || IpClassifier.isIpv6Literal(endpoint.host)) {
            throw IllegalArgumentException(
                "Endpoint ${endpoint.host}:${endpoint.port} is not a public routable IP address. " +
                    "Use an official public endpoint from the provider."
            )
        }

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
            selectedEndpointHost = selected.ip,
            port = endpoint.port,
            note = "Pinned ${endpoint.host}:${endpoint.port} to ${selected.ip}:${endpoint.port} before starting WireGuard.",
            wasPinned = true
        )
    }
}

data class RuntimeConfigSelection(
    val configText: String,
    val originalHost: String,
    val selectedEndpointHost: String,
    val port: Int,
    val note: String,
    val wasPinned: Boolean
)
