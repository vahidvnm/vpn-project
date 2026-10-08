package com.vpnproject.app.profile

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.vpnproject.app.core.ImportedConfig
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Small local profile store for the VPN hub foundation.
 *
 * Raw configs are encrypted with an Android Keystore AES-GCM key before being
 * written to SharedPreferences. Non-secret metadata is stored separately so the
 * UI can list profiles without decrypting or exposing credentials.
 */
class SecureProfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val crypto = ProfileCrypto()

    fun saveImportedConfig(
        config: ImportedConfig,
        displayName: String? = config.name,
        stableProfileId: String? = null
    ): VpnProfile {
        val generated = VpnProfileFactory.fromImportedConfig(config, displayName)
        val existing = stableProfileId?.let { loadProfileMetadata(it) }
        val now = System.currentTimeMillis()
        val profile = if (stableProfileId == null) {
            generated
        } else {
            generated.copy(
                id = stableProfileId,
                createdAtEpochMs = existing?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now,
                lastVerifiedEpochMs = existing?.lastVerifiedEpochMs,
                lastVerifiedNetwork = existing?.lastVerifiedNetwork,
                lastVerifiedLatencyMs = existing?.lastVerifiedLatencyMs,
                lastTestedEpochMs = existing?.lastTestedEpochMs,
                lastTestKind = existing?.lastTestKind,
                lastTestSuccess = existing?.lastTestSuccess,
                lastTestLatencyMs = existing?.lastTestLatencyMs,
                lastTestScore = existing?.lastTestScore,
                lastTestNetwork = existing?.lastTestNetwork,
                testNetworkHistory = existing?.testNetworkHistory,
                favorite = existing?.favorite ?: generated.favorite,
                useCount = existing?.useCount ?: generated.useCount,
                lastUsedEpochMs = existing?.lastUsedEpochMs
            )
        }
        prefs.edit().apply {
            putString(key(profile.id, FIELD_NAME), profile.name)
            putString(key(profile.id, FIELD_KIND), profile.kind.name)
            putString(key(profile.id, FIELD_ENDPOINTS), encodeEndpoints(profile.endpoints))
            putLong(key(profile.id, FIELD_CREATED), profile.createdAtEpochMs)
            putLong(key(profile.id, FIELD_UPDATED), profile.updatedAtEpochMs)
            putBoolean(key(profile.id, FIELD_FAVORITE), profile.favorite)
            putLong(key(profile.id, FIELD_USE_COUNT), profile.useCount.coerceAtLeast(0L))
            profile.lastUsedEpochMs?.let { putLong(key(profile.id, FIELD_LAST_USED), it) }
                ?: remove(key(profile.id, FIELD_LAST_USED))
            profile.lastVerifiedEpochMs?.let { putLong(key(profile.id, FIELD_LAST_VERIFIED), it) }
                ?: remove(key(profile.id, FIELD_LAST_VERIFIED))
            profile.lastVerifiedNetwork?.let { putString(key(profile.id, FIELD_LAST_VERIFIED_NETWORK), it) }
                ?: remove(key(profile.id, FIELD_LAST_VERIFIED_NETWORK))
            profile.lastVerifiedLatencyMs?.let { putLong(key(profile.id, FIELD_LAST_VERIFIED_LATENCY), it) }
                ?: remove(key(profile.id, FIELD_LAST_VERIFIED_LATENCY))
            profile.lastTestedEpochMs?.let { putLong(key(profile.id, FIELD_LAST_TESTED), it) }
                ?: remove(key(profile.id, FIELD_LAST_TESTED))
            profile.lastTestKind?.takeIf { it.isNotBlank() }?.let { putString(key(profile.id, FIELD_LAST_TEST_KIND), it) }
                ?: remove(key(profile.id, FIELD_LAST_TEST_KIND))
            profile.lastTestSuccess?.let { putBoolean(key(profile.id, FIELD_LAST_TEST_SUCCESS), it) }
                ?: remove(key(profile.id, FIELD_LAST_TEST_SUCCESS))
            profile.lastTestLatencyMs?.let { putLong(key(profile.id, FIELD_LAST_TEST_LATENCY), it) }
                ?: remove(key(profile.id, FIELD_LAST_TEST_LATENCY))
            profile.lastTestScore?.let { putInt(key(profile.id, FIELD_LAST_TEST_SCORE), it) }
                ?: remove(key(profile.id, FIELD_LAST_TEST_SCORE))
            profile.lastTestNetwork?.let { putString(key(profile.id, FIELD_LAST_TEST_NETWORK), it) }
                ?: remove(key(profile.id, FIELD_LAST_TEST_NETWORK))
            profile.testNetworkHistory?.takeIf { it.isNotBlank() }?.let { putString(key(profile.id, FIELD_TEST_NETWORK_HISTORY), it) }
                ?: remove(key(profile.id, FIELD_TEST_NETWORK_HISTORY))
            putString(key(profile.id, FIELD_RAW_CONFIG), crypto.encrypt(config.originalText))
            putString(KEY_PROFILE_IDS, mergeProfileIds(profile.id))
            putString(KEY_LAST_PROFILE_ID, profile.id)
        }.apply()
        return profile
    }

    fun listProfiles(): List<VpnProfile> = profileIds()
        .mapNotNull { id -> loadProfileMetadata(id) }
        .sortedWith(compareByDescending<VpnProfile> { it.favorite }.thenByDescending { it.updatedAtEpochMs })

    fun profile(profileId: String): VpnProfile? = loadProfileMetadata(profileId)

    fun latestProfile(): VpnProfile? = prefs.getString(KEY_LAST_PROFILE_ID, null)
        ?.let { loadProfileMetadata(it) }
        ?: listProfiles().firstOrNull()

    fun loadRawConfig(profileId: String): String? {
        val encrypted = prefs.getString(key(profileId, FIELD_RAW_CONFIG), null) ?: return null
        return crypto.decrypt(encrypted)
    }

    fun saveSubscriptionGroup(name: String, url: String): SubscriptionGroup {
        val id = stableSubscriptionGroupId(url)
        val existing = loadSubscriptionGroupMetadata(id)
        val now = System.currentTimeMillis()
        val sanitizedName = name.sanitizedProfileName().takeIf { it.isNotBlank() }
            ?: existing?.name
            ?: "Subscription"
        prefs.edit().apply {
            putString(subscriptionKey(id, FIELD_NAME), sanitizedName)
            putString(subscriptionKey(id, FIELD_URL), crypto.encrypt(url.trim()))
            putLong(subscriptionKey(id, FIELD_CREATED), existing?.createdAtEpochMs ?: now)
            putLong(subscriptionKey(id, FIELD_UPDATED), now)
            existing?.lastSyncEpochMs?.let { putLong(subscriptionKey(id, FIELD_LAST_SYNC), it) }
            existing?.lastResult?.let { putString(subscriptionKey(id, FIELD_LAST_RESULT), it) }
            putString(subscriptionKey(id, FIELD_SUB_PROFILE_IDS), existing?.profileIds?.joinToString(ID_SEPARATOR).orEmpty())
            putString(KEY_SUBSCRIPTION_GROUP_IDS, mergeSubscriptionGroupIds(id))
        }.apply()
        return loadSubscriptionGroupMetadata(id) ?: SubscriptionGroup(id, sanitizedName, emptyList(), now, now)
    }

    fun saveClipboardSubscriptionGroup(name: String, subscriptionText: String): SubscriptionGroup {
        val id = stableClipboardSubscriptionGroupId(name, subscriptionText)
        val existing = loadSubscriptionGroupMetadata(id)
        val now = System.currentTimeMillis()
        val sanitizedName = name.sanitizedProfileName().takeIf { it.isNotBlank() }
            ?: existing?.name
            ?: "Clipboard subscription"
        prefs.edit().apply {
            putString(subscriptionKey(id, FIELD_NAME), sanitizedName)
            remove(subscriptionKey(id, FIELD_URL))
            putLong(subscriptionKey(id, FIELD_CREATED), existing?.createdAtEpochMs ?: now)
            putLong(subscriptionKey(id, FIELD_UPDATED), now)
            existing?.lastSyncEpochMs?.let { putLong(subscriptionKey(id, FIELD_LAST_SYNC), it) }
            existing?.lastResult?.let { putString(subscriptionKey(id, FIELD_LAST_RESULT), it) }
            putString(subscriptionKey(id, FIELD_SUB_PROFILE_IDS), existing?.profileIds?.joinToString(ID_SEPARATOR).orEmpty())
            putString(KEY_SUBSCRIPTION_GROUP_IDS, mergeSubscriptionGroupIds(id))
        }.apply()
        return loadSubscriptionGroupMetadata(id) ?: SubscriptionGroup(id, sanitizedName, emptyList(), now, now)
    }

    fun listSubscriptionGroups(): List<SubscriptionGroup> = subscriptionGroupIds()
        .mapNotNull { id -> loadSubscriptionGroupMetadata(id) }
        .sortedByDescending { it.updatedAtEpochMs }

    fun subscriptionGroup(groupId: String): SubscriptionGroup? = loadSubscriptionGroupMetadata(groupId)

    fun loadSubscriptionUrl(groupId: String): String? {
        val encrypted = prefs.getString(subscriptionKey(groupId, FIELD_URL), null) ?: return null
        return crypto.decrypt(encrypted)
    }

    fun markSubscriptionSynced(
        groupId: String,
        profileIds: List<String>,
        result: String,
        syncedAtEpochMs: Long = System.currentTimeMillis()
    ): SubscriptionGroup? {
        prefs.edit().apply {
            putString(subscriptionKey(groupId, FIELD_SUB_PROFILE_IDS), profileIds.joinToString(ID_SEPARATOR))
            putLong(subscriptionKey(groupId, FIELD_LAST_SYNC), syncedAtEpochMs)
            putString(subscriptionKey(groupId, FIELD_LAST_RESULT), result.take(160))
            putLong(subscriptionKey(groupId, FIELD_UPDATED), syncedAtEpochMs)
            putString(KEY_SUBSCRIPTION_GROUP_IDS, mergeSubscriptionGroupIds(groupId))
        }.apply()
        return loadSubscriptionGroupMetadata(groupId)
    }

    fun renameProfile(profileId: String, newName: String): VpnProfile? {
        val sanitized = newName.sanitizedProfileName().takeIf { it.isNotBlank() } ?: return loadProfileMetadata(profileId)
        prefs.edit()
            .putString(key(profileId, FIELD_NAME), sanitized)
            .putLong(key(profileId, FIELD_UPDATED), System.currentTimeMillis())
            .putString(KEY_LAST_PROFILE_ID, profileId)
            .apply()
        return loadProfileMetadata(profileId)
    }

    fun setFavorite(profileId: String, favorite: Boolean): VpnProfile? {
        prefs.edit()
            .putBoolean(key(profileId, FIELD_FAVORITE), favorite)
            .putLong(key(profileId, FIELD_UPDATED), System.currentTimeMillis())
            .putString(KEY_LAST_PROFILE_ID, profileId)
            .apply()
        return loadProfileMetadata(profileId)
    }

    fun markVerified(
        profileId: String,
        verifiedAtEpochMs: Long = System.currentTimeMillis(),
        network: String? = null,
        latencyMs: Long? = null
    ): VpnProfile? {
        val updatedHistory = updatedTestNetworkHistory(
            profileId = profileId,
            network = network,
            kind = HISTORY_KIND_VERIFIED,
            success = true,
            latencyMs = latencyMs,
            score = null,
            checkedAtEpochMs = verifiedAtEpochMs
        )
        prefs.edit().apply {
            putLong(key(profileId, FIELD_LAST_VERIFIED), verifiedAtEpochMs)
            network?.takeIf { it.isNotBlank() }?.let { putString(key(profileId, FIELD_LAST_VERIFIED_NETWORK), it) }
                ?: remove(key(profileId, FIELD_LAST_VERIFIED_NETWORK))
            if (latencyMs != null && latencyMs >= 0L) putLong(key(profileId, FIELD_LAST_VERIFIED_LATENCY), latencyMs)
            else remove(key(profileId, FIELD_LAST_VERIFIED_LATENCY))
            updatedHistory?.let { putString(key(profileId, FIELD_TEST_NETWORK_HISTORY), it) }
                ?: remove(key(profileId, FIELD_TEST_NETWORK_HISTORY))
            putLong(key(profileId, FIELD_UPDATED), verifiedAtEpochMs)
            putString(KEY_LAST_PROFILE_ID, profileId)
        }.apply()
        return loadProfileMetadata(profileId)
    }

    fun markTested(
        profileId: String,
        testedAtEpochMs: Long = System.currentTimeMillis(),
        success: Boolean,
        network: String? = null,
        latencyMs: Long? = null,
        score: Int? = null,
        testKind: String? = null
    ): VpnProfile? {
        val normalizedKind = testKind?.takeIf { it.isNotBlank() }?.take(24)
        val updatedHistory = updatedTestNetworkHistory(
            profileId = profileId,
            network = network,
            kind = normalizedKind ?: HISTORY_KIND_TEST,
            success = success,
            latencyMs = latencyMs,
            score = score,
            checkedAtEpochMs = testedAtEpochMs
        )
        prefs.edit().apply {
            putLong(key(profileId, FIELD_LAST_TESTED), testedAtEpochMs)
            normalizedKind?.let { putString(key(profileId, FIELD_LAST_TEST_KIND), it) }
                ?: remove(key(profileId, FIELD_LAST_TEST_KIND))
            putBoolean(key(profileId, FIELD_LAST_TEST_SUCCESS), success)
            network?.takeIf { it.isNotBlank() }?.let { putString(key(profileId, FIELD_LAST_TEST_NETWORK), it) }
                ?: remove(key(profileId, FIELD_LAST_TEST_NETWORK))
            if (latencyMs != null && latencyMs >= 0L) putLong(key(profileId, FIELD_LAST_TEST_LATENCY), latencyMs)
            else remove(key(profileId, FIELD_LAST_TEST_LATENCY))
            if (score != null && score >= 0) putInt(key(profileId, FIELD_LAST_TEST_SCORE), score)
            else remove(key(profileId, FIELD_LAST_TEST_SCORE))
            updatedHistory?.let { putString(key(profileId, FIELD_TEST_NETWORK_HISTORY), it) }
                ?: remove(key(profileId, FIELD_TEST_NETWORK_HISTORY))
            putLong(key(profileId, FIELD_UPDATED), testedAtEpochMs)
            putString(KEY_LAST_PROFILE_ID, profileId)
        }.apply()
        return loadProfileMetadata(profileId)
    }

    /** Records an explicit user-started connection attempt for profile ranking. */
    fun markUsed(profileId: String, usedAtEpochMs: Long = System.currentTimeMillis()): VpnProfile? {
        val profile = loadProfileMetadata(profileId) ?: return null
        val nextUseCount = if (profile.useCount < Long.MAX_VALUE) profile.useCount + 1L else Long.MAX_VALUE
        prefs.edit()
            .putLong(key(profileId, FIELD_USE_COUNT), nextUseCount)
            .putLong(key(profileId, FIELD_LAST_USED), usedAtEpochMs)
            .putString(KEY_LAST_PROFILE_ID, profileId)
            .apply()
        return loadProfileMetadata(profileId)
    }

    fun deleteProfile(profileId: String) {
        val remaining = profileIds().filterNot { it == profileId }
        prefs.edit().apply {
            remove(key(profileId, FIELD_NAME))
            remove(key(profileId, FIELD_KIND))
            remove(key(profileId, FIELD_ENDPOINTS))
            remove(key(profileId, FIELD_CREATED))
            remove(key(profileId, FIELD_UPDATED))
            remove(key(profileId, FIELD_FAVORITE))
            remove(key(profileId, FIELD_USE_COUNT))
            remove(key(profileId, FIELD_LAST_USED))
            remove(key(profileId, FIELD_LAST_VERIFIED))
            remove(key(profileId, FIELD_LAST_VERIFIED_NETWORK))
            remove(key(profileId, FIELD_LAST_VERIFIED_LATENCY))
            remove(key(profileId, FIELD_LAST_TESTED))
            remove(key(profileId, FIELD_LAST_TEST_KIND))
            remove(key(profileId, FIELD_LAST_TEST_SUCCESS))
            remove(key(profileId, FIELD_LAST_TEST_LATENCY))
            remove(key(profileId, FIELD_LAST_TEST_SCORE))
            remove(key(profileId, FIELD_LAST_TEST_NETWORK))
            remove(key(profileId, FIELD_TEST_NETWORK_HISTORY))
            remove(key(profileId, FIELD_RAW_CONFIG))
            putString(KEY_PROFILE_IDS, remaining.joinToString(ID_SEPARATOR))
            if (prefs.getString(KEY_LAST_PROFILE_ID, null) == profileId) {
                if (remaining.isEmpty()) remove(KEY_LAST_PROFILE_ID) else putString(KEY_LAST_PROFILE_ID, remaining.first())
            }
        }.apply()
    }

    private fun loadProfileMetadata(id: String): VpnProfile? {
        val name = prefs.getString(key(id, FIELD_NAME), null) ?: return null
        val kind = prefs.getString(key(id, FIELD_KIND), null)?.let { runCatching { VpnProfileKind.valueOf(it) }.getOrNull() }
            ?: VpnProfileKind.UNKNOWN
        val created = prefs.getLong(key(id, FIELD_CREATED), 0L).takeIf { it > 0L } ?: return null
        val updated = prefs.getLong(key(id, FIELD_UPDATED), created)
        val useCount = prefs.getLong(key(id, FIELD_USE_COUNT), 0L).coerceAtLeast(0L)
        val lastUsed = prefs.getLong(key(id, FIELD_LAST_USED), 0L).takeIf { it > 0L }
        val lastVerified = prefs.getLong(key(id, FIELD_LAST_VERIFIED), 0L).takeIf { it > 0L }
        val lastVerifiedLatency = prefs.getLong(key(id, FIELD_LAST_VERIFIED_LATENCY), -1L).takeIf { it >= 0L }
        val lastTested = prefs.getLong(key(id, FIELD_LAST_TESTED), 0L).takeIf { it > 0L }
        val hasLastTestSuccess = prefs.contains(key(id, FIELD_LAST_TEST_SUCCESS))
        val lastTestLatency = prefs.getLong(key(id, FIELD_LAST_TEST_LATENCY), -1L).takeIf { it >= 0L }
        val lastTestScore = prefs.getInt(key(id, FIELD_LAST_TEST_SCORE), -1).takeIf { it >= 0 }
        return VpnProfile(
            id = id,
            name = name,
            kind = kind,
            endpoints = decodeEndpoints(prefs.getString(key(id, FIELD_ENDPOINTS), null).orEmpty()),
            createdAtEpochMs = created,
            updatedAtEpochMs = updated,
            lastVerifiedEpochMs = lastVerified,
            lastVerifiedNetwork = prefs.getString(key(id, FIELD_LAST_VERIFIED_NETWORK), null),
            lastVerifiedLatencyMs = lastVerifiedLatency,
            lastTestedEpochMs = lastTested,
            lastTestKind = prefs.getString(key(id, FIELD_LAST_TEST_KIND), null),
            lastTestSuccess = if (hasLastTestSuccess) prefs.getBoolean(key(id, FIELD_LAST_TEST_SUCCESS), false) else null,
            lastTestLatencyMs = lastTestLatency,
            lastTestScore = lastTestScore,
            lastTestNetwork = prefs.getString(key(id, FIELD_LAST_TEST_NETWORK), null),
            testNetworkHistory = prefs.getString(key(id, FIELD_TEST_NETWORK_HISTORY), null),
            favorite = prefs.getBoolean(key(id, FIELD_FAVORITE), false),
            useCount = useCount,
            lastUsedEpochMs = lastUsed
        )
    }

    private fun loadSubscriptionGroupMetadata(id: String): SubscriptionGroup? {
        val name = prefs.getString(subscriptionKey(id, FIELD_NAME), null) ?: return null
        val created = prefs.getLong(subscriptionKey(id, FIELD_CREATED), 0L).takeIf { it > 0L } ?: return null
        val updated = prefs.getLong(subscriptionKey(id, FIELD_UPDATED), created)
        val lastSync = prefs.getLong(subscriptionKey(id, FIELD_LAST_SYNC), 0L).takeIf { it > 0L }
        val profileIds = prefs.getString(subscriptionKey(id, FIELD_SUB_PROFILE_IDS), null)
            ?.split(ID_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return SubscriptionGroup(
            id = id,
            name = name,
            profileIds = profileIds,
            createdAtEpochMs = created,
            updatedAtEpochMs = updated,
            lastSyncEpochMs = lastSync,
            lastResult = prefs.getString(subscriptionKey(id, FIELD_LAST_RESULT), null)
        )
    }

    private fun mergeProfileIds(newId: String): String = (listOf(newId) + profileIds().filterNot { it == newId })
        .joinToString(ID_SEPARATOR)

    private fun mergeSubscriptionGroupIds(newId: String): String = (listOf(newId) + subscriptionGroupIds().filterNot { it == newId })
        .joinToString(ID_SEPARATOR)

    private fun profileIds(): List<String> = prefs.getString(KEY_PROFILE_IDS, null)
        ?.split(ID_SEPARATOR)
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()

    private fun subscriptionGroupIds(): List<String> = prefs.getString(KEY_SUBSCRIPTION_GROUP_IDS, null)
        ?.split(ID_SEPARATOR)
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()

    private fun updatedTestNetworkHistory(
        profileId: String,
        network: String?,
        kind: String,
        success: Boolean,
        latencyMs: Long?,
        score: Int?,
        checkedAtEpochMs: Long
    ): String? {
        val networkToken = historyToken(network, fallback = "unknown", maxLength = 32)
        val kindToken = historyToken(kind, fallback = HISTORY_KIND_TEST, maxLength = 24)
        val latencyToken = latencyMs?.takeIf { it >= 0L }?.toString().orEmpty()
        val scoreToken = score?.takeIf { it >= 0 }?.toString().orEmpty()
        val entry = listOf(
            networkToken,
            kindToken,
            if (success) "1" else "0",
            latencyToken,
            scoreToken,
            checkedAtEpochMs.coerceAtLeast(0L).toString()
        ).joinToString(HISTORY_FIELD_SEPARATOR)
        val existing = prefs.getString(key(profileId, FIELD_TEST_NETWORK_HISTORY), null).orEmpty()
        val retained = existing
            .split(HISTORY_ENTRY_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { historyEntryMatches(it, networkToken, kindToken) }
        return (listOf(entry) + retained)
            .take(MAX_TEST_NETWORK_HISTORY_ENTRIES)
            .joinToString(HISTORY_ENTRY_SEPARATOR)
            .takeIf { it.isNotBlank() }
    }

    private fun historyEntryMatches(entry: String, networkToken: String, kindToken: String): Boolean {
        val parts = entry.split(HISTORY_FIELD_SEPARATOR)
        return parts.getOrNull(0) == networkToken && parts.getOrNull(1) == kindToken
    }

    private fun historyToken(value: String?, fallback: String, maxLength: Int): String = value.orEmpty()
        .trim()
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9+_.-]"), "-")
        .take(maxLength)
        .ifBlank { fallback }

    private fun encodeEndpoints(endpoints: List<VpnProfileEndpoint>): String = endpoints.joinToString(ENDPOINT_SEPARATOR) { endpoint ->
        listOf(
            endpoint.protocol,
            endpoint.host,
            endpoint.port.toString(),
            endpoint.verifyHost.orEmpty()
        ).joinToString(FIELD_SEPARATOR) { it.encodeField() }
    }

    private fun decodeEndpoints(value: String): List<VpnProfileEndpoint> {
        if (value.isBlank()) return emptyList()
        return value.split(ENDPOINT_SEPARATOR).mapNotNull { entry ->
            val parts = entry.split(FIELD_SEPARATOR).map { it.decodeField() }
            val port = parts.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
            VpnProfileEndpoint(
                protocol = parts.getOrNull(0).orEmpty(),
                host = parts.getOrNull(1).orEmpty(),
                port = port,
                verifyHost = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
            )
        }
    }

    private fun String.sanitizedProfileName(): String = replace(Regex("\\s+"), " ")
        .trim()
        .take(80)

    private fun String.encodeField(): String = Base64.encodeToString(toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)

    private fun String.decodeField(): String = String(Base64.decode(this, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)

    fun stableSubscriptionProfileId(groupId: String, link: String): String = "sub-profile-${sha256(groupId + "\\n" + link.trim()).take(16)}"

    private fun stableSubscriptionGroupId(url: String): String = "sub-${sha256(url.trim()).take(16)}"

    private fun stableClipboardSubscriptionGroupId(name: String, subscriptionText: String): String =
        "clip-sub-${sha256(name.sanitizedProfileName() + "\n" + subscriptionText.trim()).take(16)}"

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private fun key(profileId: String, field: String): String = "profile.$profileId.$field"

    private fun subscriptionKey(groupId: String, field: String): String = "subscription.$groupId.$field"

    private companion object {
        const val PREFS_NAME = "vpn_project_profiles"
        const val KEY_PROFILE_IDS = "profile_ids"
        const val KEY_LAST_PROFILE_ID = "last_profile_id"
        const val KEY_SUBSCRIPTION_GROUP_IDS = "subscription_group_ids"
        const val FIELD_NAME = "name"
        const val FIELD_KIND = "kind"
        const val FIELD_ENDPOINTS = "endpoints"
        const val FIELD_CREATED = "created_at"
        const val FIELD_UPDATED = "updated_at"
        const val FIELD_FAVORITE = "favorite"
        const val FIELD_USE_COUNT = "use_count"
        const val FIELD_LAST_USED = "last_used_at"
        const val FIELD_LAST_VERIFIED = "last_verified_at"
        const val FIELD_LAST_VERIFIED_NETWORK = "last_verified_network"
        const val FIELD_LAST_VERIFIED_LATENCY = "last_verified_latency_ms"
        const val FIELD_LAST_TESTED = "last_tested_at"
        const val FIELD_LAST_TEST_KIND = "last_test_kind"
        const val FIELD_LAST_TEST_SUCCESS = "last_test_success"
        const val FIELD_LAST_TEST_LATENCY = "last_test_latency_ms"
        const val FIELD_LAST_TEST_SCORE = "last_test_score"
        const val FIELD_LAST_TEST_NETWORK = "last_test_network"
        const val FIELD_TEST_NETWORK_HISTORY = "test_network_history"
        const val FIELD_RAW_CONFIG = "raw_config"
        const val FIELD_URL = "url"
        const val FIELD_LAST_SYNC = "last_sync_at"
        const val FIELD_LAST_RESULT = "last_result"
        const val FIELD_SUB_PROFILE_IDS = "profile_ids"
        const val ID_SEPARATOR = ","
        const val ENDPOINT_SEPARATOR = ";"
        const val FIELD_SEPARATOR = ":"
        const val HISTORY_ENTRY_SEPARATOR = ";"
        const val HISTORY_FIELD_SEPARATOR = "|"
        const val HISTORY_KIND_VERIFIED = "verified"
        const val HISTORY_KIND_TEST = "test"
        const val MAX_TEST_NETWORK_HISTORY_ENTRIES = 8
    }
}

private class ProfileCrypto {
    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return listOf(iv, encrypted).joinToString(PART_SEPARATOR) { bytes ->
            Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
        }
    }

    fun decrypt(payload: String): String? = runCatching {
        val parts = payload.split(PART_SEPARATOR)
        require(parts.size == 2) { "Invalid encrypted profile payload." }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP or Base64.URL_SAFE)
        val encrypted = Base64.decode(parts[1], Base64.NO_WRAP or Base64.URL_SAFE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "vpn_project_profiles_aes_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PART_SEPARATOR = "."
    }
}
