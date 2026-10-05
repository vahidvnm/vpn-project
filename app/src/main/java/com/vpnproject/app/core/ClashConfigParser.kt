package com.vpnproject.app.core

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

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

    fun firstXrayShareLink(text: String): String? = parseProxyBlocks(text.replace("\uFEFF", ""))
        .mapNotNull { block -> parseProxy(block)?.runtimeLink }
        .firstOrNull()

    fun splitProxyTexts(text: String): List<String> = parseProxyBlocks(text.replace("\uFEFF", ""))
        .filter { block -> parseProxy(block) != null }
        .map { block -> block.toSingleProxyYaml() }

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
            add("Clash/Clash.Meta import is experimental: supported vless/vmess/trojan/ss proxies can be mapped to embedded Xray; unsupported Clash features stay saved for grouping, search, and no-VPN diagnostics.")
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
        val runtimeLink = buildRuntimeLink(
            type = rawType,
            server = server,
            port = port,
            credential = firstNonBlank(block.field("uuid"), block.field("password")),
            method = firstNonBlank(block.field("cipher"), block.field("method")),
            alterId = block.intField("alterId") ?: block.intField("alter-id") ?: block.intField("alterId"),
            flow = block.field("flow"),
            security = security,
            transport = network,
            hostHeader = firstNonBlank(block.field("Host"), block.field("host")),
            path = firstNonBlank(block.field("path"), block.field("ws-path")),
            sni = verifyHost,
            fingerprint = firstNonBlank(block.field("client-fingerprint"), block.field("fingerprint"), block.field("fp")),
            publicKey = firstNonBlank(block.field("public-key"), block.field("public_key"), block.field("pbk")),
            shortId = firstNonBlank(block.field("short-id"), block.field("short_id"), block.field("sid")),
            serviceName = firstNonBlank(block.field("grpc-service-name"), block.field("serviceName"), block.field("service-name")),
            allowInsecure = block.booleanField("skip-cert-verify") == true,
            name = block.field("name")
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
            runtimeLink = runtimeLink,
            endpoint = EndpointCandidate(
                host = server,
                port = port,
                protocol = protocol,
                verifyHost = verifyHost,
                source = CandidateSource.IMPORTED_CONFIG
            )
        )
    }

    private fun buildRuntimeLink(
        type: String,
        server: String,
        port: Int,
        credential: String?,
        method: String?,
        alterId: Int?,
        flow: String?,
        security: String,
        transport: String,
        hostHeader: String?,
        path: String?,
        sni: String?,
        fingerprint: String?,
        publicKey: String?,
        shortId: String?,
        serviceName: String?,
        allowInsecure: Boolean,
        name: String?
    ): String? {
        val secret = credential?.takeIf { it.isNotBlank() } ?: return null
        val safeName = V2RayLinkInspector.safeDisplayName(name)
        val shareHost = server.toShareAuthorityHost()
        val query = buildQuery(security, transport, hostHeader, path, sni, fingerprint, publicKey, shortId, serviceName, allowInsecure, flow)
        val fragment = safeName?.takeIf { it.isNotBlank() }?.let { "#${it.urlEncode()}" }.orEmpty()
        return when (type) {
            "vless" -> "vless://${secret.urlEncode()}@$shareHost:$port?$query$fragment"
            "trojan" -> "trojan://${secret.urlEncode()}@$shareHost:$port?$query$fragment"
            "vmess" -> {
                val vmessJson = listOf(
                    "\"v\":\"2\"",
                    "\"ps\":${(safeName ?: "Clash").jsonQuote()}",
                    "\"add\":${server.jsonQuote()}",
                    "\"port\":${port.toString().jsonQuote()}",
                    "\"id\":${secret.jsonQuote()}",
                    "\"aid\":${(alterId ?: 0).toString().jsonQuote()}",
                    "\"scy\":${(method ?: "auto").jsonQuote()}",
                    "\"net\":${transport.jsonQuote()}",
                    "\"type\":${"none".jsonQuote()}",
                    "\"host\":${hostHeader.orEmpty().jsonQuote()}",
                    "\"path\":${(serviceName ?: path).orEmpty().jsonQuote()}",
                    "\"tls\":${if (security == "none") "".jsonQuote() else security.jsonQuote()}",
                    "\"sni\":${sni.orEmpty().jsonQuote()}"
                ).joinToString(",", prefix = "{", postfix = "}")
                "vmess://${Base64.getEncoder().encodeToString(vmessJson.toByteArray(StandardCharsets.UTF_8))}"
            }
            "shadowsocks", "ss" -> {
                val userInfo = "${method ?: "aes-128-gcm"}:$secret"
                val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(userInfo.toByteArray(StandardCharsets.UTF_8))
                "ss://$encoded@$shareHost:$port$fragment"
            }
            else -> null
        }
    }

    private fun buildQuery(
        security: String,
        transport: String,
        hostHeader: String?,
        path: String?,
        sni: String?,
        fingerprint: String?,
        publicKey: String?,
        shortId: String?,
        serviceName: String?,
        allowInsecure: Boolean,
        flow: String?
    ): String = buildList {
        add("security=${security.urlEncode()}")
        add("type=${transport.urlEncode()}")
        hostHeader?.takeIf { it.isNotBlank() }?.let { add("host=${it.urlEncode()}") }
        path?.takeIf { it.isNotBlank() }?.let { add("path=${it.urlEncode()}") }
        sni?.takeIf { it.isNotBlank() }?.let { add("sni=${it.urlEncode()}") }
        fingerprint?.takeIf { it.isNotBlank() }?.let { add("fp=${it.urlEncode()}") }
        publicKey?.takeIf { it.isNotBlank() }?.let { add("pbk=${it.urlEncode()}") }
        shortId?.takeIf { it.isNotBlank() }?.let { add("sid=${it.urlEncode()}") }
        serviceName?.takeIf { it.isNotBlank() }?.let { add("serviceName=${it.urlEncode()}") }
        if (allowInsecure) add("allowInsecure=1")
        flow?.takeIf { it.isNotBlank() }?.let { add("flow=${it.urlEncode()}") }
    }.joinToString("&")

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

        fun toSingleProxyYaml(): String = buildString {
            append("proxies:\n")
            val first = lines.firstOrNull()?.trim().orEmpty()
            append("- ").append(first).append('\n')
            lines.drop(1).forEach { line ->
                val normalized = line.trimEnd()
                if (normalized.isBlank()) return@forEach
                if (normalized.startsWith(" ")) {
                    append(normalized).append('\n')
                } else {
                    append("  ").append(normalized.trimStart()).append('\n')
                }
            }
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

    private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private fun String.toShareAuthorityHost(): String = when {
        startsWith("[") && endsWith("]") -> this
        IpClassifier.isIpv6Literal(this) -> "[$this]"
        else -> this
    }

    private fun String.jsonQuote(): String = buildString {
        append('\"')
        for (ch in this@jsonQuote) {
            when (ch) {
                '\\' -> append("\\\\")
                '\"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('\"')
    }

    private data class ParsedClashProxy(
        val name: String?,
        val transport: String,
        val label: String,
        val unsupportedTransport: Boolean,
        val runtimeLink: String?,
        val endpoint: EndpointCandidate
    )
}
