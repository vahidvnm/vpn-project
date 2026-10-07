package com.vpnproject.app.core

import java.nio.charset.StandardCharsets
import java.util.Base64

/** Text/file representations for explicit, user-initiated config sharing. */
data class ConfigSharePayload(
    val label: String,
    val fileName: String,
    val mimeType: String,
    val content: String
)

data class ConfigShareEntry(
    val name: String,
    val rawConfig: String
)

object ConfigShareFormatter {
    fun singleConfig(entry: ConfigShareEntry): ConfigSharePayload? {
        val text = entry.rawConfig.trim()
        if (text.isBlank()) return null
        val kind = runCatching { ConfigImporter.parse(text, entry.name).kind }.getOrNull()
        val extensionAndMime = when (kind) {
            ConfigKind.OPENVPN -> "ovpn" to "application/x-openvpn-profile"
            ConfigKind.WIREGUARD -> "conf" to "text/plain"
            ConfigKind.SING_BOX -> "json" to "application/json"
            ConfigKind.CLASH -> "yaml" to "application/yaml"
            else -> "txt" to "text/plain"
        }
        return ConfigSharePayload(
            label = "Original config",
            fileName = fileName(entry.name, extensionAndMime.first),
            mimeType = extensionAndMime.second,
            content = entry.rawConfig
        )
    }

    fun textBundle(entries: List<ConfigShareEntry>, bundleName: String): ConfigSharePayload? {
        val usable = entries.filter { it.rawConfig.isNotBlank() }
        if (usable.isEmpty()) return null
        val content = usable.mapIndexed { index, entry ->
            val safeName = entry.name.replace(Regex("[\\r\\n]+"), " ").trim().take(80).ifBlank { "Config ${index + 1}" }
            "# ${index + 1}. $safeName\n${entry.rawConfig.trim()}"
        }.joinToString("\n\n")
        return ConfigSharePayload(
            label = "Plain-text config bundle (${usable.size})",
            fileName = fileName(bundleName, "txt"),
            mimeType = "text/plain",
            content = content
        )
    }

    /** Returns a standard base64 Xray/V2Ray subscription only for one-link-per-entry groups. */
    fun v2RaySubscription(entries: List<ConfigShareEntry>, subscriptionName: String): ConfigSharePayload? {
        if (entries.isEmpty()) return null
        val links = mutableListOf<String>()
        for (entry in entries) {
            val text = entry.rawConfig.trim()
            if (text.isBlank()) return null
            val link = V2RaySubscriptionParser.extractLinks(text).singleOrNull() ?: return null
            links += link
        }
        val encoded = Base64.getEncoder().encodeToString(links.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
        return ConfigSharePayload(
            label = "V2Ray/Xray base64 subscription (${links.size})",
            fileName = fileName(subscriptionName, "sub"),
            mimeType = "text/plain",
            content = encoded
        )
    }

    fun fileName(displayName: String, extension: String): String {
        val base = displayName
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .trim('.', ' ')
            .take(64)
            .ifBlank { "vpn-config" }
        val normalizedExtension = extension.trim().trimStart('.').filter { it.isLetterOrDigit() }.ifBlank { "txt" }
        return "$base.$normalizedExtension"
    }
}
