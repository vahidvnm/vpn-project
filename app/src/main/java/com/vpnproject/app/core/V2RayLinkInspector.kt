package com.vpnproject.app.core

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Extracts non-secret display metadata from V2Ray/Xray share links.
 *
 * This deliberately avoids returning UUIDs, passwords, raw URLs, or subscription
 * tokens. It is for UI labels such as Reality or HTTPUpgrade only.
 */
object V2RayLinkInspector {
    data class Descriptor(
        val scheme: String,
        val transport: String,
        val security: String,
        val shortLabel: String,
        val displayName: String? = null,
        val runtimeSupported: Boolean = true,
        val runtimeIssue: String? = null
    )

    fun inspect(text: String): Descriptor? {
        val link = V2RaySubscriptionParser.extractLinks(text).firstOrNull()
            ?: text.trim().takeIf { looksLikeShareLink(it) }
            ?: return null
        return inspectLink(link)
    }

    fun inspectLink(link: String): Descriptor? {
        val scheme = link.substringBefore("://", "").lowercase()
        if (scheme !in setOf("vless", "vmess", "trojan", "ss")) return null
        return runCatching {
            when (scheme) {
                "vmess" -> inspectVmess(link)
                "ss" -> inspectShadowsocks(link)
                else -> inspectAuthorityStyle(link, scheme)
            }
        }.getOrNull()
    }

    /** Returns a short, user-facing name without raw URL/query/credential material. */
    fun safeDisplayName(rawName: String?): String? {
        val decoded = rawName
            ?.urlDecodeOrSelf()
            ?.decodeEscapedUnicode()
            ?.replace('\uFEFF', ' ')
            ?.collapseLabelWhitespace()
            ?.trim()
            ?: return null
        if (decoded.isBlank() || decoded.isLikelySecretOrRawLink()) return null

        val normalizedSeparators = decoded
            .replace('|', '•')
            .replace('/', '•')
            .replace('\\', '•')
            .replace('~', '•')
            .replace('—', '•')
            .replace('–', '•')
        val parts = normalizedSeparators
            .split('•')
            .mapNotNull { it.cleanNameSegment().takeIf { segment -> segment.isUsefulNameSegment() } }
            .take(2)
        val candidate = parts.joinToString(" / ").ifBlank { decoded.cleanNameSegment() }
        return candidate
            .takeIf { it.isUsefulNameSegment() && !it.isLikelySecretOrRawLink() }
            ?.shortSafeLabel(56)
    }

    private fun inspectAuthorityStyle(link: String, scheme: String): Descriptor {
        val rest = link.substringAfter("://")
        val query = rest.substringBefore('#').substringAfter('?', "")
        val fragment = rest.substringAfter('#', "")
        val params = parseQuery(query)
        val transport = normalizeTransport(params["type"] ?: params["net"] ?: params["network"])
        val security = normalizeSecurity(params["security"])
        val headerType = params["headertype"] ?: params["header_type"] ?: params["header"]
        val hasRealityPublicKey = params["pbk"].isNullOrBlank().not() ||
            params["publickey"].isNullOrBlank().not()
        return descriptor(
            scheme = scheme,
            transport = transport,
            security = security,
            headerType = headerType,
            displayName = safeDisplayName(fragment),
            hasRealityPublicKey = hasRealityPublicKey
        )
    }

    private fun inspectVmess(link: String): Descriptor {
        val encoded = link.substringAfter("://").substringBefore('#').trim()
        val json = decodeBase64Text(encoded).orEmpty()
        val transport = normalizeTransport(jsonStringField(json, "net"))
        val security = normalizeSecurity(jsonStringField(json, "tls"))
        val headerType = jsonStringField(json, "type")
        val hasRealityPublicKey = !jsonStringField(json, "pbk").isNullOrBlank() ||
            !jsonStringField(json, "publicKey").isNullOrBlank()
        return descriptor(
            scheme = "vmess",
            transport = transport,
            security = security,
            headerType = headerType,
            displayName = safeDisplayName(jsonStringField(json, "ps")),
            hasRealityPublicKey = hasRealityPublicKey
        )
    }

    private fun inspectShadowsocks(link: String): Descriptor {
        val fragment = link.substringAfter('#', "")
        return descriptor(
            scheme = "ss",
            transport = "tcp",
            security = "none",
            headerType = null,
            displayName = safeDisplayName(fragment),
            hasRealityPublicKey = true
        )
    }

    private fun descriptor(
        scheme: String,
        transport: String,
        security: String,
        headerType: String?,
        displayName: String?,
        hasRealityPublicKey: Boolean
    ): Descriptor {
        val issue = runtimeIssue(transport, security, hasRealityPublicKey)
        return Descriptor(
            scheme = scheme,
            transport = transport,
            security = security.ifBlank { "none" },
            shortLabel = displayLabel(scheme, transport, security, headerType),
            displayName = displayName,
            runtimeSupported = issue == null,
            runtimeIssue = issue
        )
    }

    private fun displayLabel(scheme: String, transport: String, security: String, headerType: String?): String = when {
        security.equals("reality", ignoreCase = true) -> "Reality"
        transport == "httpupgrade" -> "HTTPUpgrade"
        transport == "tcp" && headerType.equals("http", ignoreCase = true) -> "TCP HTTP"
        transport == "grpc" -> if (security.equals("tls", true)) "gRPC/TLS" else "gRPC"
        transport == "ws" -> if (security.equals("tls", true)) "WS/TLS" else "WebSocket"
        transport == "http" -> if (security.equals("tls", true)) "H2/TLS" else "H2"
        transport == "xhttp" -> "XHTTP"
        transport == "splithttp" -> "SplitHTTP"
        transport == "kcp" -> "mKCP"
        transport == "quic" -> "QUIC"
        transport == "tcp" && security.equals("tls", true) -> "TLS"
        scheme == "trojan" -> "Trojan"
        scheme == "ss" -> "SS"
        scheme == "vmess" -> "VMess"
        scheme == "vless" -> "VLESS"
        else -> scheme.uppercase()
    }

    private fun runtimeIssue(transport: String, security: String, hasRealityPublicKey: Boolean): String? {
        if (transport !in setOf("tcp", "ws", "grpc", "http", "httpupgrade")) {
            return "Unsupported transport $transport"
        }
        if (security !in setOf("", "none", "tls", "reality")) {
            return "Unsupported security $security"
        }
        if (security == "reality" && !hasRealityPublicKey) {
            return "REALITY public key missing"
        }
        return null
    }

    private fun normalizeTransport(raw: String?): String {
        val normalized = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        return when (normalized) {
            null, "tcp", "raw", "none" -> "tcp"
            "ws", "websocket" -> "ws"
            "grpc", "gun" -> "grpc"
            "http", "h2" -> "http"
            "httpupgrade", "http-upgrade", "http_upgrade" -> "httpupgrade"
            "xhttp" -> "xhttp"
            "splithttp", "split-http", "split_http" -> "splithttp"
            else -> normalized
        }
    }

    private fun normalizeSecurity(raw: String?): String = raw
        ?.trim()
        ?.lowercase()
        ?.takeUnless { it.isBlank() || it == "none" }
        .orEmpty()

    private fun looksLikeShareLink(value: String): Boolean = listOf("vless://", "vmess://", "trojan://", "ss://")
        .any { value.startsWith(it, ignoreCase = true) }

    private fun parseQuery(queryText: String): Map<String, String> {
        if (queryText.isBlank()) return emptyMap()
        return queryText.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val key = part.substringBefore('=').urlDecodeOrSelf().lowercase()
            val value = part.substringAfter('=', "").urlDecodeOrSelf()
            key to value
        }.toMap()
    }

    private fun decodeBase64Text(value: String): String? {
        val cleaned = value.trim().replace(Regex("\\s+"), "")
        if (cleaned.isBlank()) return null
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        for (decoder in listOf(Base64.getDecoder(), Base64.getUrlDecoder())) {
            try {
                return String(decoder.decode(padded), StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                // Try next alphabet.
            }
        }
        return null
    }

    private fun jsonStringField(json: String, fieldName: String): String? {
        val match = Regex(
            "\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\\\"])*)\\\"",
            RegexOption.IGNORE_CASE
        ).find(json) ?: return null
        return match.groupValues[1]
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private fun String.urlDecodeOrSelf(): String = try {
        URLDecoder.decode(this, StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        this
    }

    private fun String.decodeEscapedUnicode(): String {
        val decoded = StringBuilder(length)
        var index = 0
        while (index < length) {
            if (this[index] == '\\' && index + 5 < length && this[index + 1] == 'u') {
                val hex = substring(index + 2, index + 6)
                if (hex.all { Character.digit(it, 16) >= 0 }) {
                    decoded.append(hex.toInt(16).toChar())
                    index += 6
                    continue
                }
            }
            decoded.append(if (this[index].isISOControl()) ' ' else this[index])
            index++
        }
        return decoded.toString()
    }

    private fun String.cleanNameSegment(): String {
        var value = replace('_', ' ')
            .replace('+', ' ')
            .collapseLabelWhitespace()
            .trim(' ', '•', '-', '·', '.', ',', ':', ';')
        for (generic in listOf("vless", "vmess", "trojan", "xray", "v2ray", "vpn", "config")) {
            value = value.removeWordIgnoreCase(generic)
        }
        return value.collapseLabelWhitespace().trim(' ', '•', '-', '·', '.', ',', ':', ';')
    }

    private fun String.removeWordIgnoreCase(word: String): String {
        var result = this
        while (true) {
            val index = result.indexOf(word, ignoreCase = true)
            if (index < 0) return result
            val before = result.getOrNull(index - 1)
            val after = result.getOrNull(index + word.length)
            val beforeBoundary = before == null || !before.isLetterOrDigit()
            val afterBoundary = after == null || !after.isLetterOrDigit()
            if (beforeBoundary && afterBoundary) {
                result = result.removeRange(index, index + word.length)
            } else {
                return result
            }
        }
    }

    private fun String.isUsefulNameSegment(): Boolean =
        isNotBlank() && any { it.isLetterOrDigit() } && !isLikelySecretOrRawLink()

    private fun String.isLikelySecretOrRawLink(): Boolean {
        val compact = trim()
        val lower = compact.lowercase()
        if (lower.contains("://")) return true
        if ((compact.contains('?') || compact.contains('&')) && compact.contains('=')) return true
        if (listOf("uuid=", "password=", "passwd=", "token=", "pbk=", "privatekey=").any { lower.contains(it) }) return true
        val alnum = compact.count { it.isLetterOrDigit() }
        val separators = compact.count { it == '-' }
        if (separators >= 4 && alnum >= 32 && compact.all { it.isLetterOrDigit() || it == '-' }) return true
        if (compact.length >= 24 && compact.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '=' }) return true
        return false
    }

    private fun String.collapseLabelWhitespace(): String {
        val compact = StringBuilder(length)
        var previousWasSpace = false
        for (char in this) {
            val normalized = if (char.isWhitespace() || char.isISOControl()) ' ' else char
            if (normalized == ' ') {
                if (!previousWasSpace) compact.append(' ')
                previousWasSpace = true
            } else {
                compact.append(normalized)
                previousWasSpace = false
            }
        }
        return compact.toString().trim()
    }

    private fun String.shortSafeLabel(maxLength: Int): String =
        if (length <= maxLength) this else take(maxLength - 1).trimEnd() + "…"
}
