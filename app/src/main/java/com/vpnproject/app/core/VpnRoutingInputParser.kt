package com.vpnproject.app.core

data class DnsServerParseResult(
    val servers: List<String>,
    val rejectedEntries: List<String>,
    val omittedCount: Int
)

data class BypassPackageParseResult(
    val packages: List<String>,
    val invalidEntries: List<String>,
    val selfPackageEntries: Int,
    val omittedCount: Int
)

/** Pure validation for user-editable VPN DNS and per-app bypass settings. */
object VpnRoutingInputParser {
    const val DEFAULT_MAX_DNS_SERVERS = 4
    const val DEFAULT_MAX_BYPASS_PACKAGES = 64

    private val packageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun parseDnsServers(
        raw: String,
        maxServers: Int = DEFAULT_MAX_DNS_SERVERS
    ): DnsServerParseResult {
        require(maxServers > 0) { "maxServers must be positive." }
        val rejected = mutableListOf<String>()
        val valid = linkedSetOf<String>()

        tokens(raw).forEach { token ->
            val canonical = canonicalPublicIpv4(token)
            if (canonical == null) {
                rejected += token
            } else {
                valid += canonical
            }
        }

        val allServers = valid.toList()
        return DnsServerParseResult(
            servers = allServers.take(maxServers),
            rejectedEntries = rejected,
            omittedCount = (allServers.size - maxServers).coerceAtLeast(0)
        )
    }

    fun parseBypassPackages(
        raw: String,
        ownPackageName: String,
        maxPackages: Int = DEFAULT_MAX_BYPASS_PACKAGES
    ): BypassPackageParseResult {
        require(maxPackages > 0) { "maxPackages must be positive." }
        val invalid = mutableListOf<String>()
        val valid = linkedSetOf<String>()
        var selfPackageEntries = 0

        tokens(raw).forEach { token ->
            when {
                !packageNamePattern.matches(token) -> invalid += token
                token == ownPackageName -> selfPackageEntries++
                else -> valid += token
            }
        }

        val allPackages = valid.toList()
        return BypassPackageParseResult(
            packages = allPackages.take(maxPackages),
            invalidEntries = invalid,
            selfPackageEntries = selfPackageEntries,
            omittedCount = (allPackages.size - maxPackages).coerceAtLeast(0)
        )
    }

    private fun canonicalPublicIpv4(value: String): String? {
        val candidate = value.trim()
        if (!IpClassifier.isPublicIpv4(candidate)) return null
        return candidate.split('.').joinToString(".") { octet ->
            octet.toInt().toString()
        }
    }

    private fun tokens(raw: String): List<String> = raw
        .split(',', ';', ' ', '\t', '\n', '\r')
        .map(String::trim)
        .filter(String::isNotEmpty)
}
