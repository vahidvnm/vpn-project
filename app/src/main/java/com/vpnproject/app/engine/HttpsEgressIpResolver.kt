package com.vpnproject.app.engine

import com.vpnproject.app.core.IpClassifier
import java.net.HttpURLConnection
import java.net.URL

class HttpsEgressIpResolver(
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val timeoutMs: Int = 4_000
) : EgressIpResolver {
    override fun fetchPublicIp(): String? {
        for (endpoint in endpoints) {
            val body = runCatching { fetch(endpoint) }.getOrNull() ?: continue
            val ip = extractIpv4(body)
            if (ip != null && IpClassifier.isPublicIpv4(ip)) return ip
        }
        return null
    }

    private fun fetch(endpoint: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/plain, application/json")
            setRequestProperty("User-Agent", "VPNProject-Android/0.1")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText().take(MAX_RESPONSE_CHARS)
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun extractIpv4(body: String): String? {
        val cloudflareTrace = body.lineSequence()
            .firstOrNull { it.startsWith("ip=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
        if (cloudflareTrace != null && IpClassifier.isPublicIpv4(cloudflareTrace)) {
            return cloudflareTrace
        }

        return IPV4_REGEX.findAll(body)
            .map { it.value }
            .firstOrNull { IpClassifier.isPublicIpv4(it) }
    }

    private companion object {
        val DEFAULT_ENDPOINTS = listOf(
            "https://1.1.1.1/cdn-cgi/trace",
            "https://api.ipify.org",
            "https://checkip.amazonaws.com",
            "https://icanhazip.com",
            "https://ifconfig.me/ip",
            "https://www.cloudflare.com/cdn-cgi/trace"
        )
        const val MAX_RESPONSE_CHARS = 2_048
        val IPV4_REGEX = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    }
}
