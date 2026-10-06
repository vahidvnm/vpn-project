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
    private val runtimeSupportedTransports = setOf("tcp", "ws", "grpc", "http", "httpupgrade", "xhttp")

    fun looksLikeClash(text: String): Boolean {
        val normalized = text.replace("\uFEFF", "").trim()
        if (!normalized.contains("proxies:", ignoreCase = true)) return false
        return parseProxyBlocks(normalized).any { block ->
            !block.field("type").isNullOrBlank() || !block.field("server").isNullOrBlank()
        }
    }

    fun firstXrayShareLink(text: String): String? = parseProxyBlocks(text.replace("\uFEFF", ""))
        .mapNotNull { block -> parseProxy(block)?.runtimeLink }
        .firstOrNull()

    fun splitProxyTexts(text: String): List<String> = parseProxyBlocks(text.replace("\uFEFF", ""))
        .filter { block -> parseProxy(block) != null }
        .map { block -> block.toSingleProxyYaml() }

    fun parse(text: String, name: String? = null): ImportedConfig {
        val normalized = text.replace("\uFEFF", "")
        val blocks = parseProxyBlocks(normalized)
        val parsed = blocks.mapNotNull { block -> parseProxy(block) }
        if (parsed.isEmpty()) {
            throw ConfigParseException(clashImportFailure(blocks))
        }

        val labels = parsed.groupingBy { it.label }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(", ") { (label, count) -> "$label $count" }
        val names = parsed.mapNotNull { it.name }.mapNotNull { V2RayLinkInspector.safeDisplayName(it) }
        val warnings = buildList {
            add("Clash/Clash.Meta import is experimental: supported vless/vmess/trojan/ss proxies can be mapped to embedded Xray; unsupported Clash features stay saved for grouping, search, and no-VPN diagnostics.")
            add("Detected Clash proxies: $labels. Secrets stay encrypted locally and are not shown in UI labels.")
            addAll(clashDiagnosticWarnings(blocks))
            parsed.filter { it.unsupportedTransport }.take(4).forEach { proxy ->
                add("Clash proxy ${V2RayLinkInspector.safeDisplayName(proxy.name) ?: proxy.endpoint.host} uses transport ${proxy.transport}; endpoint probe can run, but Connect needs a future mapper or provider TCP-like profile.")
            }
            parsed.filter { it.missingCredential }.take(3).forEach { proxy ->
                add("Clash proxy ${V2RayLinkInspector.safeDisplayName(proxy.name) ?: proxy.endpoint.host} is missing uuid/password, so it can be saved/probed but cannot start through embedded Xray.")
            }
            parsed.filter { it.missingRealityPublicKey }.take(3).forEach { proxy ->
                add("Clash REALITY proxy ${V2RayLinkInspector.safeDisplayName(proxy.name) ?: proxy.endpoint.host} is missing public-key/pbk; ask the provider for the full REALITY link before Connect.")
            }
            parsed.filter { it.unsupportedShadowsocksPlugin }.take(3).forEach { proxy ->
                add("Clash Shadowsocks proxy ${V2RayLinkInspector.safeDisplayName(proxy.name) ?: proxy.endpoint.host} uses a plugin that this Xray mapper does not support; it is saved for diagnostics but will not be started with the plugin silently removed.")
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
        val rawNetwork = block.field("network") ?: block.field("transport") ?: block.field("net") ?: block.inferredTransport()
        val network = normalizeNetwork(rawNetwork)
        val hostHeader = firstNonBlank(
            block.field("Host"),
            block.field("host"),
            block.sectionField("ws-opts", "Host"),
            block.sectionField("ws-opts", "host"),
            block.sectionField("ws_opts", "host"),
            block.sectionField("http-opts", "Host"),
            block.sectionField("http_opts", "host"),
            block.sectionField("h2-opts", "host"),
            block.sectionField("h2_opts", "host"),
            block.sectionField("xhttp-opts", "host"),
            block.sectionField("xhttp_opts", "host"),
            block.sectionField("splithttp-opts", "host"),
            block.sectionField("split-http-opts", "host"),
            block.sectionField("split_http_opts", "host")
        )
        val path = firstNonBlank(
            block.field("path"),
            block.field("ws-path"),
            block.sectionField("ws-opts", "path"),
            block.sectionField("ws_opts", "path"),
            block.sectionField("http-opts", "path"),
            block.sectionField("http_opts", "path"),
            block.sectionField("h2-opts", "path"),
            block.sectionField("h2_opts", "path"),
            block.sectionField("httpupgrade-opts", "path"),
            block.sectionField("http-upgrade-opts", "path"),
            block.sectionField("httpupgrade_opts", "path"),
            block.sectionField("xhttp-opts", "path"),
            block.sectionField("xhttp_opts", "path"),
            block.sectionField("splithttp-opts", "path"),
            block.sectionField("split-http-opts", "path"),
            block.sectionField("split_http_opts", "path")
        )
        val serviceName = firstNonBlank(
            block.field("grpc-service-name"),
            block.field("serviceName"),
            block.field("service-name"),
            block.sectionField("grpc-opts", "grpc-service-name"),
            block.sectionField("grpc-opts", "serviceName"),
            block.sectionField("grpc-opts", "service-name"),
            block.sectionField("grpc_opts", "grpc-service-name"),
            block.sectionField("grpc_opts", "serviceName"),
            block.sectionField("grpc_opts", "service-name")
        )
        val publicKey = firstNonBlank(
            block.field("public-key"),
            block.field("public_key"),
            block.field("pbk"),
            block.sectionField("reality-opts", "public-key"),
            block.sectionField("reality-opts", "public_key"),
            block.sectionField("reality-opts", "pbk"),
            block.sectionField("reality_opts", "public-key"),
            block.sectionField("reality_opts", "public_key"),
            block.sectionField("reality_opts", "pbk")
        )
        val shortId = firstNonBlank(
            block.field("short-id"),
            block.field("short_id"),
            block.field("sid"),
            block.sectionField("reality-opts", "short-id"),
            block.sectionField("reality-opts", "short_id"),
            block.sectionField("reality-opts", "sid"),
            block.sectionField("reality_opts", "short-id"),
            block.sectionField("reality_opts", "short_id"),
            block.sectionField("reality_opts", "sid")
        )
        val fingerprint = firstNonBlank(
            block.field("client-fingerprint"),
            block.field("fingerprint"),
            block.field("fp"),
            block.sectionField("reality-opts", "fingerprint"),
            block.sectionField("reality-opts", "client-fingerprint"),
            block.sectionField("reality_opts", "fingerprint"),
            block.sectionField("reality_opts", "client-fingerprint"),
            block.sectionField("tls-opts", "client-fingerprint"),
            block.sectionField("tls_opts", "client-fingerprint")
        )
        val tlsEnabled = block.booleanField("tls") == true ||
            block.booleanField("skip-cert-verify") != null ||
            !block.field("servername").isNullOrBlank() ||
            !block.field("sni").isNullOrBlank() ||
            rawType == "trojan"
        val reality = !publicKey.isNullOrBlank() ||
            block.hasKey("reality-opts") ||
            block.hasKey("reality_opts") ||
            block.field("flow")?.contains("xtls-rprx", ignoreCase = true) == true
        val security = when {
            reality -> "reality"
            tlsEnabled -> "tls"
            else -> "none"
        }
        val verifyHost = firstNonBlank(
            block.field("servername"),
            block.field("sni"),
            block.sectionField("reality-opts", "server-name"),
            block.sectionField("reality-opts", "server_name"),
            block.sectionField("reality_opts", "server-name"),
            block.sectionField("reality_opts", "server_name"),
            hostHeader,
            server.takeUnless { IpClassifier.isIpv4Literal(it) || IpClassifier.isIpv6Literal(it) }
        )
        val credential = firstNonBlank(block.field("uuid"), block.field("password"))
        val missingCredential = credential.isNullOrBlank()
        val missingRealityPublicKey = security == "reality" && publicKey.isNullOrBlank()
        val unsupportedShadowsocksPlugin = rawType in setOf("ss", "shadowsocks") &&
            (block.hasKey("plugin") || block.hasKey("plugin-opts") || block.hasKey("plugin_opts"))
        val transportMode = when (network) {
            "grpc" -> firstNonBlank(
                block.field("grpc-mode"),
                block.field("mode")?.takeIf { it.equals("multi", ignoreCase = true) },
                block.sectionField("grpc-opts", "grpc-mode"),
                block.sectionField("grpc-opts", "mode"),
                block.sectionField("grpc_opts", "grpc-mode"),
                block.sectionField("grpc_opts", "mode")
            )?.takeIf { it.equals("multi", ignoreCase = true) }
            "xhttp" -> firstNonBlank(
                block.field("mode"),
                block.field("xhttp-mode"),
                block.sectionField("xhttp-opts", "mode"),
                block.sectionField("xhttp_opts", "mode"),
                block.sectionField("splithttp-opts", "mode"),
                block.sectionField("split-http-opts", "mode"),
                block.sectionField("split_http_opts", "mode")
            )
            else -> null
        }
        val runtimeLink = if (unsupportedShadowsocksPlugin) null else buildRuntimeLink(
            type = rawType,
            server = server,
            port = port,
            credential = credential,
            method = firstNonBlank(block.field("cipher"), block.field("method")),
            alterId = block.intField("alterId") ?: block.intField("alter-id") ?: block.intField("alterId"),
            flow = block.field("flow"),
            security = security,
            transport = network,
            hostHeader = hostHeader,
            path = path,
            sni = verifyHost,
            fingerprint = fingerprint,
            publicKey = publicKey,
            shortId = shortId,
            serviceName = serviceName,
            transportMode = transportMode,
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
            unsupportedTransport = network !in runtimeSupportedTransports,
            missingCredential = missingCredential,
            missingRealityPublicKey = missingRealityPublicKey,
            unsupportedShadowsocksPlugin = unsupportedShadowsocksPlugin,
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

    private fun clashImportFailure(blocks: List<ProxyBlock>): String {
        val details = clashDiagnosticWarnings(blocks)
        return buildString {
            append("No Xray-compatible Clash proxies were found. This build is not a full Clash engine; it can map vless/vmess/trojan/ss proxies through embedded Xray when their transport/security fields are complete.")
            if (details.isNotEmpty()) {
                append(' ')
                append(details.joinToString(" "))
            }
        }
    }

    private fun clashDiagnosticWarnings(blocks: List<ProxyBlock>): List<String> = buildList {
        if (blocks.isEmpty()) {
            add("No proxy entries were found under proxies:.")
            return@buildList
        }
        val types = blocks.mapNotNull { it.field("type")?.lowercase()?.takeIf { type -> type.isNotBlank() } }
        val unsupportedTypes = types.filter { it !in supportedProxyTypes }.distinct().sorted()
        if (unsupportedTypes.isNotEmpty()) {
            add("Unsupported Clash proxy types: ${unsupportedTypes.joinToString(", ")}. Full engine-only protocols such as hysteria/tuic/wireguard are kept diagnostics-only until a native runtime exists.")
        }
        val supportedBlocks = blocks.filter { block -> block.field("type")?.lowercase()?.let { it in supportedProxyTypes } == true }
        val missingEndpointCount = supportedBlocks.count { block ->
            block.field("server").isNullOrBlank() || block.intField("port")?.takeIf { it in 1..65535 } == null
        }
        if (missingEndpointCount > 0) {
            add("${missingEndpointCount.formatCount("supported Clash proxy is", "supported Clash proxies are")} missing server/port metadata.")
        }
        val missingCredentialCount = supportedBlocks.count { block ->
            firstNonBlank(block.field("uuid"), block.field("password")).isNullOrBlank()
        }
        if (missingCredentialCount > 0) {
            add("${missingCredentialCount.formatCount("supported Clash proxy is", "supported Clash proxies are")} missing uuid/password; secrets are not shown, but Connect needs a complete user-provided proxy.")
        }
        val pluginShadowsocksCount = supportedBlocks.count { block ->
            block.field("type")?.lowercase() in setOf("ss", "shadowsocks") &&
                (block.hasKey("plugin") || block.hasKey("plugin-opts") || block.hasKey("plugin_opts"))
        }
        if (pluginShadowsocksCount > 0) {
            add("${pluginShadowsocksCount.formatCount("Clash Shadowsocks proxy uses", "Clash Shadowsocks proxies use")} SIP003 plugin options that are not mapped by embedded Xray; Connect will not silently strip them.")
        }
        val unsupportedTransports = supportedBlocks.map { block ->
            normalizeNetwork(block.field("network") ?: block.field("transport") ?: block.field("net") ?: block.inferredTransport())
        }.filter { it !in runtimeSupportedTransports }.distinct().sorted()
        if (unsupportedTransports.isNotEmpty()) {
            add("Unsupported Clash transports for embedded Xray: ${unsupportedTransports.joinToString(", ")}. Try provider profiles using TCP/WebSocket/gRPC/H2/HTTPUpgrade/XHTTP.")
        }
        val missingRealityKeys = supportedBlocks.count { block ->
            val hasReality = block.hasKey("reality-opts") ||
                block.hasKey("reality_opts") ||
                block.field("flow")?.contains("xtls-rprx", ignoreCase = true) == true
            val publicKey = firstNonBlank(
                block.field("public-key"),
                block.field("public_key"),
                block.field("pbk"),
                block.sectionField("reality-opts", "public-key"),
                block.sectionField("reality-opts", "public_key"),
                block.sectionField("reality-opts", "pbk"),
                block.sectionField("reality_opts", "public-key"),
                block.sectionField("reality_opts", "public_key"),
                block.sectionField("reality_opts", "pbk")
            )
            hasReality && publicKey.isNullOrBlank()
        }
        if (missingRealityKeys > 0) {
            add("${missingRealityKeys.formatCount("Clash REALITY proxy is", "Clash REALITY proxies are")} missing public-key/pbk; ask the provider for the full REALITY link.")
        }
    }.distinct()

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
        transportMode: String?,
        allowInsecure: Boolean,
        name: String?
    ): String? {
        val secret = credential?.takeIf { it.isNotBlank() } ?: return null
        val safeName = V2RayLinkInspector.safeDisplayName(name)
        val shareHost = server.toShareAuthorityHost()
        val query = buildQuery(security, transport, hostHeader, path, sni, fingerprint, publicKey, shortId, serviceName, transportMode, allowInsecure, flow)
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
        transportMode: String?,
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
        transportMode?.takeIf { it.isNotBlank() }?.let { add("mode=${it.urlEncode()}") }
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
        type == "ss" || type == "shadowsocks" -> "SS"
        else -> type.uppercase()
    }

    private fun normalizeNetwork(raw: String?): String = when (raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }) {
        null, "tcp", "none" -> "tcp"
        "ws", "websocket" -> "ws"
        "grpc", "gun" -> "grpc"
        "http", "h2" -> "http"
        "httpupgrade", "http-upgrade", "http_upgrade" -> "httpupgrade"
        "xhttp", "splithttp", "split-http", "split_http" -> "xhttp"
        else -> raw.trim().lowercase()
    }

    private fun parseProxyBlocks(text: String): List<ProxyBlock> {
        val lines = text.replace("\uFEFF", "").lines()
        val blocks = mutableListOf<ProxyBlock>()
        var inProxies = false
        var proxiesIndent = 0
        var proxyItemIndent: Int? = null
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

            val isListItem = trimmed.startsWith("- ") || trimmed == "-"
            val startsProxy = isListItem && (proxyItemIndent == null || indent <= proxyItemIndent!!)
            if (startsProxy) {
                proxyItemIndent = indent
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
                        return trimmed.substringAfter(':').cleanBlockValue().takeIf { it.isNotBlank() }
                    }
                }
            }
            return null
        }

        fun sectionField(sectionName: String, fieldName: String): String? {
            inlineFields[sectionName.lowercase()]?.let { rawSection ->
                findInInlineMap(rawSection, fieldName)?.let { return it }
            }

            lines.forEachIndexed { index, line ->
                val trimmed = line.trim().removePrefix("-").trim()
                val prefix = "$sectionName:"
                if (!trimmed.startsWith(prefix, ignoreCase = true)) return@forEachIndexed
                val inlineSection = trimmed.substringAfter(':', "").cleanYamlValue()
                findInInlineMap(inlineSection, fieldName)?.let { return it }
                val sectionIndent = line.leadingSpaces()
                for (childIndex in index + 1 until lines.size) {
                    val childLine = lines[childIndex]
                    val childTrimmed = childLine.trim()
                    if (childTrimmed.isBlank() || childTrimmed.startsWith('#')) continue
                    val childIndent = childLine.leadingSpaces()
                    if (childIndent <= sectionIndent) break
                    val normalized = childTrimmed.removePrefix("-").trim()
                    if (normalized.startsWith("$fieldName:", ignoreCase = true)) {
                        val directValue = normalized.substringAfter(':').cleanBlockValue()
                        if (directValue.isNotBlank()) return directValue
                        firstNestedListValue(childIndex + 1, childIndent)?.let { return it }
                    }
                }
            }
            return null
        }

        fun inferredTransport(): String? = when {
            hasKey("ws-opts") || hasKey("ws_opts") -> "ws"
            hasKey("grpc-opts") || hasKey("grpc_opts") -> "grpc"
            hasKey("h2-opts") || hasKey("h2_opts") || hasKey("http-opts") || hasKey("http_opts") -> "http"
            hasKey("httpupgrade-opts") || hasKey("http-upgrade-opts") || hasKey("httpupgrade_opts") -> "httpupgrade"
            hasKey("xhttp-opts") || hasKey("xhttp_opts") || hasKey("splithttp-opts") || hasKey("split-http-opts") || hasKey("split_http_opts") -> "xhttp"
            else -> null
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
                val rawValue = part.substringAfter(':', "").cleanBlockValue()
                key.lowercase().takeIf { it.isNotBlank() }?.let { it to rawValue }
            }.toMap()
        }

        private fun String.cleanBlockValue(): String {
            val cleaned = cleanYamlValue()
            if (!cleaned.startsWith('[') || !cleaned.endsWith(']')) return cleaned
            return splitInlineMap(cleaned.removePrefix("[").removeSuffix("]"))
                .firstOrNull()
                ?.cleanYamlValue()
                .orEmpty()
        }

        private fun firstNestedListValue(startIndex: Int, parentIndent: Int): String? {
            for (index in startIndex until lines.size) {
                val line = lines[index]
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith('#')) continue
                if (line.leadingSpaces() <= parentIndent) break
                val normalized = trimmed.removePrefix("-").trim().cleanBlockValue()
                if (normalized.isNotBlank() && !normalized.endsWith(':')) return normalized
            }
            return null
        }

        private fun findInInlineMap(value: String, fieldName: String): String? {
            val normalized = value.trim()
            if (!normalized.startsWith('{') || !normalized.endsWith('}')) return null
            val map = parseInlineMap(normalized)
            map[fieldName.lowercase()]?.takeIf { it.isNotBlank() }?.let { return it }
            map.values.forEach { child ->
                findInInlineMap(child, fieldName)?.let { return it }
            }
            return null
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

    private fun Int.formatCount(singular: String, plural: String): String = "${this} ${if (this == 1) singular else plural}"

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
        val missingCredential: Boolean,
        val missingRealityPublicKey: Boolean,
        val unsupportedShadowsocksPlugin: Boolean,
        val runtimeLink: String?,
        val endpoint: EndpointCandidate
    )
}
