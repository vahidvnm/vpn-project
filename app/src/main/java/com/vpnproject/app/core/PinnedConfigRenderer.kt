package com.vpnproject.app.core

/** Renders temporary runtime configs with a selected healthy IP. */
object PinnedConfigRenderer {
    private val openVpnRemoteLine = Regex("^(\\s*remote\\s+)(\\S+)(.*)$", RegexOption.IGNORE_CASE)
    private val wireGuardEndpointLine = Regex("^(\\s*Endpoint\\s*=\\s*)(\\S+)(.*)$", RegexOption.IGNORE_CASE)

    fun render(config: ImportedConfig, endpoint: EndpointCandidate, pinnedIp: String): String {
        require(IpClassifier.isPublicIpv4(pinnedIp)) { "Pinned endpoint must be a public IPv4 address." }
        return when (config.kind) {
            ConfigKind.OPENVPN -> renderOpenVpn(config.originalText, endpoint, pinnedIp)
            ConfigKind.WIREGUARD -> renderWireGuard(config.originalText, endpoint, pinnedIp)
            ConfigKind.V2RAY,
            ConfigKind.SING_BOX,
            ConfigKind.UNKNOWN -> throw IllegalArgumentException("Unsupported config kind for IP pin rendering.")
        }
    }

    fun renderOpenVpn(text: String, endpoint: EndpointCandidate, pinnedIp: String): String {
        val newline = newlineOf(text)
        val header = listOf(
            "# pinned by VPN Project",
            "# ${endpoint.host} -> $pinnedIp"
        )
        var replaced = false
        val body = text.lines().map { line ->
            val match = openVpnRemoteLine.find(line)
            if (match != null && !replaced) {
                val host = match.groupValues[2].trim('"', '\'')
                val remoteTailTokens = OpenVpnConfigParser.splitWords(match.groupValues[3])
                val linePort = remoteTailTokens.firstOrNull()?.toIntOrNull()
                if (host == endpoint.host && (linePort == null || linePort == endpoint.port)) {
                    replaced = true
                    return@map match.groupValues[1] + pinnedIp + match.groupValues[3]
                }
            }
            line
        }
        if (!replaced) throw IllegalArgumentException("Endpoint ${endpoint.host} was not found in OpenVPN config.")
        return (header + body).joinToString(newline).ensureTrailingNewline(newline)
    }

    fun renderWireGuard(text: String, endpoint: EndpointCandidate, pinnedIp: String): String {
        val newline = newlineOf(text)
        val header = listOf(
            "# pinned by VPN Project",
            "# ${endpoint.host} -> $pinnedIp"
        )
        var replaced = false
        val body = text.lines().map { line ->
            val match = wireGuardEndpointLine.find(line)
            if (match != null && !replaced) {
                val parsed = WireGuardConfigParser.parseEndpoint(match.groupValues[2])
                if (parsed?.host == endpoint.host && parsed.port == endpoint.port) {
                    replaced = true
                    return@map match.groupValues[1] + "$pinnedIp:${endpoint.port}" + match.groupValues[3]
                }
            }
            line
        }
        if (!replaced) throw IllegalArgumentException("Endpoint ${endpoint.host}:${endpoint.port} was not found in WireGuard config.")
        return (header + body).joinToString(newline).ensureTrailingNewline(newline)
    }

    private fun newlineOf(text: String): String = if (text.contains("\r\n")) "\r\n" else "\n"

    private fun String.ensureTrailingNewline(newline: String): String =
        if (endsWith(newline)) this else this + newline
}
