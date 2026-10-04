package com.vpnproject.app.core

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Extracts non-secret display metadata from V2Ray/Xray share links.
 *
 * This deliberately avoids returning UUIDs, passwords, raw URLs, or subscription
 * tokens. It is for UI labels such as REALITY or HTTPUpgrade only.
 */
object V2RayLinkInspector {
    data class Descriptor(
        val scheme: String,
        val transport: String,
        val security: String,
        val shortLabel: String
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
                "ss" -> Descriptor(scheme = "ss", transport = "tcp", security = "none", shortLabel = "SS")
                else -> inspectAuthorityStyle(link, scheme)
            }
        }.getOrNull()
    }

    private fun inspectAuthorityStyle(link: String, scheme: String): Descriptor {
        val query = link.substringAfter("://").substringBefore('#').substringAfter('?', "")
        val params = parseQuery(query)
        val transport = normalizeTransport(params["type"] ?: params["net"] ?: params["network"])
        val security = params["security"]?.lowercase()?.takeUnless { it == "none" }.orEmpty()
        val headerType = params["headertype"] ?: params["header_type"] ?: params["header"]
        return Descriptor(
            scheme = scheme,
            transport = transport,
            security = security.ifBlank { "none" },
            shortLabel = displayLabel(scheme, transport, security, headerType)
        )
    }

    private fun inspectVmess(link: String): Descriptor {
        val encoded = link.substringAfter("://").substringBefore('#').trim()
        val json = decodeBase64Text(encoded).orEmpty()
        val transport = normalizeTransport(jsonStringField(json, "net"))
        val security = jsonStringField(json, "tls")?.lowercase()?.takeUnless { it == "none" }.orEmpty()
        val headerType = jsonStringField(json, "type")
        return Descriptor(
            scheme = "vmess",
            transport = transport,
            security = security.ifBlank { "none" },
            shortLabel = displayLabel("vmess", transport, security, headerType)
        )
    }

    private fun displayLabel(scheme: String, transport: String, security: String, headerType: String?): String = when {
        security.equals("reality", ignoreCase = true) -> "Reality"
        transport == "httpupgrade" -> "HTTPUpgrade"
        transport == "tcp" && headerType.equals("http", ignoreCase = true) -> "TCP HTTP"
        transport == "grpc" -> if (security.equals("tls", true)) "gRPC/TLS" else "gRPC"
        transport == "ws" -> if (security.equals("tls", true)) "WS/TLS" else "WebSocket"
        transport == "http" -> if (security.equals("tls", true)) "H2/TLS" else "H2"
        transport == "tcp" && security.equals("tls", true) -> "TLS"
        scheme == "trojan" -> "Trojan"
        scheme == "ss" -> "SS"
        scheme == "vmess" -> "VMess"
        scheme == "vless" -> "VLESS"
        else -> scheme.uppercase()
    }

    private fun normalizeTransport(raw: String?): String {
        val normalized = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        return when (normalized) {
            null, "tcp", "raw", "none" -> "tcp"
            "ws", "websocket" -> "ws"
            "grpc", "gun" -> "grpc"
            "http", "h2" -> "http"
            "httpupgrade", "http-upgrade", "http_upgrade" -> "httpupgrade"
            else -> normalized
        }
    }

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
}
