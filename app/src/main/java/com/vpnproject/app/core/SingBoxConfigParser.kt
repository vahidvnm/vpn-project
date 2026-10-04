package com.vpnproject.app.core

/**
 * Experimental parser for user-owned sing-box JSON configs.
 *
 * This does not execute sing-box yet. It extracts safe endpoint metadata from
 * common outbound objects so the hub can save, group, search, and quick-probe
 * sing-box profiles while the embedded engine decision is still pending.
 */
object SingBoxConfigParser {
    private val supportedOutboundTypes = setOf("vless", "vmess", "trojan", "shadowsocks", "ss")

    fun looksLikeSingBox(text: String): Boolean {
        val normalized = text.replace("\uFEFF", "").trim()
        if (!normalized.startsWith("{") && !normalized.startsWith("[")) return false
        if (!normalized.contains("\"server\"", ignoreCase = true)) return false
        return outboundObjects(normalized).any { objectText ->
            topLevelStringField(objectText, "type")?.lowercase() in supportedOutboundTypes &&
                !topLevelStringField(objectText, "server").isNullOrBlank() &&
                topLevelPortField(objectText) != null
        }
    }

    fun parse(text: String, name: String? = null): ImportedConfig {
        val normalized = text.replace("\uFEFF", "").trim()
        val parsed = outboundObjects(normalized).mapNotNull { objectText -> parseOutbound(objectText) }
        if (parsed.isEmpty()) {
            throw ConfigParseException("No supported sing-box outbounds were found. Supported outbound types: vless, vmess, trojan, shadowsocks.")
        }

        val endpoints = parsed.map { it.endpoint }.distinct()
        val profileNames = parsed.mapNotNull { it.tag }.mapNotNull { V2RayLinkInspector.safeDisplayName(it) }
        val labels = parsed.groupingBy { it.label }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(", ") { (label, count) -> "$label $count" }
        val warnings = buildList {
            add("sing-box JSON import is experimental: endpoints are saved for grouping, search, and no-VPN diagnostics, but an embedded sing-box engine is not bundled yet.")
            add("Detected sing-box outbounds: $labels. Secrets stay encrypted locally and are not shown in UI labels.")
            parsed.filter { it.unsupportedTransport }.take(4).forEach { outbound ->
                add("sing-box outbound ${outbound.tag ?: outbound.endpoint.host} uses transport ${outbound.transport}; endpoint probe can run, but runtime support needs a future sing-box/Xray mapper.")
            }
        }

        return ImportedConfig(
            kind = ConfigKind.SING_BOX,
            name = name ?: profileNames.firstOrNull() ?: "sing-box profile",
            originalText = text,
            endpoints = endpoints,
            hasAuthUserPass = false,
            warnings = warnings.distinct()
        )
    }

    private fun parseOutbound(objectText: String): ParsedSingBoxOutbound? {
        val rawType = topLevelStringField(objectText, "type")?.lowercase() ?: return null
        if (rawType !in supportedOutboundTypes) return null
        val server = topLevelStringField(objectText, "server")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val port = topLevelPortField(objectText)?.takeIf { it in 1..65535 } ?: return null
        val tag = topLevelStringField(objectText, "tag")
        val transport = topLevelObjectField(objectText, "transport")
            ?.let { topLevelStringField(it, "type") }
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: "tcp"
        val tls = topLevelObjectField(objectText, "tls")
        val reality = tls?.let { topLevelObjectField(it, "reality") }
        val realityEnabled = reality != null && topLevelBooleanField(reality, "enabled") != false
        val tlsEnabled = tls != null && topLevelBooleanField(tls, "enabled") != false
        val security = when {
            realityEnabled -> "reality"
            tlsEnabled -> "tls"
            else -> "none"
        }
        val verifyHost = firstNonBlank(
            tls?.let { topLevelStringField(it, "server_name") },
            topLevelStringField(objectText, "server_name"),
            topLevelObjectField(objectText, "transport")
                ?.let { topLevelObjectField(it, "headers") }
                ?.let { topLevelStringField(it, "Host") },
            server.takeUnless { IpClassifier.isIpv4Literal(it) || IpClassifier.isIpv6Literal(it) }
        )
        val protocol = when (security) {
            "reality" -> VpnProtocol.SING_BOX_REALITY
            "tls" -> VpnProtocol.SING_BOX_TLS
            "none" -> VpnProtocol.SING_BOX_TCP
            else -> VpnProtocol.SING_BOX_UNKNOWN
        }
        return ParsedSingBoxOutbound(
            tag = tag,
            transport = transport,
            label = displayLabel(rawType, transport, security),
            unsupportedTransport = transport !in setOf("tcp", "ws", "grpc", "http", "httpupgrade"),
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
        type == "shadowsocks" || type == "ss" -> "SS"
        else -> type.uppercase()
    }

    private fun outboundObjects(text: String): List<String> = extractJsonObjects(text)
        .filter { objectText ->
            topLevelStringField(objectText, "type")?.lowercase() in supportedOutboundTypes &&
                !topLevelStringField(objectText, "server").isNullOrBlank()
        }
        .distinct()

    private fun extractJsonObjects(text: String): List<String> {
        val objects = mutableListOf<String>()
        val stack = ArrayDeque<Int>()
        var inString = false
        var escape = false
        text.forEachIndexed { index, char ->
            when {
                escape -> escape = false
                char == '\\' && inString -> escape = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> stack.addLast(index)
                !inString && char == '}' && stack.isNotEmpty() -> {
                    val start = stack.removeLast()
                    objects += text.substring(start, index + 1)
                }
            }
        }
        return objects
    }

    private fun topLevelStringField(json: String, fieldName: String): String? {
        val regex = Regex(
            "\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\\\"])*)\\\"",
            RegexOption.IGNORE_CASE
        )
        return regex.findAll(json)
            .firstOrNull { match -> isTopLevelField(json, match.range.first) }
            ?.groupValues
            ?.get(1)
            ?.unescapeJsonString()
    }

    private fun topLevelPortField(json: String): Int? =
        topLevelIntField(json, "server_port") ?: topLevelStringField(json, "server_port")?.toIntOrNull()

    private fun topLevelIntField(json: String, fieldName: String): Int? {
        val regex = Regex(
            "\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*(\\d+)",
            RegexOption.IGNORE_CASE
        )
        return regex.findAll(json)
            .firstOrNull { match -> isTopLevelField(json, match.range.first) }
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    private fun topLevelBooleanField(json: String, fieldName: String): Boolean? {
        val regex = Regex(
            "\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*(true|false)",
            RegexOption.IGNORE_CASE
        )
        return regex.findAll(json)
            .firstOrNull { match -> isTopLevelField(json, match.range.first) }
            ?.groupValues
            ?.get(1)
            ?.equals("true", ignoreCase = true)
    }

    private fun topLevelObjectField(json: String, fieldName: String): String? {
        val regex = Regex(
            "\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*\\{",
            RegexOption.IGNORE_CASE
        )
        val match = regex.findAll(json).firstOrNull { isTopLevelField(json, it.range.first) } ?: return null
        val objectStart = json.indexOf('{', startIndex = match.range.first).takeIf { it >= 0 } ?: return null
        val objectEnd = matchingBraceEnd(json, objectStart) ?: return null
        return json.substring(objectStart, objectEnd + 1)
    }

    private fun matchingBraceEnd(text: String, objectStart: Int): Int? {
        var depth = 0
        var inString = false
        var escape = false
        for (index in objectStart until text.length) {
            val char = text[index]
            when {
                escape -> escape = false
                char == '\\' && inString -> escape = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> depth++
                !inString && char == '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun isTopLevelField(json: String, index: Int): Boolean {
        var depth = 0
        var inString = false
        var escape = false
        for (i in 0 until index) {
            val char = json[i]
            when {
                escape -> escape = false
                char == '\\' && inString -> escape = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> depth++
                !inString && char == '}' -> depth--
            }
        }
        return depth == 1
    }

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun String.unescapeJsonString(): String = replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private data class ParsedSingBoxOutbound(
        val tag: String?,
        val transport: String,
        val label: String,
        val unsupportedTransport: Boolean,
        val endpoint: EndpointCandidate
    )
}
