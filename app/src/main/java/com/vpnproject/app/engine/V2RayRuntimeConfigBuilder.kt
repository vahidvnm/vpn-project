package com.vpnproject.app.engine

import com.vpnproject.app.core.ConfigKind
import com.vpnproject.app.core.ConfigParseException
import com.vpnproject.app.core.ImportedConfig
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

object V2RayRuntimeConfigBuilder {
    fun build(config: ImportedConfig): V2RayRuntimeConfig {
        require(config.kind == ConfigKind.V2RAY) { "Only V2Ray/Xray configs can be prepared for the Xray engine." }
        val link = firstShareLink(config.originalText)
            ?: throw ConfigParseException("No V2Ray/Xray share link found for runtime start.")
        val profile = parseLink(link)
        return V2RayRuntimeConfig(
            configJson = buildXrayConfig(profile),
            profileName = profile.name ?: config.name ?: "v2ray-import",
            note = "Prepared ${profile.scheme.uppercase()} ${profile.address}:${profile.port} via ${profile.network}/${profile.security.ifBlank { "none" }} for embedded Xray."
        )
    }

    internal fun firstShareLink(text: String): String? {
        val direct = text.replace("\uFEFF", "")
            .lineSequence()
            .flatMap { line -> line.trim().splitToSequence(Regex("\\s+")) }
            .firstOrNull { token -> isShareLink(token) }
        if (direct != null) return direct.trim()

        val decoded = decodeBase64Text(text.replace("\uFEFF", "").trim().lineSequence().joinToString("")) ?: return null
        return decoded.lineSequence()
            .flatMap { line -> line.trim().splitToSequence(Regex("\\s+")) }
            .firstOrNull { token -> isShareLink(token) }
            ?.trim()
    }

    private fun isShareLink(token: String): Boolean =
        token.startsWith("vless://", ignoreCase = true) ||
            token.startsWith("vmess://", ignoreCase = true) ||
            token.startsWith("trojan://", ignoreCase = true) ||
            token.startsWith("ss://", ignoreCase = true)

    private fun parseLink(link: String): V2RayProfile {
        val scheme = link.substringBefore("://").lowercase()
        return when (scheme) {
            "vless" -> parseAuthorityStyle(link, scheme)
            "trojan" -> parseAuthorityStyle(link, scheme)
            "vmess" -> parseVmess(link)
            "ss" -> parseShadowsocks(link)
            else -> throw ConfigParseException("Unsupported V2Ray/Xray link scheme: $scheme")
        }
    }

    private fun parseAuthorityStyle(link: String, scheme: String): V2RayProfile {
        val rest = link.substringAfter("://")
        val (withoutFragment, fragment) = splitOnce(rest, '#')
        val (authorityAndPath, queryText) = splitOnce(withoutFragment, '?')
        val authority = authorityAndPath.substringBefore('/')
        val userInfo = authority.substringBeforeLast('@', "")
        val hostPort = authority.substringAfterLast('@', authority)
        val (host, port) = parseHostPort(hostPort, defaultPort = if (queryText.contains("security=reality", true) || queryText.contains("security=tls", true)) 443 else 80)
        val params = parseQuery(queryText)
        val security = params["security"]?.lowercase()?.takeUnless { it == "none" }.orEmpty()
        val network = params["type"]?.lowercase()?.takeUnless { it == "http" } ?: params["type"]?.lowercase() ?: "tcp"
        return V2RayProfile(
            scheme = scheme,
            address = host,
            port = port,
            idOrPassword = userInfo.urlDecodeOrSelf(),
            method = if (scheme == "vless") params["encryption"]?.takeIf { it.isNotBlank() } ?: "none" else null,
            flow = params["flow"],
            security = security,
            network = when (network) {
                "h2" -> "http"
                else -> network
            },
            headerType = params["headerType"] ?: params["header_type"] ?: params["type"]?.takeIf { network == "tcp" && it != "tcp" },
            hostHeader = params["host"],
            path = params["path"],
            sni = params["sni"],
            fingerprint = params["fp"],
            alpn = params["alpn"],
            publicKey = params["pbk"] ?: params["publickey"] ?: params["publicKey"],
            shortId = params["sid"] ?: params["shortid"] ?: params["shortId"],
            spiderX = params["spx"] ?: params["spiderx"] ?: params["spiderX"],
            serviceName = params["serviceName"] ?: params["serviceName".lowercase()] ?: params["path"],
            authority = params["authority"] ?: params["host"],
            name = fragment.urlDecodeOrSelf().takeIf { it.isNotBlank() }
        )
    }

    private fun parseVmess(link: String): V2RayProfile {
        val encoded = link.substringAfter("://").substringBefore('#').trim()
        val json = decodeBase64Text(encoded) ?: throw ConfigParseException("VMess payload is not valid base64 JSON.")
        val host = jsonStringField(json, "add") ?: throw ConfigParseException("VMess payload has no add host field.")
        val port = jsonStringField(json, "port")?.toIntOrNull()
            ?: jsonNumberField(json, "port")?.toInt()
            ?: 443
        val security = jsonStringField(json, "tls")?.lowercase()?.takeUnless { it == "none" }.orEmpty()
        return V2RayProfile(
            scheme = "vmess",
            address = host,
            port = port,
            idOrPassword = jsonStringField(json, "id") ?: throw ConfigParseException("VMess payload has no id field."),
            method = jsonStringField(json, "scy")?.takeIf { it.isNotBlank() } ?: "auto",
            alterId = jsonStringField(json, "aid")?.toIntOrNull()
                ?: jsonNumberField(json, "aid")?.toInt()
                ?: 0,
            security = security,
            network = jsonStringField(json, "net")?.lowercase()?.takeIf { it.isNotBlank() } ?: "tcp",
            headerType = jsonStringField(json, "type"),
            hostHeader = jsonStringField(json, "host"),
            path = jsonStringField(json, "path"),
            sni = jsonStringField(json, "sni"),
            fingerprint = jsonStringField(json, "fp"),
            alpn = jsonStringField(json, "alpn"),
            serviceName = jsonStringField(json, "path"),
            authority = jsonStringField(json, "host"),
            name = jsonStringField(json, "ps")
        )
    }

    private fun parseShadowsocks(link: String): V2RayProfile {
        val rest = link.substringAfter("://")
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
        val userInfo = decodedAuthority.substringBeforeLast('@', "")
        val hostPort = decodedAuthority.substringAfterLast('@', decodedAuthority)
        val (method, password) = splitOnce(userInfo, ':')
        val (host, port) = parseHostPort(hostPort, defaultPort = 8388)
        return V2RayProfile(
            scheme = "shadowsocks",
            address = host,
            port = port,
            idOrPassword = password,
            method = method.ifBlank { "aes-128-gcm" },
            security = "",
            network = "tcp",
            name = fragment.urlDecodeOrSelf().takeIf { it.isNotBlank() }
        )
    }

    private fun buildXrayConfig(profile: V2RayProfile): String {
        val outbound = buildOutbound(profile)
        return """
            {
              "stats": {},
              "log": { "loglevel": "warning" },
              "policy": {
                "levels": { "8": { "handshake": 4, "connIdle": 300, "uplinkOnly": 1, "downlinkOnly": 1 } },
                "system": { "statsOutboundUplink": true, "statsOutboundDownlink": true }
              },
              "inbounds": [
                {
                  "tag": "tun",
                  "protocol": "tun",
                  "settings": { "name": "xray0", "MTU": 1500, "userLevel": 8 },
                  "sniffing": { "enabled": true, "destOverride": ["http", "tls", "quic"] }
                }
              ],
              "outbounds": [
                $outbound,
                { "tag": "direct", "protocol": "freedom", "streamSettings": { "sockopt": { "domainStrategy": "UseIP" } } },
                { "tag": "block", "protocol": "blackhole" }
              ],
              "routing": {
                "domainStrategy": "AsIs",
                "rules": [
                  { "type": "field", "ip": ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8"], "outboundTag": "direct" }
                ]
              },
              "dns": { "servers": ["1.1.1.1", "8.8.8.8", "localhost"] }
            }
        """.trimIndent()
    }

    private fun buildOutbound(profile: V2RayProfile): String {
        val protocol = when (profile.scheme) {
            "ss" -> "shadowsocks"
            else -> profile.scheme
        }
        val settings = when (protocol) {
            "vless" -> {
                val flow = profile.flow?.takeIf { it.isNotBlank() }?.let { ", \"flow\": ${it.json()}" }.orEmpty()
                """
                    "settings": {
                      "vnext": [{
                        "address": ${profile.address.json()},
                        "port": ${profile.port},
                        "users": [{
                          "id": ${profile.idOrPassword.json()},
                          "encryption": ${(profile.method ?: "none").json()},
                          "level": 8$flow
                        }]
                      }]
                    }
                """.trimIndent()
            }
            "vmess" -> """
                "settings": {
                  "vnext": [{
                    "address": ${profile.address.json()},
                    "port": ${profile.port},
                    "users": [{
                      "id": ${profile.idOrPassword.json()},
                      "alterId": ${profile.alterId},
                      "security": ${(profile.method ?: "auto").json()},
                      "level": 8
                    }]
                  }]
                }
            """.trimIndent()
            "trojan" -> {
                val flow = profile.flow?.takeIf { it.isNotBlank() }?.let { ", \"flow\": ${it.json()}" }.orEmpty()
                """
                    "settings": {
                      "servers": [{
                        "address": ${profile.address.json()},
                        "port": ${profile.port},
                        "password": ${profile.idOrPassword.json()},
                        "level": 8$flow
                      }]
                    }
                """.trimIndent()
            }
            "shadowsocks" -> """
                "settings": {
                  "servers": [{
                    "address": ${profile.address.json()},
                    "port": ${profile.port},
                    "method": ${(profile.method ?: "aes-128-gcm").json()},
                    "password": ${profile.idOrPassword.json()},
                    "level": 8
                  }]
                }
            """.trimIndent()
            else -> throw ConfigParseException("Unsupported outbound protocol $protocol")
        }
        return """
            {
              "tag": "proxy",
              "protocol": ${protocol.json()},
              $settings,
              "streamSettings": ${buildStreamSettings(profile)},
              "mux": { "enabled": false }
            }
        """.trimIndent()
    }

    private fun buildStreamSettings(profile: V2RayProfile): String {
        val parts = mutableListOf("\"network\": ${profile.network.json()}")
        if (profile.security.isNotBlank()) parts += "\"security\": ${profile.security.json()}"
        networkSettings(profile)?.let { parts += it }
        securitySettings(profile)?.let { parts += it }
        return "{ ${parts.joinToString(", ")} }"
    }

    private fun networkSettings(profile: V2RayProfile): String? = when (profile.network) {
        "ws", "websocket" -> {
            val headers = profile.hostHeader?.takeIf { it.isNotBlank() }?.let { ", \"headers\": { \"Host\": ${it.json()} }" }.orEmpty()
            "\"wsSettings\": { \"path\": ${(profile.path ?: "/").json()}$headers }"
        }
        "grpc" -> {
            val authority = profile.authority?.takeIf { it.isNotBlank() }?.let { ", \"authority\": ${it.json()}" }.orEmpty()
            "\"grpcSettings\": { \"serviceName\": ${profile.serviceName.orEmpty().json()}, \"multiMode\": false$authority }"
        }
        "http", "h2" -> {
            val hosts = profile.hostHeader.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
            "\"httpSettings\": { \"host\": [${hosts.joinToString(",") { it.json() }}], \"path\": ${(profile.path ?: "/").json()} }"
        }
        "httpupgrade" -> httpUpgradeSettings(profile)
        "tcp", "raw" -> tcpSettings(profile)
        else -> null
    }

    private fun httpUpgradeSettings(profile: V2RayProfile): String {
        val fields = mutableListOf("\"path\": ${(profile.path ?: "/").ifBlank { "/" }.json()}")
        firstNonBlank(profile.hostHeader, profile.authority, profile.sni)?.let { host ->
            fields += "\"host\": ${host.json()}"
        }
        return "\"httpupgradeSettings\": { ${fields.joinToString(", ")} }"
    }

    private fun tcpSettings(profile: V2RayProfile): String? {
        val headerType = profile.headerType?.lowercase()?.takeIf { it.isNotBlank() && it != "tcp" && it != "none" }
            ?: return null
        if (headerType != "http") {
            return "\"tcpSettings\": { \"header\": { \"type\": ${headerType.json()} } }"
        }

        val requestFields = mutableListOf(
            "\"method\": \"GET\"",
            "\"path\": [${(profile.path ?: "/").ifBlank { "/" }.json()}]"
        )
        val hosts = firstNonBlank(profile.hostHeader, profile.authority, profile.sni)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (hosts.isNotEmpty()) {
            requestFields += "\"headers\": { \"Host\": [${hosts.joinToString(",") { it.json() }}] }"
        }
        return "\"tcpSettings\": { \"header\": { \"type\": \"http\", \"request\": { ${requestFields.joinToString(", ")} }, \"response\": {} } } }"
    }

    private fun securitySettings(profile: V2RayProfile): String? = when (profile.security) {
        "tls" -> {
            val fields = mutableListOf<String>()
            (profile.sni ?: profile.hostHeader ?: profile.address).takeIf { it.isNotBlank() }?.let { fields += "\"serverName\": ${it.json()}" }
            profile.fingerprint?.takeIf { it.isNotBlank() }?.let { fields += "\"fingerprint\": ${it.json()}" }
            alpnArray(profile.alpn)?.let { fields += "\"alpn\": $it" }
            fields += "\"allowInsecure\": false"
            "\"tlsSettings\": { ${fields.joinToString(", ")} }"
        }
        "reality" -> {
            val fields = mutableListOf<String>()
            (profile.sni ?: profile.address).takeIf { it.isNotBlank() }?.let { fields += "\"serverName\": ${it.json()}" }
            fields += "\"fingerprint\": ${(profile.fingerprint ?: "chrome").json()}"
            profile.publicKey?.takeIf { it.isNotBlank() }?.let { fields += "\"publicKey\": ${it.json()}" }
            profile.shortId?.let { fields += "\"shortId\": ${it.json()}" }
            profile.spiderX?.let { fields += "\"spiderX\": ${it.json()}" }
            "\"realitySettings\": { ${fields.joinToString(", ")} }"
        }
        else -> null
    }

    private fun alpnArray(value: String?): String? {
        val items = value?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        if (items.isEmpty()) return null
        return "[${items.joinToString(",") { it.json() }}]"
    }

    private fun parseQuery(queryText: String): Map<String, String> {
        if (queryText.isBlank()) return emptyMap()
        return queryText.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val key = part.substringBefore('=').urlDecodeOrSelf()
            val value = part.substringAfter('=', "").urlDecodeOrSelf()
            key to value
        }.toMap()
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
        val host = trimmed.substringBeforeLast(':', trimmed).trim('"', '\'')
        val port = trimmed.substringAfterLast(':', "").toIntOrNull() ?: defaultPort
        require(host.isNotBlank()) { "Host is empty." }
        require(port in 1..65535) { "Invalid port $port" }
        return host to port
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
        for (decoder in listOf(Base64.getDecoder(), Base64.getUrlDecoder())) {
            try {
                return String(decoder.decode(padded), StandardCharsets.UTF_8)
            } catch (_: IllegalArgumentException) {
                // Try next alphabet.
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

    private fun String.json(): String = buildString {
        append('"')
        for (ch in this@json) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
}

data class V2RayRuntimeConfig(
    val configJson: String,
    val profileName: String,
    val note: String
)

private data class V2RayProfile(
    val scheme: String,
    val address: String,
    val port: Int,
    val idOrPassword: String,
    val method: String? = null,
    val alterId: Int = 0,
    val flow: String? = null,
    val security: String,
    val network: String,
    val headerType: String? = null,
    val hostHeader: String? = null,
    val path: String? = null,
    val sni: String? = null,
    val fingerprint: String? = null,
    val alpn: String? = null,
    val publicKey: String? = null,
    val shortId: String? = null,
    val spiderX: String? = null,
    val serviceName: String? = null,
    val authority: String? = null,
    val name: String? = null
)
