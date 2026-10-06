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
        var currentUrl = URL(endpoint)
        if (!currentUrl.protocol.equals("https", ignoreCase = true)) {
            throw IllegalArgumentException("Egress verification only permits HTTPS URLs.")
        }

        for (redirectCount in 0..MAX_REDIRECTS) {
            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                setRequestProperty("Accept", "text/plain, application/json")
                setRequestProperty("User-Agent", "VPNProject-Android/0.1")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    if (redirectCount == MAX_REDIRECTS) error("Too many HTTPS redirects.")
                    val location = connection.getHeaderField("Location")
                        ?: error("HTTPS endpoint returned a redirect without a Location.")
                    val nextUrl = URL(currentUrl, location)
                    if (!nextUrl.protocol.equals("https", ignoreCase = true)) {
                        error("HTTPS egress verification blocked a redirect to cleartext HTTP.")
                    }
                    currentUrl = nextUrl
                    continue
                }
                if (code !in 200..299) error("HTTP $code")
                return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val output = StringBuilder()
                    val buffer = CharArray(512)
                    while (output.length < MAX_RESPONSE_CHARS) {
                        val remaining = MAX_RESPONSE_CHARS - output.length
                        val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read < 0) break
                        if (read == 0) continue
                        output.append(buffer, 0, read)
                    }
                    output.toString()
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Could not fetch public egress IP after HTTPS redirects.")
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
        const val MAX_REDIRECTS = 3
        const val MAX_RESPONSE_CHARS = 2_048
        val DEFAULT_ENDPOINTS = listOf(
            "https://1.1.1.1/cdn-cgi/trace",
            "https://api.ipify.org",
            "https://checkip.amazonaws.com",
            "https://icanhazip.com",
            "https://ifconfig.me/ip",
            "https://www.cloudflare.com/cdn-cgi/trace"
        )
        val IPV4_REGEX = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    }
}
