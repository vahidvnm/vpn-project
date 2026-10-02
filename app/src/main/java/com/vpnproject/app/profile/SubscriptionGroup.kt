package com.vpnproject.app.profile

/**
 * Metadata for a user-provided subscription group. A refreshable group stores
 * the URL encrypted in SecureProfileStore; clipboard-only groups store only
 * safe metadata and encrypted profile configs, not the raw subscription text.
 */
data class SubscriptionGroup(
    val id: String,
    val name: String,
    val profileIds: List<String>,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val lastSyncEpochMs: Long? = null,
    val lastResult: String? = null
) {
    val displayName: String get() = name.ifBlank { "Subscription" }
}
