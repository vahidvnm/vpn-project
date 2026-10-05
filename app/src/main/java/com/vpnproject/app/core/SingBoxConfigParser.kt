package com.vpnproject.app.core

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Experimental parser for user-owned sing-box JSON configs.
 *
 * This does not execute sing-box yet. It extracts safe endpoint metadata from
 * common outbound objects so the hub can save, group, search, and quick-probe
 * sing-box profiles. Supported vless/vmess/trojan/ss outbounds can also be
 * synthesized as Xray share links for the embedded Xray runtime mapper.
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

    fun firstXrayShareLink(text: String): String? = outboundObjects(text.replace("\uFEFF", "").trim())
        .mapNotNull { objectText -> parseOutbound(objectText)?.runtimeLink }
        .firstOrNull()

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
            add("sing-box JSON import is experimental: supported vless/vmess/trojan/ss outbounds can be mapped to embedded Xray; unsupported sing-box features stay saved for grouping, search, and no-VPN diagnostics.")
            add("Detected sing-box outbounds: $labels. Secrets stay encrypted locally and are not shown in UI labels.")
            parsed.filter { it.unsupportedTransport }.take(4).forEach { outbound ->
                val safeName = V2RayLinkInspector.safeDisplayName(outbound.tag) ?: outbound.endpoint.host
                add("sing-box outbound $safeName uses transport ${outbound.transport}; endpoint probe can run, but runtime support needs a future sing-box/Xray mapper.")
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
        val transportObject = topLevelObjectField(objectText, "transport")
        val transport = normalizeTransport(transportObject
            ?.let { topLevelStringField(it, "type") })
        val tls = topLevelObjectField(objectText, "tls")
        val reality = tls?.let { topLevelObjectField(it, "reality") }
        val realityEnabled = reality != null && topLevelBooleanField(reality, "enabled") != false
        val tlsEnabled = tls != null && topLevelBooleanField(tls, "enabled") != false
        val security = when {
            realityEnabled -> "reality"
            tlsEnabled -> "tls"
            else -> "none"
        }
        val transportHeaders = transportObject?.let { topLevelObjectField(it, "headers") }
        val hostHeader = firstNonBlank(
            transportHeaders?.let { topLevelStringField(it, "Host") },
            transportHeaders?.let { topLevelStringField(it, "host") },
            transportObject?.let { topLevelStringField(it, "host") }
        )
        val path = transportObject?.let { topLevelStringField(it, "path") }
        val serviceName = firstNonBlank(
            transportObject?.let { topLevelStringField(it, "service_name") },
            transportObject?.let { topLevelStringField(it, "serviceName") }
        )
        val fingerprint = firstNonBlank(
            tls?.let { topLevelObjectField(it, "utls") }?.let { topLevelStringField(it, "fingerprint") },
            tls?.let { topLevelStringField(it, "fingerprint") }
        )
        val verifyHost = firstNonBlank(
            tls?.let { topLevelStringField(it, "server_name") },
            topLevelStringField(objectText, "server_name"),
            hostHeader,
            server.takeUnless { IpClassifier.isIpv4Literal(it) || IpClassifier.isIpv6Literal(it) }
        )
        val publicKey = reality?.let { topLevelStringField(it, "public_key") }
        val shortId = reality?.let { topLevelStringField(it, "short_id") }
        val allowInsecure = tls?.let { topLevelBooleanField(it, "insecure") } == true
        val runtimeLink = buildRuntimeLink(
            type = rawType,
            server = server,
            port = port,
            credential = firstNonBlank(topLevelStringField(objectText, "uuid"), topLevelStringField(objectText, "password")),
            method = topLevelStringField(objectText, "method"),
            alterId = topLevelIntField(objectText, "alter_id") ?: topLevelIntField(objectText, "alterId"),
            flow = topLevelStringField(objectText, "flow"),
            security = security,
            transport = transport,
            hostHeader = hostHeader,
            path = path,
            sni = verifyHost,
            fingerprint = fingerprint,
            publicKey = publicKey,
            shortId = shortId,
            serviceName = serviceName,
            allowInsecure = allowInsecure,
            name = tag
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
            unsupportedTransport = transport !in setOf("tcp", "ws", "grpc", "http", "httpupgrade", "xhttp"),
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
                    "\"ps\":${(safeName ?: "sing-box").jsonQuote()}",
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
        transport == "xhttp" -> if (security == "reality") "XHTTP/Reality" else "XHTTP"
        transport == "grpc" -> if (security == "tls") "gRPC/TLS" else "gRPC"
        transport == "ws" -> if (security == "tls") "WS/TLS" else "WebSocket"
        transport == "http" -> if (security == "tls") "H2/TLS" else "H2"
        security == "tls" -> "TLS"
        type == "shadowsocks" || type == "ss" -> "SS"
        else -> type.uppercase()
    }

    private fun normalizeTransport(raw: String?): String = when (raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }) {
        null, "tcp", "raw", "none" -> "tcp"
        "ws", "websocket" -> "ws"
        "grpc", "gun" -> "grpc"
        "http", "h2" -> "http"
        "httpupgrade", "http-upgrade", "http_upgrade" -> "httpupgrade"
        "xhttp", "splithttp", "split-http", "split_http" -> "xhttp"
        else -> raw.trim().lowercase()
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

    private fun String.unescapeJsonString(): String = replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private data class ParsedSingBoxOutbound(
        val tag: String?,
        val transport: String,
        val label: String,
        val unsupportedTransport: Boolean,
        val runtimeLink: String?,
        val endpoint: EndpointCandidate
    )
}
