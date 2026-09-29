package com.vpnproject.app.core

/**
 * Detects supported VPN config formats and delegates to the format parser.
 *
 * This layer deliberately does not connect anywhere and does not resolve DNS.
 * It only turns user-owned config text into endpoint candidates for the route
 * engine that will arrive in later milestones.
 */
object ConfigImporter {
    fun parse(text: String, name: String? = null): ImportedConfig {
        val normalized = text.replace("\uFEFF", "")
        if (normalized.isBlank()) {
            throw ConfigParseException("The selected file is empty.")
        }

        return when {
            WireGuardConfigParser.looksLikeWireGuard(normalized) ->
                WireGuardConfigParser.parse(normalized, name)
            OpenVpnConfigParser.looksLikeOpenVpn(normalized) ->
                OpenVpnConfigParser.parse(normalized, name)
            V2RayConfigParser.looksLikeV2Ray(normalized) ->
                V2RayConfigParser.parse(normalized, name)
            else -> throw ConfigParseException(
                "This does not look like an OpenVPN .ovpn, WireGuard .conf, or V2Ray/Xray share-link file."
            )
        }
    }
}
        }
    }
}
