package com.vpnproject.app.core

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Parses common V2Ray/Xray-style share links from user-owned configs.
 *
 * Supported for endpoint discovery/probing only in this milestone:
 * - vless://...
 * - vmess://base64-json
 * - trojan://...
 * - ss://... (accepted because many Xray/V2Ray clients import it)
 *
 * We intentionally keep credentials opaque and only extract routing metadata
 * needed to diagnose whether a server/fronting endpoint is reachable.
 */
object V2RayConfigParser {
    private val supportedSchemes = setOf("vless", "vmess", "trojan", "ss")

    fun looksLikeV2Ray(text: String): Boolean = candidateLines(text).any { line ->
        supportedSchemes.any { scheme -> line.startsWith("$scheme://", ignoreCase = true) }
    } || decodeWholeSubscription(text).orEmpty().lineSequence().any { line ->
        supportedSchemes.any { scheme -> line.trim().startsWith("$scheme://", ignoreCase = true) }
    }

    fun parse(text: String, name: String? = null): ImportedConfig {
        val directLines = candidateLines(text)
        val lines = directLines.ifEmpty {
            decodeWholeSubscription(text)?.let { candidateLines(it) }.orEmpty()
        }

        val endpoints = mutableListOf<EndpointCandidate>()
        val warnings = mutableListOf<String>()
        val profileNames = mutableListOf<String>()

        for (line in lines) {
            when (val parsed = parseLink(line)) {
                is ParsedV2RayLink.Success -> {
                    endpoints += parsed.endpoint
                    parsed.name?.takeIf { it.isNotBlank() }?.let { profileNames += it }
                    warnings += parsed.warnings
                }
                is ParsedV2RayLink.Failure -> warnings += parsed.warning
                null -> Unit
            }
        }

        if (endpoints.isEmpty()) {
            throw ConfigParseException("No supported V2Ray/Xray share links were found. Supported schemes: vless, vmess, trojan, ss.")
        }

        warnings += "V2Ray/Xray links are parsed for endpoint diagnostics only; an internal Xray/V2Ray engine is not integrated yet. Keep using your trusted external client for real connections."
        warnings += "Do not blindly IP-pin CDN/REALITY/V2Ray links: SNI, Host, ALPN, path, and fingerprint settings must be preserved by the eventual engine."

        return ImportedConfig(
            kind = ConfigKind.V2RAY,
            name = name ?: profileNames.firstOrNull(),
            originalText = text,
            endpoints = endpoints.distinct(),
            hasAuthUserPass = false,
            warnings = warnings.distinct()
        )
    }

    private fun parseLink(rawLine: String): ParsedV2RayLink? {
        val line = rawLine.trim().trim('"', '\'')
        if (line.isBlank()) return null
        val scheme = line.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme !in supportedSchemes) return null
        return try {
            when (scheme) {
                "vless", "trojan" -> parseAuthorityStyleLink(line, scheme)
                "vmess" -> parseVmessLink(line)
                "ss" -> parseShadowsocksLink(line)
                else -> null
            }
        } catch (e: Exception) {
            ParsedV2RayLink.Failure("Could not parse $scheme link: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun parseAuthorityStyleLink(line: String, scheme: String): ParsedV2RayLink.Success {
        val rest = line.substringAfter("://")
        val (withoutFragment, fragment) = splitOnce(rest, '#')
        val (authorityAndPath, queryText) = splitOnce(withoutFragment, '?')
        val authority = authorityAndPath.substringBefore('/')
        val hostPort = authority.substringAfterLast('@', authority)
        val (host, port) = parseHostPort(hostPort, defaultPort = defaultPortForQuery(queryText))
        val params = parseQuery(queryText)
        val security = params["security"]?.lowercase().orEmpty()
        val protocol = when (security) {
            "tls" -> VpnProtocol.V2RAY_TLS
            "reality" -> VpnProtocol.V2RAY_REALITY
            "none", "" -> VpnProtocol.V2RAY_TCP
            else -> VpnProtocol.V2RAY_UNKNOWN
        }
        val verifyHost = firstNonBlank(
            params["sni"],
            params["peer"],
            params["host"]?.substringBefore(','),
            host.takeUnless { IpClassifier.isIpv4Literal(it) || IpClassifier.isIpv6Literal(it) }
        )
        val warnings = buildList {
            if (protocol == VpnProtocol.V2RAY_REALITY) {
                add("$scheme REALITY endpoint ${host}:$port can only be TCP-probed for now; REALITY validation needs the eventual Xray engine.")
            }
            if (params["type"].equals("grpc", ignoreCase = true)) {
                add("gRPC transport detected for ${host}:$port; the current probe checks reachability, not full gRPC/V2Ray login.")
            }
        }
        return ParsedV2RayLink.Success(
            endpoint = EndpointCandidate(
                host = host,
                port = port,
                protocol = protocol,
                verifyHost = verifyHost,
                source = CandidateSource.IMPORTED_CONFIG
            ),
            name = fragment.urlDecodeOrSelf().takeIf { it.isNotBlank() },
            warnings = warnings
        )
    }

    private fun parseVmessLink(line: String): ParsedV2RayLink.Success {
        val encoded = line.substringAfter("://").substringBefore('#').trim()
        val json = decodeBase64Text(encoded)
            ?: throw ConfigParseException("vmess payload is not valid base64 JSON.")
        val host = jsonStringField(json, "add")
            ?: throw ConfigParseException("vmess payload has no add host field.")
        val port = jsonStringField(json, "port")?.toIntOrNull()
            ?: jsonNumberField(json, "port")?.toInt()
            ?: defaultPortForSecurity(jsonStringField(json, "tls"))
        val tls = jsonStringField(json, "tls")?.lowercase().orEmpty()
        val protocol = when (tls) {
            "tls" -> VpnProtocol.V2RAY_TLS
            "reality" -> VpnProtocol.V2RAY_REALITY
            "", "none" -> VpnProtocol.V2RAY_TCP
            else -> VpnProtocol.V2RAY_UNKNOWN
        }
        val verifyHost = firstNonBlank(
            jsonStringField(json, "sni"),
            jsonStringField(json, "host")?.substringBefore(','),
            host.takeUnless { IpClassifier.isIpv4Literal(it) || IpClassifier.isIpv6Literal(it) }
        )
        val warnings = buildList {
            jsonStringField(json, "net")?.let { net ->
                if (net.equals("grpc", ignoreCase = true)) {
                    add("VMess gRPC transport detected for ${host}:$port; the current probe checks reachability, not full gRPC/V2Ray login.")
                }
            }
        }
        return ParsedV2RayLink.Success(
            endpoint = EndpointCandidate(
                host = host,
                port = port,
                protocol = protocol,
                verifyHost = verifyHost,
                source = CandidateSource.IMPORTED_CONFIG
            ),
            name = jsonStringField(json, "ps"),
            warnings = warnings
        )
    }

    private fun parseShadowsocksLink(line: String): ParsedV2RayLink.Success {
        val rest = line.substringAfter("://")
        val (withoutFragment, fragment) = splitOnce(rest, '#')
        val (withoutQuery, _) = splitOnce(withoutFragment, '?')
        val decodedAuthority = if ('@' in withoutQuery) {
            val userInfo = withoutQuery.substringBefore('@')
            val hostPort = withoutQuery.substringAfter('@')
            val decodedUserInfo = decodeBase64Text(userInfo) ?: userInfo.urlDecodeOrSelf()
            "$decodedUserInfo@$hostPort"
        } else {
            decodeBase64Text(withoutQuery) ?: withoutQuery.urlDecodeOrSelf()
        }
        val hostPort = decodedAuthority.substringAfterLast('@', decodedAuthority)
        val (host, port) = parseHostPort(hostPort, defaultPort = 8388)
        return ParsedV2RayLink.Success(
            endpoint = EndpointCandidate(
                host = host,
                port = port,
                protocol = VpnProtocol.V2RAY_TCP,
                verifyHost = null,
                source = CandidateSource.IMPORTED_CONFIG
            ),
            name = fragment.urlDecodeOrSelf().takeIf { it.isNotBlank() },
            warnings = listOf("Shadowsocks link detected; it is accepted for Xray-compatible diagnostics, but full support requires an internal engine decision.")
        )
    }

    private fun candidateLines(text: String): List<String> = text
        .replace("\uFEFF", "")
        .lineSequence()
        .flatMap { line -> line.trim().splitToSequence(Regex("\\s+")) }
        .map { it.trim() }
        .filter { token ->
            token.isNotBlank() &&
                !token.startsWith("#") &&
                supportedSchemes.any { scheme -> token.startsWith("$scheme://", ignoreCase = true) }
        }
        .toList()

    private fun decodeWholeSubscription(text: String): String? = decodeBase64Text(
        text.replace("\uFEFF", "").trim().lineSequence().filterNot { it.trim().startsWith("#") }.joinToString("")
    )

    private fun parseQuery(queryText: String): Map<String, String> {
        if (queryText.isBlank()) return emptyMap()
        return queryText.split('&')
            .mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val key = part.substringBefore('=').urlDecodeOrSelf().lowercase()
                val value = part.substringAfter('=', "").urlDecodeOrSelf()
                key to value
            }
            .toMap()
    }

    private fun parseHostPort(value: String, defaultPort: Int): Pair<String, Int> {
        val trimmed = value.trim()
        if (trimmed.startsWith('[')) {
            val end = trimmed.indexOf(']')
            require(end > 0) { "Invalid IPv6 host/port: $value" }
            val host = trimmed.substring(1, end)
            val port = trimmed.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            require(port in 1..65535) { "Invalid port $port" }
            return host to port
        }
        val host = trimmed.substringBeforeLast(':', trimmed).trim()
        val port = trimmed.substringAfterLast(':', "").toIntOrNull() ?: defaultPort
        require(host.isNotBlank()) { "Host is empty." }
        require(port in 1..65535) { "Invalid port $port" }
        return host.trim('"', '\'') to port
    }

    private fun defaultPortForQuery(queryText: String): Int = defaultPortForSecurity(parseQuery(queryText)["security"])

    private fun defaultPortForSecurity(security: String?): Int = when (security?.lowercase()) {
        "tls", "reality" -> 443
        else -> 80
    }

    private fun splitOnce(value: String, delimiter: Char): Pair<String, String> {
        val index = value.indexOf(delimiter)
        return if (index < 0) value to "" else value.substring(0, index) to value.substring(index + 1)
    }

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun decodeBase64Text(value: String): String? {
        val cleaned = value.trim().replace(Regex("\\s+"), "")
        if (cleaned.isBlank()) return null
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        val decoders = listOf(Base64.getDecoder(), Base64.getUrlDecoder())
        for (decoder in decoders) {
            try {
                return String(decoder.decode(padded), StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                // Try the next alphabet.
            }
        }
        return null
    }

    private fun String.urlDecodeOrSelf(): String = try {
        URLDecoder.decode(this, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        this
    }

    private fun jsonStringField(json: String, fieldName: String): String? {
        val match = Regex(
            "\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\\\"])*)\\\"",
            RegexOption.IGNORE_CASE
        ).find(json) ?: return null
        return unescapeJsonString(match.groupValues[1])
    }

    private fun jsonNumberField(json: String, fieldName: String): Long? {
        val match = Regex(
            "\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*(\\d+)",
            RegexOption.IGNORE_CASE
        ).find(json) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun unescapeJsonString(value: String): String = value
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")

    private sealed class ParsedV2RayLink {
        data class Success(
            val endpoint: EndpointCandidate,
            val name: String?,
            val warnings: List<String> = emptyList()
        ) : ParsedV2RayLink()

        data class Failure(val warning: String) : ParsedV2RayLink()
    }
}
