package com.vpnproject.app.engine

import com.vpnproject.app.core.ClashConfigParser
import com.vpnproject.app.core.ConfigKind
import com.vpnproject.app.core.ConfigParseException
import com.vpnproject.app.core.ImportedConfig
import com.vpnproject.app.core.SingBoxConfigParser
import com.vpnproject.app.core.V2RaySubscriptionParser
import com.vpnproject.app.core.VpnRoutingInputParser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

object V2RayRuntimeConfigBuilder {
    fun build(
        config: ImportedConfig,
        dnsServers: List<String> = DEFAULT_DNS_SERVERS,
        sniffingEnabled: Boolean = true,
        muxEnabled: Boolean = false,
        muxConcurrency: Int = DEFAULT_MUX_CONCURRENCY,
        logLevel: String = DEFAULT_LOG_LEVEL,
        localHttpProxyPort: Int? = null
    ): V2RayRuntimeConfig {
        val prepared = prepareProfile(config)
        return V2RayRuntimeConfig(
            configJson = buildXrayConfig(
                profile = prepared.profile,
                includeTunInbound = true,
                dnsServers = dnsServers,
                sniffingEnabled = sniffingEnabled,
                muxEnabled = muxEnabled,
                muxConcurrency = muxConcurrency,
                logLevel = logLevel,
                localHttpProxyPort = localHttpProxyPort
            ),
            profileName = prepared.profile.name ?: config.name ?: "${prepared.source.lowercase(java.util.Locale.US)}-import",
            localHttpProxyPort = localHttpProxyPort?.takeIf { it in MIN_LOCAL_HTTP_PROXY_PORT..MAX_LOCAL_HTTP_PROXY_PORT },
            note = "Prepared ${prepared.source} ${prepared.profile.scheme.uppercase()} ${prepared.profile.address}:${prepared.profile.port} via ${prepared.profile.network}/${prepared.profile.security.ifBlank { "none" }} for embedded Xray."
        )
    }

    fun buildDelayProbe(
        config: ImportedConfig,
        dnsServers: List<String> = DEFAULT_DNS_SERVERS,
        muxEnabled: Boolean = false,
        muxConcurrency: Int = DEFAULT_MUX_CONCURRENCY,
        logLevel: String = DEFAULT_LOG_LEVEL
    ): V2RayRuntimeConfig {
        val prepared = prepareProfile(config)
        return V2RayRuntimeConfig(
            configJson = buildXrayConfig(
                profile = prepared.profile,
                includeTunInbound = false,
                dnsServers = dnsServers,
                sniffingEnabled = false,
                muxEnabled = muxEnabled,
                muxConcurrency = muxConcurrency,
                logLevel = logLevel,
                localHttpProxyPort = null
            ),
            profileName = prepared.profile.name ?: config.name ?: "${prepared.source.lowercase(java.util.Locale.US)}-real-delay",
            note = "Prepared ${prepared.source} ${prepared.profile.scheme.uppercase()} ${prepared.profile.address}:${prepared.profile.port} for Xray core real-delay probe without Android VPN/TUN."
        )
    }

    private fun prepareProfile(config: ImportedConfig): PreparedProfile {
        val source = when (config.kind) {
            ConfigKind.V2RAY -> "V2Ray/Xray"
            ConfigKind.SING_BOX -> "sing-box"
            ConfigKind.CLASH -> "Clash"
            else -> throw ConfigParseException("Only V2Ray/Xray, sing-box, or Clash-compatible configs can be prepared for the Xray engine.")
        }
        val link = when (config.kind) {
            ConfigKind.V2RAY -> firstShareLink(config.originalText)
            ConfigKind.SING_BOX -> SingBoxConfigParser.firstXrayShareLink(config.originalText)
            ConfigKind.CLASH -> ClashConfigParser.firstXrayShareLink(config.originalText)
            else -> null
        } ?: throw ConfigParseException("No Xray-compatible outbound found in this $source config. It stays saved for diagnostics, but Connect needs an additional runtime mapper or embedded engine for its unsupported features.")
        val profile = parseLink(link)
        validateRuntimeSupport(profile)
        return PreparedProfile(source, profile)
    }

    internal fun firstShareLink(text: String): String? = V2RaySubscriptionParser.extractLinks(text).firstOrNull()

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
        val defaultPort = if (queryText.contains("security=reality", true) || queryText.contains("security=tls", true)) 443 else 80
        val (host, port) = parseHostPort(hostPort, defaultPort = defaultPort)
        val params = parseQuery(queryText)
        val rawNetwork = params.param("type", "net", "network")
        val network = normalizeNetwork(rawNetwork)
        val headerType = params.param("headerType", "header_type", "header")
            ?: rawNetwork?.takeIf { network == "tcp" && it.isTcpHeaderAlias() }
        val security = params.param("security")
            ?.lowercase(java.util.Locale.US)
            ?.takeUnless { it == "none" }
            .orEmpty()
        return V2RayProfile(
            scheme = scheme,
            address = host,
            port = port,
            idOrPassword = userInfo.urlDecodeOrSelf(),
            method = if (scheme == "vless") params.param("encryption") ?: "none" else null,
            flow = params.param("flow"),
            security = security,
            network = network,
            headerType = headerType,
            hostHeader = params.param("host"),
            path = params.param("path"),
            sni = params.param("sni", "serverName", "servername"),
            fingerprint = params.param("fp", "fingerprint"),
            alpn = params.param("alpn"),
            publicKey = params.param("pbk", "publickey", "publicKey"),
            shortId = params["sid"] ?: params["shortid"],
            spiderX = params.param("spx", "spiderx", "spiderX"),
            serviceName = params.param("serviceName", "service", "service_name") ?: params.param("path")?.takeIf { network == "grpc" },
            authority = params.param("authority") ?: params.param("host")?.firstHostHeader(),
            allowInsecure = params.boolParam("allowInsecure", "allowinsecure", "insecure", "skip-cert-verify"),
            grpcMultiMode = params.boolParam("multiMode", "multimode") || params.param("mode")?.equals("multi", ignoreCase = true) == true,
            xhttpMode = params.param("mode", "xhttpMode", "xhttp_mode")?.takeIf { network == "xhttp" },
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
        val security = jsonStringField(json, "tls")
            ?.lowercase(java.util.Locale.US)
            ?.takeUnless { it == "none" }
            .orEmpty()
        val network = normalizeNetwork(jsonStringField(json, "net"))
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
            network = network,
            headerType = jsonStringField(json, "type"),
            hostHeader = jsonStringField(json, "host"),
            path = jsonStringField(json, "path"),
            sni = jsonStringField(json, "sni"),
            fingerprint = jsonStringField(json, "fp") ?: jsonStringField(json, "fingerprint"),
            alpn = jsonStringField(json, "alpn"),
            serviceName = jsonStringField(json, "path")?.takeIf { network == "grpc" },
            authority = jsonStringField(json, "host")?.firstHostHeader(),
            allowInsecure = jsonBooleanLikeField(json, "allowInsecure") || jsonBooleanLikeField(json, "skip-cert-verify"),
            grpcMultiMode = jsonStringField(json, "mode")?.equals("multi", ignoreCase = true) == true,
            xhttpMode = jsonStringField(json, "mode")?.takeIf { network == "xhttp" },
            name = jsonStringField(json, "ps")
        )
    }

    private fun parseShadowsocks(link: String): V2RayProfile {
        val rest = link.substringAfter("://")
        val (withoutFragment, fragment) = splitOnce(rest, '#')
        val (withoutQuery, queryText) = splitOnce(withoutFragment, '?')
        val query = parseQuery(queryText)
        if (query.containsKey("plugin") || query.containsKey("plugin_opts") || query.containsKey("plugin-opts")) {
            throw ConfigParseException("Shadowsocks SIP003 plugins are not supported by embedded Xray in this build. The plugin will not be silently ignored; import a non-plugin Shadowsocks link or use an external compatible client.")
        }
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

    private fun validateRuntimeSupport(profile: V2RayProfile) {
        if (profile.network !in SUPPORTED_NETWORKS) {
            throw ConfigParseException(unsupportedTransportMessage(profile.network))
        }

        if (profile.security !in SUPPORTED_SECURITY) {
            throw ConfigParseException(unsupportedSecurityMessage(profile.security))
        }

        if (profile.security == "reality" && profile.publicKey.isNullOrBlank()) {
            throw ConfigParseException("REALITY link is missing public key (pbk/publicKey), so embedded Xray cannot start it safely. Ask your provider for the full REALITY link; UUIDs/passwords are not shown here.")
        }
    }

    private fun unsupportedTransportMessage(network: String): String = when (network) {
        "kcp", "mkcp", "quic" ->
            "Unsupported V2Ray/Xray transport $network. It is UDP-based and unreliable on many Iran networks; this build starts TCP/WebSocket/gRPC/H2/HTTPUpgrade/XHTTP through embedded Xray. Import stays saved for no-VPN diagnostics."
        "http3", "h3" ->
            "Unsupported V2Ray/Xray transport $network. HTTP/3 is QUIC/UDP-based; use an Xray TCP, WS, gRPC, H2, HTTPUpgrade, or XHTTP profile for Connect."
        else ->
            "Unsupported V2Ray/Xray transport $network. Embedded Xray start currently supports TCP, WebSocket, gRPC, H2, HTTPUpgrade, XHTTP, TLS, and REALITY. Import stays saved, but Connect needs a mapper update for this transport."
    }

    private fun unsupportedSecurityMessage(security: String): String =
        "Unsupported V2Ray/Xray security $security. Embedded Xray start currently supports none, TLS, and REALITY; unsupported security fields are kept encrypted and not logged."

    private fun buildXrayConfig(
        profile: V2RayProfile,
        includeTunInbound: Boolean,
        dnsServers: List<String>,
        sniffingEnabled: Boolean,
        muxEnabled: Boolean,
        muxConcurrency: Int,
        logLevel: String,
        localHttpProxyPort: Int?
    ): String {
        val outbound = buildOutbound(profile, muxEnabled, muxConcurrency)
        val safeDnsServers = VpnRoutingInputParser.parseDnsServers(dnsServers.joinToString("\n")).servers
            .ifEmpty { DEFAULT_DNS_SERVERS }
        val dnsJson = safeDnsServers.joinToString(prefix = "[", postfix = "]") { it.json() }
        val lanBypassJson = XrayRoutePolicy.LAN_BYPASS_CIDRS
            .joinToString(prefix = "[", postfix = "]") { it.json() }
        val sniffingJson = if (sniffingEnabled) {
            "\"sniffing\": { \"enabled\": true, \"destOverride\": [\"http\", \"tls\", \"quic\"] }"
        } else {
            "\"sniffing\": { \"enabled\": false }"
        }
        val inboundBlocks = mutableListOf<String>()
        if (includeTunInbound) {
            inboundBlocks += """
                {
                  "tag": "tun",
                  "protocol": "tun",
                  "settings": { "name": "xray0", "MTU": 1500, "userLevel": 8 },
                  $sniffingJson
                }
            """.trimIndent()
        }
        localHttpProxyPort
            ?.takeIf { it in MIN_LOCAL_HTTP_PROXY_PORT..MAX_LOCAL_HTTP_PROXY_PORT }
            ?.let { port ->
                inboundBlocks += """
                    {
                      "tag": "loopback-http",
                      "listen": "127.0.0.1",
                      "port": $port,
                      "protocol": "http",
                      "settings": { "allowTransparent": false },
                      "sniffing": { "enabled": false }
                    }
                """.trimIndent()
            }
        val inbounds = if (inboundBlocks.isEmpty()) {
            "[]"
        } else {
            inboundBlocks.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n")
        }
        val safeLogLevel = safeLogLevel(logLevel)
        return """
            {
              "stats": {},
              "log": { "loglevel": ${safeLogLevel.json()} },
              "policy": {
                "levels": { "8": { "handshake": 4, "connIdle": 300, "uplinkOnly": 1, "downlinkOnly": 1 } },
                "system": { "statsOutboundUplink": true, "statsOutboundDownlink": true }
              },
              "inbounds": $inbounds,
              "outbounds": [
                $outbound,
                { "tag": "direct", "protocol": "freedom", "streamSettings": { "sockopt": { "domainStrategy": "UseIP" } } },
                { "tag": "block", "protocol": "blackhole" }
              ],
              "routing": {
                "domainStrategy": "AsIs",
                "rules": [
                  { "type": "field", "ip": $lanBypassJson, "outboundTag": "direct" }
                ]
              },
              "dns": { "servers": $dnsJson }
            }
        """.trimIndent()
    }

    private fun buildOutbound(profile: V2RayProfile, muxEnabled: Boolean, muxConcurrency: Int): String {
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
              "mux": ${muxSettings(muxEnabled, muxConcurrency)}
            }
        """.trimIndent()
    }

    private fun muxSettings(enabled: Boolean, concurrency: Int): String = if (enabled) {
        "{ \"enabled\": true, \"concurrency\": ${concurrency.coerceIn(MIN_MUX_CONCURRENCY, MAX_MUX_CONCURRENCY)} }"
    } else {
        "{ \"enabled\": false }"
    }

    private fun safeLogLevel(logLevel: String): String = when (logLevel.lowercase(java.util.Locale.US)) {
        "debug", "info", "warning", "error", "none" -> logLevel.lowercase(java.util.Locale.US)
        else -> DEFAULT_LOG_LEVEL
    }

    private fun buildStreamSettings(profile: V2RayProfile): String {
        val parts = mutableListOf("\"network\": ${profile.network.json()}")
        if (profile.security.isNotBlank()) parts += "\"security\": ${profile.security.json()}"
        networkSettings(profile)?.let { parts += it }
        securitySettings(profile)?.let { parts += it }
        return "{ ${parts.joinToString(", ")} }"
    }

    private fun networkSettings(profile: V2RayProfile): String? = when (profile.network) {
        "ws" -> {
            val host = profile.hostHeader?.firstHostHeader()
            val headers = host?.takeIf { it.isNotBlank() }?.let { ", \"headers\": { \"Host\": ${it.json()} }" }.orEmpty()
            "\"wsSettings\": { \"path\": ${normalizedPath(profile.path).json()}$headers }"
        }
        "grpc" -> {
            val authority = firstNonBlank(profile.authority, profile.hostHeader?.firstHostHeader(), profile.sni)
                ?.let { ", \"authority\": ${it.json()}" }
                .orEmpty()
            "\"grpcSettings\": { \"serviceName\": ${grpcServiceName(profile.serviceName ?: profile.path).json()}, \"multiMode\": ${profile.grpcMultiMode}$authority }"
        }
        "http" -> {
            val hosts = profile.hostHeader.orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
            "\"httpSettings\": { \"host\": [${hosts.joinToString(",") { it.json() }}], \"path\": ${normalizedPath(profile.path).json()} }"
        }
        "httpupgrade" -> httpUpgradeSettings(profile)
        "xhttp" -> xhttpSettings(profile)
        "tcp" -> tcpSettings(profile)
        else -> null
    }

    private fun xhttpSettings(profile: V2RayProfile): String {
        val fields = mutableListOf("\"path\": ${normalizedPath(profile.path).json()}")
        firstNonBlank(profile.hostHeader?.firstHostHeader(), profile.authority, profile.sni)?.let { host ->
            fields += "\"host\": ${host.json()}"
        }
        profile.xhttpMode?.trim()?.takeIf { it.isNotBlank() }?.let { mode ->
            fields += "\"mode\": ${mode.json()}"
        }
        return "\"xhttpSettings\": { ${fields.joinToString(", ")} }"
    }

    private fun httpUpgradeSettings(profile: V2RayProfile): String {
        val fields = mutableListOf("\"path\": ${normalizedPath(profile.path).json()}")
        firstNonBlank(profile.hostHeader?.firstHostHeader(), profile.authority, profile.sni)?.let { host ->
            fields += "\"host\": ${host.json()}"
        }
        return "\"httpupgradeSettings\": { ${fields.joinToString(", ")} }"
    }

    private fun tcpSettings(profile: V2RayProfile): String? {
        val headerType = profile.headerType?.lowercase(java.util.Locale.US)?.takeIf { it.isNotBlank() && it != "tcp" && it != "none" && it != "raw" }
            ?: return null
        if (headerType != "http") {
            return "\"tcpSettings\": { \"header\": { \"type\": ${headerType.json()} } }"
        }

        val requestFields = mutableListOf(
            "\"method\": \"GET\"",
            "\"path\": [${normalizedPath(profile.path).json()}]"
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
            firstNonBlank(profile.sni, profile.hostHeader?.firstHostHeader(), profile.address)?.let { fields += "\"serverName\": ${it.json()}" }
            profile.fingerprint?.takeIf { it.isNotBlank() }?.let { fields += "\"fingerprint\": ${it.json()}" }
            alpnArray(profile.alpn)?.let { fields += "\"alpn\": $it" }
            fields += "\"allowInsecure\": ${profile.allowInsecure}"
            "\"tlsSettings\": { ${fields.joinToString(", ")} }"
        }
        "reality" -> {
            val fields = mutableListOf<String>()
            firstNonBlank(profile.sni, profile.hostHeader?.firstHostHeader(), profile.address)?.let { fields += "\"serverName\": ${it.json()}" }
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
            val key = part.substringBefore('=').urlDecodeOrSelf().lowercase(java.util.Locale.US)
            val value = part.substringAfter('=', "").urlDecodeOrSelf()
            key to value
        }.toMap()
    }

    private fun Map<String, String>.param(vararg names: String): String? = names
        .firstNotNullOfOrNull { name -> this[name.lowercase(java.util.Locale.US)]?.takeIf { it.isNotBlank() } }

    private fun Map<String, String>.boolParam(vararg names: String): Boolean = names.any { name ->
        val value = this[name.lowercase(java.util.Locale.US)]?.trim()?.lowercase(java.util.Locale.US) ?: return@any false
        value == "1" || value == "true" || value == "yes" || value == "y" || value == "allow"
    }

    private fun normalizeNetwork(raw: String?): String = when (raw?.trim()?.lowercase(java.util.Locale.US)?.takeIf { it.isNotBlank() }) {
        null, "tcp", "raw", "none" -> "tcp"
        "ws", "websocket" -> "ws"
        "grpc", "gun" -> "grpc"
        "http", "h2" -> "http"
        "httpupgrade", "http-upgrade", "http_upgrade" -> "httpupgrade"
        "xhttp", "splithttp", "split-http", "split_http" -> "xhttp"
        "mkcp" -> "kcp"
        else -> raw.trim().lowercase(java.util.Locale.US)
    }

    private fun String.isTcpHeaderAlias(): Boolean = trim().lowercase(java.util.Locale.US).let { value ->
        value.isNotBlank() && value != "tcp" && value != "raw" && value != "none"
    }

    private fun normalizedPath(value: String?, defaultValue: String = "/"): String {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: defaultValue
        return if (trimmed.startsWith("/") || trimmed.startsWith("?")) trimmed else "/$trimmed"
    }

    private fun grpcServiceName(value: String?): String = value
        ?.trim()
        ?.removePrefix("/")
        ?.takeIf { it.isNotBlank() }
        ?: ""

    private fun String.firstHostHeader(): String? = split(',', ';')
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }

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

    private fun jsonBooleanLikeField(json: String, fieldName: String): Boolean {
        jsonStringField(json, fieldName)?.trim()?.lowercase(java.util.Locale.US)?.let { value ->
            return value == "1" || value == "true" || value == "yes" || value == "allow"
        }
        val match = Regex(
            "\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*(true|false|1|0)",
            RegexOption.IGNORE_CASE
        ).find(json) ?: return false
        return match.groupValues[1].equals("true", ignoreCase = true) || match.groupValues[1] == "1"
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

    private const val DEFAULT_LOG_LEVEL = "warning"
    private val SUPPORTED_NETWORKS = setOf("tcp", "ws", "grpc", "http", "httpupgrade", "xhttp")
    private val SUPPORTED_SECURITY = setOf("", "tls", "reality")
    private const val DEFAULT_MUX_CONCURRENCY = 8
    private const val MIN_MUX_CONCURRENCY = 1
    private const val MAX_MUX_CONCURRENCY = 32
    private const val MIN_LOCAL_HTTP_PROXY_PORT = 1024
    private const val MAX_LOCAL_HTTP_PROXY_PORT = 65535
    private val DEFAULT_DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")
}

data class V2RayRuntimeConfig(
    val configJson: String,
    val profileName: String,
    val note: String,
    val localHttpProxyPort: Int? = null
)

private data class PreparedProfile(
    val source: String,
    val profile: V2RayProfile
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
    val allowInsecure: Boolean = false,
    val grpcMultiMode: Boolean = false,
    val xhttpMode: String? = null,
    val name: String? = null
)
