package com.vpnproject.app.core

object WireGuardConfigParser {
    private val sectionLine = Regex("^\\s*\\[([^]]+)]\\s*(?:[#;].*)?$")
    private val keyValueLine = Regex("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*(.*?)\\s*(?:[#;].*)?$")

    fun looksLikeWireGuard(text: String): Boolean {
        var hasInterface = false
        var hasPeer = false
        for (line in text.lines()) {
            val section = sectionLine.find(line)?.groupValues?.get(1)?.trim()?.lowercase()
            if (section == "interface") hasInterface = true
            if (section == "peer") hasPeer = true
        }
        return hasInterface && hasPeer && text.contains("PrivateKey", ignoreCase = true)
    }

    fun hasIpv6DefaultRoute(text: String): Boolean {
        var section = ""
        for (line in text.lines()) {
            val sectionMatch = sectionLine.find(line)
            if (sectionMatch != null) {
                section = sectionMatch.groupValues[1].trim().lowercase()
                continue
            }
            if (section != "peer") continue
            val keyValue = keyValueLine.find(line) ?: continue
            if (!keyValue.groupValues[1].trim().equals("allowedips", ignoreCase = true)) continue
            if (keyValue.groupValues[2].split(',').any(::isIpv6DefaultRoute)) return true
        }
        return false
    }

    private fun isIpv6DefaultRoute(route: String): Boolean {
        val parts = route.trim().split('/', limit = 2)
        if (parts.size != 2 || parts[1].trim() != "0") return false
        val address = parts[0].trim()
        return address.contains(':')
    }

    fun parse(text: String, name: String? = null): ImportedConfig {
        val endpoints = mutableListOf<EndpointCandidate>()
        val warnings = mutableListOf<String>()
        var section = ""

        for (line in text.lines()) {
            val sectionMatch = sectionLine.find(line)
            if (sectionMatch != null) {
                section = sectionMatch.groupValues[1].trim().lowercase()
                continue
            }
            if (section != "peer") continue
            val keyValue = keyValueLine.find(line) ?: continue
            val key = keyValue.groupValues[1].trim().lowercase()
            val value = keyValue.groupValues[2].trim()
            if (key != "endpoint") continue

            val endpoint = parseEndpoint(value)
            if (endpoint == null) {
                warnings += "Could not read WireGuard endpoint: $value"
                continue
            }
            endpoints += EndpointCandidate(
                host = endpoint.host,
                port = endpoint.port,
                protocol = VpnProtocol.WIREGUARD,
                verifyHost = null,
                source = CandidateSource.IMPORTED_CONFIG
            )
        }

        if (!hasIpv6DefaultRoute(text)) {
            warnings += "No IPv6 default route (::/0) was found in peer AllowedIPs. This app will refuse to start this profile because IPv6 could bypass WireGuard; ask the provider for an IPv6-routed config."
        }
        if (endpoints.isEmpty()) {
            throw ConfigParseException("WireGuard config has no peer Endpoint to test or pin.")
        }

        return ImportedConfig(
            kind = ConfigKind.WIREGUARD,
            name = name,
            originalText = text,
            endpoints = endpoints.distinct(),
            warnings = warnings
        )
    }

    internal fun parseEndpoint(value: String): ParsedEndpoint? {
        val trimmed = value.trim().trim('"', '\'')
        if (trimmed.isBlank()) return null

        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            if (end <= 1 || end + 2 > trimmed.length || trimmed.getOrNull(end + 1) != ':') return null
            val host = trimmed.substring(1, end)
            val port = trimmed.substring(end + 2).toIntOrNull() ?: return null
            return ParsedEndpoint(host, port)
        }

        val splitAt = trimmed.lastIndexOf(':')
        if (splitAt <= 0 || splitAt == trimmed.lastIndex) return null
        val host = trimmed.substring(0, splitAt)
        val port = trimmed.substring(splitAt + 1).toIntOrNull() ?: return null
        return ParsedEndpoint(host, port)
    }

    internal data class ParsedEndpoint(val host: String, val port: Int)
}
