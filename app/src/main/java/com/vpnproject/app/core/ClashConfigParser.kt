package com.vpnproject.app.core

/**
 * Experimental parser for Clash/Clash.Meta-style YAML profile files.
 *
 * It intentionally extracts only safe endpoint/display metadata from provider or
 * user-owned configs. Secrets such as UUID, password, cipher keys, and raw YAML
 * remain encrypted in SecureProfileStore and are not returned in warnings.
 */
object ClashConfigParser {
    private val supportedProxyTypes = setOf("vless", "vmess", "trojan", "ss", "shadowsocks")

    fun looksLikeClash(text: String): Boolean {
        val normalized = text.replace("\uFEFF", "").trim()
        if (!normalized.contains("proxies:", ignoreCase = true)) return false
        return parseProxyBlocks(normalized).any { block -> parseProxy(block) != null }
    }

    fun parse(text: String, name: String? = null): ImportedConfig {
        val normalized = text.replace("\uFEFF", "")
        val parsed = parseProxyBlocks(normalized).mapNotNull { block -> parseProxy(block) }
        if (parsed.isEmpty()) {
            throw ConfigParseException("No supported Clash proxies were found. Supported proxy types: vless, vmess, trojan, ss/shadowsocks.")
        }

        val labels = parsed.groupingBy { it.label }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(", ") { (label, count) -> "$label $count" }
        val names = parsed.mapNotNull { it.name }.mapNotNull { V2RayLinkInspector.safeDisplayName(it) }
        val warnings = buildList {
            add("Clash/Clash.Meta import is experimental: proxies are saved for grouping, search, and no-VPN diagnostics. Embedded runtime mapping will use Xray or sing-box in a later stage.")
            add("Detected Clash proxies: $labels. Secrets stay encrypted locally and are not shown in UI labels.")
            parsed.filter { it.unsupportedTransport }.take(4).forEach { proxy ->
                add("Clash proxy ${V2RayLinkInspector.safeDisplayName(proxy.name) ?: proxy.endpoint.host} uses transport ${proxy.transport}; endpoint probe can run, but runtime support needs a future mapper.")
            }
        }

        return ImportedConfig(
            kind = ConfigKind.CLASH,
            name = name ?: names.firstOrNull() ?: "Clash profile",
            originalText = text,
            endpoints = parsed.map { it.endpoint }.distinct(),
            hasAuthUserPass = false,
            warnings = warnings.distinct()
        )
    }

    private fun parseProxy(block: ProxyBlock): ParsedClashProxy? {
        val rawType = block.field("type")?.lowercase() ?: return null
        if (rawType !in supportedProxyTypes) return null
        val server = block.field("server")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val port = block.intField("port")?.takeIf { it in 1..65535 } ?: return null
        val network = normalizeNetwork(block.field("network") ?: block.field("transport") ?: block.field("net"))
        val tlsEnabled = block.booleanField("tls") == true ||
            block.booleanField("skip-cert-verify") != null ||
            !block.field("servername").isNullOrBlank() ||
            !block.field("sni").isNullOrBlank() ||
            rawType == "trojan"
        val reality = block.hasKey("reality-opts") || block.hasKey("reality_opts") || block.field("flow")?.contains("xtls-rprx", ignoreCase = true) == true
        val security = when {
            reality -> "reality"
            tlsEnabled -> "tls"
            else -> "none"
        }
        val verifyHost = firstNonBlank(
            block.field("servername"),
            block.field("sni"),
            block.field("Host"),
            block.field("host"),
            server.takeUnless { IpClassifier.isIpv4Literal(it) || IpClassifier.isIpv6Literal(it) }
        )
        val protocol = when (security) {
            "reality" -> VpnProtocol.CLASH_REALITY
            "tls" -> VpnProtocol.CLASH_TLS
            "none" -> VpnProtocol.CLASH_TCP
            else -> VpnProtocol.CLASH_UNKNOWN
        }
        return ParsedClashProxy(
            name = block.field("name"),
            transport = network,
            label = displayLabel(rawType, network, security),
            unsupportedTransport = network !in setOf("tcp", "ws", "grpc", "http", "httpupgrade"),
            endpoint = EndpointCandidate(
                host = server,
                port = port,
                protocol = protocol,
                verifyHost = verifyHost,
                source = CandidateSource.IMPORTED_CONFIG
            )
        )
    }

    private fun displayLabel(type: String, transport: String, security: String): String = when {
        security == "reality" -> "Reality"
        transport == "httpupgrade" -> "HTTPUpgrade"
        transport == "grpc" -> if (security == "tls") "gRPC/TLS" else "gRPC"
        transport == "ws" -> if (security == "tls") "WS/TLS" else "WebSocket"
        transport == "http" -> if (security == "tls") "H2/TLS" else "H2"
        security == "tls" -> "TLS"
        type == "ss" || type == "shadowsocks" -> "SS"
        else -> type.uppercase()
    }

    private fun normalizeNetwork(raw: String?): String = when (raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }) {
        null, "tcp", "none" -> "tcp"
        "ws", "websocket" -> "ws"
        "grpc", "gun" -> "grpc"
        "http", "h2" -> "http"
        "httpupgrade", "http-upgrade", "http_upgrade" -> "httpupgrade"
        else -> raw.trim().lowercase()
    }

    private fun parseProxyBlocks(text: String): List<ProxyBlock> {
        val lines = text.replace("\uFEFF", "").lines()
        val blocks = mutableListOf<ProxyBlock>()
        var inProxies = false
        var proxiesIndent = 0
        var current = mutableListOf<String>()

        fun flush() {
            if (current.isNotEmpty()) {
                blocks += ProxyBlock(current.toList())
                current = mutableListOf()
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith('#')) continue
            val indent = line.leadingSpaces()
            if (!inProxies) {
                if (trimmed == "proxies:" || trimmed.startsWith("proxies:", ignoreCase = true)) {
                    inProxies = true
                    proxiesIndent = indent
                }
                continue
            }

            if (indent <= proxiesIndent && !trimmed.startsWith("-")) {
                flush()
                break
            }

            if (trimmed.startsWith("- ") || trimmed == "-") {
                flush()
                current += trimmed.removePrefix("-").trim()
            } else if (current.isNotEmpty()) {
                current += line
            }
        }
        flush()
        return blocks
    }

    private fun String.leadingSpaces(): Int = takeWhile { it == ' ' }.length

    private class ProxyBlock(private val lines: List<String>) {
        private val inlineFields: Map<String, String> by lazy {
            lines.firstOrNull()
                ?.trim()
                ?.takeIf { it.startsWith('{') && it.endsWith('}') }
                ?.let { parseInlineMap(it) }
                .orEmpty()
        }

        fun field(name: String): String? {
            inlineFields[name.lowercase()]?.let { return it }
            val names = if (name.equals("Host", ignoreCase = true)) listOf("Host") else listOf(name)
            for (line in lines) {
                val trimmed = line.trim().removePrefix("-").trim()
                for (candidate in names) {
                    val prefix = "$candidate:"
                    if (trimmed.startsWith(prefix, ignoreCase = true)) {
                        return trimmed.substringAfter(':').cleanYamlValue().takeIf { it.isNotBlank() }
                    }
                }
            }
            return null
        }

        fun intField(name: String): Int? = field(name)?.toIntOrNull()

        fun booleanField(name: String): Boolean? = when (field(name)?.lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> null
        }

        fun hasKey(name: String): Boolean = inlineFields.containsKey(name.lowercase()) || lines.any { line ->
            line.trim().removePrefix("-").trim().startsWith("$name:", ignoreCase = true)
        }

        private fun parseInlineMap(value: String): Map<String, String> {
            val body = value.trim().removePrefix("{").removeSuffix("}")
            return splitInlineMap(body).mapNotNull { part ->
                val key = part.substringBefore(':', "").trim().trim('"', '\'')
                val rawValue = part.substringAfter(':', "").cleanYamlValue()
                key.lowercase().takeIf { it.isNotBlank() }?.let { it to rawValue }
            }.toMap()
        }

        private fun splitInlineMap(body: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var quote: Char? = null
            var escape = false
            for (char in body) {
                when {
                    escape -> {
                        current.append(char)
                        escape = false
                    }
                    quote != null && char == '\\' -> {
                        current.append(char)
                        escape = true
                    }
                    quote != null && char == quote -> {
                        current.append(char)
                        quote = null
                    }
                    quote != null -> current.append(char)
                    char == '"' || char == '\'' -> {
                        current.append(char)
                        quote = char
                    }
                    char == '{' || char == '[' -> {
                        current.append(char)
                        depth++
                    }
                    char == '}' || char == ']' -> {
                        current.append(char)
                        depth--
                    }
                    char == ',' && depth == 0 -> {
                        parts += current.toString()
                        current.setLength(0)
                    }
                    else -> current.append(char)
                }
            }
            if (current.isNotBlank()) parts += current.toString()
            return parts
        }
    }

    private fun String.cleanYamlValue(): String = substringBefore(" #")
        .trim()
        .trim('"', '\'')
        .trim()

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private data class ParsedClashProxy(
        val name: String?,
        val transport: String,
        val label: String,
        val unsupportedTransport: Boolean,
        val endpoint: EndpointCandidate
    )
}
