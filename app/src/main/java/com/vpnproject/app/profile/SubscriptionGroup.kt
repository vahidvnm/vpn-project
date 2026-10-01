package com.vpnproject.app.profile

/**
 * Metadata for a user-provided subscription URL. The URL itself can contain an
 * account token and is therefore stored encrypted in SecureProfileStore; this
 * model only exposes safe metadata for the UI.
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
