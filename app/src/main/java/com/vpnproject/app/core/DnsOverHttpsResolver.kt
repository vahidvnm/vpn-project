package com.vpnproject.app.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.IDN
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

interface DohTransport {
    @Throws(IOException::class)
    fun queryA(provider: DohProvider, hostname: String, timeoutMs: Int): String
}

class HttpUrlConnectionDohTransport : DohTransport {
    override fun queryA(provider: DohProvider, hostname: String, timeoutMs: Int): String {
        val encodedName = URLEncoder.encode(hostname, "UTF-8")
        val url = URL("${provider.endpointUrl}?name=$encodedName&type=A")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/dns-json")
            setRequestProperty("User-Agent", "VPNProject-Android/0.1")
        }

        return try {
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            if (code !in 200..299) {
                throw IOException("HTTP $code from ${provider.displayName}")
            }
            body
        } finally {
            connection.disconnect()
        }
    }
}

class DnsOverHttpsResolver(
    private val transport: DohTransport = HttpUrlConnectionDohTransport(),
    private val providers: List<DohProvider> = listOf(
        DohProvider.CLOUDFLARE,
        DohProvider.GOOGLE,
        DohProvider.QUAD9
    ),
    private val cache: DnsCache = DnsCache(),
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() }
) {
    fun resolveA(hostname: String): DnsLookupResult {
        val normalized = normalizeHostname(hostname)
        if (normalized.isBlank()) {
            return DnsLookupResult(hostname, emptyList(), listOf("Hostname is empty."))
        }

        if (IpClassifier.isIpv4Literal(normalized)) {
            return literalIpv4Result(normalized)
        }

        cache.get(normalized, nowEpochMs())?.let { return it }

        val errors = mutableListOf<String>()
        val addresses = linkedMapOf<String, ResolvedAddress>()

        for (provider in providers.distinct()) {
            try {
                val body = transport.queryA(provider, normalized, timeoutMs)
                val parsed = parseJsonDnsResponse(
                    body = body,
                    provider = provider,
                    hostname = normalized,
                    nowEpochMs = nowEpochMs()
                )
                if (parsed.isEmpty()) {
                    errors += "${provider.displayName}: no public IPv4 A records returned."
                }
                for (answer in parsed) {
                    addresses.putIfAbsent(answer.ip, answer)
                }
            } catch (e: Exception) {
                errors += "${provider.displayName}: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        val result = DnsLookupResult(
            hostname = normalized,
            addresses = addresses.values.sortedWith(compareBy<ResolvedAddress> { it.provider?.ordinal ?: Int.MAX_VALUE }.thenBy { it.ip }),
            errors = errors,
            fromCache = false
        )
        if (result.addresses.isNotEmpty()) {
            cache.put(normalized, result)
        }
        return result
    }

    internal fun parseJsonDnsResponse(
        body: String,
        provider: DohProvider,
        hostname: String,
        nowEpochMs: Long
    ): List<ResolvedAddress> {
        val answerArray = extractJsonArray(body, "Answer") ?: return emptyList()
        return splitTopLevelJsonObjects(answerArray).mapNotNull { jsonObject ->
            val type = numberField(jsonObject, "type")?.toInt() ?: return@mapNotNull null
            if (type != DNS_TYPE_A) return@mapNotNull null
            val ip = stringField(jsonObject, "data") ?: return@mapNotNull null
            if (!IpClassifier.isPublicIpv4(ip)) return@mapNotNull null
            val ttlSeconds = sanitizeTtl(numberField(jsonObject, "TTL") ?: DEFAULT_TTL_SECONDS)
            ResolvedAddress(
                hostname = hostname,
                ip = ip,
                provider = provider,
                ttlSeconds = ttlSeconds,
                expiresAtEpochMs = nowEpochMs + ttlSeconds * 1000L
            )
        }.distinctBy { it.ip }
    }

    internal fun normalizeHostname(value: String): String {
        val trimmed = value.trim().trim('"', '\'').trimEnd('.')
        if (trimmed.isBlank()) return ""
        if (IpClassifier.isIpv4Literal(trimmed)) return trimmed
        return try {
            IDN.toASCII(trimmed).lowercase()
        } catch (_: IllegalArgumentException) {
            ""
        }
    }

    private fun literalIpv4Result(ip: String): DnsLookupResult {
        if (!IpClassifier.isPublicIpv4(ip)) {
            return DnsLookupResult(
                hostname = ip,
                addresses = emptyList(),
                errors = listOf("$ip is private, loopback, multicast, or otherwise reserved.")
            )
        }
        val ttl = LITERAL_TTL_SECONDS
        return DnsLookupResult(
            hostname = ip,
            addresses = listOf(
                ResolvedAddress(
                    hostname = ip,
                    ip = ip,
                    provider = null,
                    ttlSeconds = ttl,
                    expiresAtEpochMs = nowEpochMs() + ttl * 1000L
                )
            )
        )
    }

    private fun sanitizeTtl(value: Long): Long = min(max(value, MIN_TTL_SECONDS), MAX_TTL_SECONDS)

    private fun extractJsonArray(body: String, key: String): String? {
        val keyMatch = Regex("\\\"${Regex.escape(key)}\\\"\\s*:").find(body) ?: return null
        var index = keyMatch.range.last + 1
        while (index < body.length && body[index].isWhitespace()) index++
        if (index >= body.length || body[index] != '[') return null

        var depth = 0
        var inString = false
        var escaped = false
        val start = index + 1
        for (i in index until body.length) {
            val ch = body[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return body.substring(start, i)
                }
            }
        }
        return null
    }

    private fun splitTopLevelJsonObjects(arrayBody: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escaped = false
        var start = -1

        for (i in arrayBody.indices) {
            val ch = arrayBody[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }

            when (ch) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out += arrayBody.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return out
    }

    private fun stringField(jsonObject: String, fieldName: String): String? {
        val match = Regex(
            "\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"",
            RegexOption.IGNORE_CASE
        ).find(jsonObject) ?: return null
        return match.groupValues[1]
    }

    private fun numberField(jsonObject: String, fieldName: String): Long? {
        val match = Regex(
            "\\\"${Regex.escape(fieldName)}\\\"\\s*:\\s*(\\d+)",
            RegexOption.IGNORE_CASE
        ).find(jsonObject) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private companion object {
        const val DNS_TYPE_A = 1
        const val DEFAULT_TIMEOUT_MS = 3_000
        const val DEFAULT_TTL_SECONDS = 300L
        const val MIN_TTL_SECONDS = 30L
        const val MAX_TTL_SECONDS = 900L
        const val LITERAL_TTL_SECONDS = 3_600L
    }
}
