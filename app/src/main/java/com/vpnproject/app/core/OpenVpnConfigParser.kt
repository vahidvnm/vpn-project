package com.vpnproject.app.core

object OpenVpnConfigParser {
    private val remoteLine = Regex("^\\s*remote\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val protoLine = Regex("^\\s*proto\\s+(\\S+)", RegexOption.IGNORE_CASE)
    private val portLine = Regex("^\\s*port\\s+(\\d+)", RegexOption.IGNORE_CASE)
    private val authLine = Regex("^\\s*auth-user-pass(?:\\s+.*)?$", RegexOption.IGNORE_CASE)
    private val verifyLine = Regex("^\\s*verify-x509-name\\s+(\\S+)", RegexOption.IGNORE_CASE)
    private val tlsRemoteLine = Regex("^\\s*tls-remote\\s+(\\S+)", RegexOption.IGNORE_CASE)

    fun looksLikeOpenVpn(text: String): Boolean {
        val lowered = text.lowercase()
        return lowered.contains("\nremote ") || lowered.lineSequence().any {
            val line = stripComment(it).trimStart().lowercase()
            line.startsWith("remote ") || line == "client" || line.startsWith("dev ")
        }
    }

    fun parse(text: String, name: String? = null): ImportedConfig {
        val lines = text.lines()
        val globalProto = lines.firstNotNullOfOrNull { line ->
            protoLine.find(stripComment(line))?.groupValues?.get(1)
        }
        val globalPort = lines.firstNotNullOfOrNull { line ->
            portLine.find(stripComment(line))?.groupValues?.get(1)?.toIntOrNull()
        }
        val verifyHost = lines.firstNotNullOfOrNull { line ->
            val clean = stripComment(line)
            verifyLine.find(clean)?.groupValues?.get(1)
                ?: tlsRemoteLine.find(clean)?.groupValues?.get(1)
        }?.trim('"', '\'')

        val endpoints = mutableListOf<EndpointCandidate>()
        val warnings = mutableListOf<String>()

        for (line in lines) {
            val clean = stripComment(line)
            val match = remoteLine.find(clean) ?: continue
            val tokens = splitWords(match.groupValues[1])
            if (tokens.isEmpty()) continue
            val host = tokens[0].trim('"', '\'')
            val port = tokens.getOrNull(1)?.toIntOrNull() ?: globalPort ?: 1194
            val protoToken = tokens.drop(2).firstOrNull { isOpenVpnProto(it) } ?: globalProto
            val protocol = protocolOf(protoToken)
            val candidateVerifyHost = verifyHost ?: host.takeUnless { IpClassifier.isIpv4Literal(it) }
            endpoints += EndpointCandidate(
                host = host,
                port = port,
                protocol = protocol,
                verifyHost = candidateVerifyHost,
                source = CandidateSource.IMPORTED_CONFIG
            )
        }

        if (endpoints.isEmpty()) {
            throw ConfigParseException("OpenVPN config has no remote line to test or pin.")
        }

        if (verifyHost == null && endpoints.any { !IpClassifier.isIpv4Literal(it.host) }) {
            warnings += "No verify-x509-name found; pinning will keep the original hostname as the verification hint, but the engine must preserve the provider's certificate checks."
        }

        return ImportedConfig(
            kind = ConfigKind.OPENVPN,
            name = name,
            originalText = text,
            endpoints = endpoints.distinct(),
            hasAuthUserPass = lines.any { authLine.matches(stripComment(it).trim()) },
            warnings = warnings
        )
    }

    internal fun stripComment(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith('#') || trimmed.startsWith(';')) return ""
        return line
    }

    internal fun splitWords(value: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (ch in value) {
            when {
                quote != null && ch == quote -> quote = null
                quote == null && (ch == '"' || ch == '\'') -> quote = ch
                quote == null && ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        out += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun isOpenVpnProto(value: String): Boolean = when (value.lowercase()) {
        "tcp", "tcp4", "tcp6", "tcp-client", "udp", "udp4", "udp6" -> true
        else -> false
    }

    private fun protocolOf(value: String?): VpnProtocol = when (value?.lowercase()) {
        "tcp", "tcp4", "tcp6", "tcp-client" -> VpnProtocol.OPENVPN_TCP
        "udp", "udp4", "udp6" -> VpnProtocol.OPENVPN_UDP
        null -> VpnProtocol.OPENVPN_UDP
        else -> VpnProtocol.UNKNOWN
    }
}
