package com.vpnproject.app.core

/** Small in-memory TTL cache for DNS answers. Persistent per-network cache comes later. */
class DnsCache {
    private data class Entry(
        val result: DnsLookupResult,
        val expiresAtEpochMs: Long
    )

    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    fun get(hostname: String, nowEpochMs: Long): DnsLookupResult? {
        val key = hostname.cacheKey()
        val entry = entries[key] ?: return null
        if (entry.expiresAtEpochMs <= nowEpochMs) {
            entries.remove(key)
            return null
        }
        return entry.result.copy(fromCache = true)
    }

    @Synchronized
    fun put(hostname: String, result: DnsLookupResult) {
        val expiresAt = result.addresses.minOfOrNull { it.expiresAtEpochMs } ?: return
        entries[hostname.cacheKey()] = Entry(result.copy(fromCache = false), expiresAt)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    private fun String.cacheKey(): String = trim().trimEnd('.').lowercase()
}
