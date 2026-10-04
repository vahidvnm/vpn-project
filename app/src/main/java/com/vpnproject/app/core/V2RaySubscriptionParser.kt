package com.vpnproject.app.core

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Extracts individual Xray/V2Ray-compatible share links from a user-provided
 * subscription response. Subscription URLs and node links can contain account
 * tokens, so callers must avoid logging or rendering the raw values.
 */
object V2RaySubscriptionParser {
    private val supportedSchemes = setOf("vless", "vmess", "trojan", "ss")
    private val shareLinkPattern = Regex("(?i)(?:vless|vmess|trojan|ss)://[^\\s\"'<>]+")

    fun extractLinks(text: String): List<String> {
        val normalized = text.replace("\uFEFF", "").trim()
        if (normalized.isBlank()) return emptyList()

        val direct = candidateLinks(normalized)
        val decoded = decodeWholeSubscription(normalized)?.let { candidateLinks(it) }.orEmpty()
        return (direct + decoded).distinctBy { it.trim() }
    }

    private fun candidateLinks(text: String): List<String> = shareLinkPattern
        .findAll(text)
        .map { match -> cleanCandidate(match.value) }
        .filter { token -> token.isNotBlank() && supportedSchemes.any { scheme -> token.startsWith("$scheme://", ignoreCase = true) } }
        .toList()

    private fun cleanCandidate(value: String): String {
        var cleaned = value.trim().trim('"', '\'', '[', ']', '(', ')', '{', '}')
        while (cleaned.lastOrNull() in listOf(',', ';', '.', ']')) {
            cleaned = cleaned.dropLast(1).trimEnd()
        }
        return cleaned
    }

    private fun decodeWholeSubscription(text: String): String? {
        val compact = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .joinToString("")
        return decodeBase64Text(compact)
    }

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
}
