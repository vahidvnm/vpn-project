package com.vpnproject.app.engine

import com.vpnproject.app.core.ConfigKind
import com.vpnproject.app.core.EndpointCandidate
import com.vpnproject.app.core.EndpointDiscovery
import com.vpnproject.app.core.ImportedConfig
import com.vpnproject.app.core.IpClassifier
import com.vpnproject.app.core.PinnedConfigRenderer
import com.vpnproject.app.core.VpnProtocol

class RuntimeConfigPreparer(
    private val endpointDiscovery: EndpointDiscovery = EndpointDiscovery()
) {
    fun prepareWireGuard(config: ImportedConfig): RuntimeConfigSelection {
        require(config.kind == ConfigKind.WIREGUARD) {
            "Only WireGuard configs can be prepared for the WireGuard engine."
        }
        val endpoint = config.endpoints.firstOrNull()
            ?: throw IllegalArgumentException("WireGuard config has no Endpoint to resolve or pin.")

        return prepareEndpointBackedRuntimeConfig(
            config = config,
            endpoint = endpoint,
            configLabel = "WireGuard",
            ipv6Note = "If the mobile network has no IPv6 path, try an IPv4 WireGuard endpoint."
        )
    }

    fun prepareOpenVpn(config: ImportedConfig): RuntimeConfigSelection {
        require(config.kind == ConfigKind.OPENVPN) {
            "Only OpenVPN configs can be prepared for OpenVPN handoff."
        }
        val endpoint = selectOpenVpnEndpoint(config.endpoints)
            ?: throw IllegalArgumentException("OpenVPN config has no remote endpoint to resolve or pin.")
        val base = prepareEndpointBackedRuntimeConfig(
            config = config,
            endpoint = endpoint,
            configLabel = "OpenVPN",
            ipv6Note = "If this endpoint fails, prefer an official OpenVPN TCP/443 profile from the provider."
        )
        val protocolNote = when (endpoint.protocol) {
            VpnProtocol.OPENVPN_TCP -> " Selected OpenVPN TCP endpoint; this is usually the better non-WireGuard fallback in Iran."
            VpnProtocol.OPENVPN_UDP -> " Warning: selected OpenVPN UDP endpoint; UDP is often blocked. Prefer provider TCP/443 configs when available."
            else -> " Protocol is not explicit; if it fails, import an official OpenVPN TCP/443 config."
        }
        return base.copy(note = base.note + protocolNote)
    }

    private fun prepareEndpointBackedRuntimeConfig(
        config: ImportedConfig,
        endpoint: EndpointCandidate,
        configLabel: String,
        ipv6Note: String
    ): RuntimeConfigSelection {
        if (IpClassifier.isPublicIpv4(endpoint.host)) {
            return RuntimeConfigSelection(
                configText = config.originalText,
                originalHost = endpoint.host,
                selectedEndpointHost = endpoint.host,
                port = endpoint.port,
                note = "Using imported public IPv4 $configLabel endpoint ${endpoint.host}:${endpoint.port}; DNS pinning is not needed.",
                wasPinned = false,
                configKind = config.kind
            )
        }

        if (IpClassifier.isPublicIpv6(endpoint.host)) {
            return RuntimeConfigSelection(
                configText = config.originalText,
                originalHost = endpoint.host,
                selectedEndpointHost = endpoint.host,
                port = endpoint.port,
                note = "Using imported public IPv6 $configLabel endpoint [${endpoint.host}]:${endpoint.port}; DNS pinning is not needed. $ipv6Note",
                wasPinned = false,
                configKind = config.kind
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
            note = "Pinned ${endpoint.host}:${endpoint.port} to ${selected.ip}:${endpoint.port} before starting/handing off $configLabel.",
            wasPinned = true,
            configKind = config.kind
        )
    }

    private fun selectOpenVpnEndpoint(endpoints: List<EndpointCandidate>): EndpointCandidate? =
        endpoints.minWithOrNull(
            compareBy<EndpointCandidate> { endpoint ->
                when {
                    endpoint.protocol == VpnProtocol.OPENVPN_TCP && endpoint.port == 443 -> 0
                    endpoint.protocol == VpnProtocol.OPENVPN_TCP -> 1
                    endpoint.protocol == VpnProtocol.UNKNOWN && endpoint.port == 443 -> 2
                    endpoint.protocol == VpnProtocol.UNKNOWN -> 3
                    endpoint.protocol == VpnProtocol.OPENVPN_UDP && endpoint.port == 443 -> 4
                    endpoint.protocol == VpnProtocol.OPENVPN_UDP -> 5
                    else -> 6
                }
            }.thenBy { it.port }
        )
}

data class RuntimeConfigSelection(
    val configText: String,
    val originalHost: String,
    val selectedEndpointHost: String,
    val port: Int,
    val note: String,
    val wasPinned: Boolean,
    val configKind: ConfigKind
)
