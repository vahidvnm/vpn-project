package com.vpnproject.app.profile

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.vpnproject.app.core.ImportedConfig
import java.security.KeyStore
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

    fun saveImportedConfig(config: ImportedConfig, displayName: String? = config.name): VpnProfile {
        val profile = VpnProfileFactory.fromImportedConfig(config, displayName)
        prefs.edit().apply {
            putString(key(profile.id, FIELD_NAME), profile.name)
            putString(key(profile.id, FIELD_KIND), profile.kind.name)
            putString(key(profile.id, FIELD_ENDPOINTS), encodeEndpoints(profile.endpoints))
            putLong(key(profile.id, FIELD_CREATED), profile.createdAtEpochMs)
            putLong(key(profile.id, FIELD_UPDATED), profile.updatedAtEpochMs)
            putString(key(profile.id, FIELD_RAW_CONFIG), crypto.encrypt(config.originalText))
            putString(KEY_PROFILE_IDS, mergeProfileIds(profile.id))
            putString(KEY_LAST_PROFILE_ID, profile.id)
        }.apply()
        return profile
    }

    fun listProfiles(): List<VpnProfile> = profileIds()
        .mapNotNull { id -> loadProfileMetadata(id) }
        .sortedByDescending { it.updatedAtEpochMs }

    fun latestProfile(): VpnProfile? = prefs.getString(KEY_LAST_PROFILE_ID, null)
        ?.let { loadProfileMetadata(it) }
        ?: listProfiles().firstOrNull()

    fun loadRawConfig(profileId: String): String? {
        val encrypted = prefs.getString(key(profileId, FIELD_RAW_CONFIG), null) ?: return null
        return crypto.decrypt(encrypted)
    }

    fun deleteProfile(profileId: String) {
        val remaining = profileIds().filterNot { it == profileId }
        prefs.edit().apply {
            remove(key(profileId, FIELD_NAME))
            remove(key(profileId, FIELD_KIND))
            remove(key(profileId, FIELD_ENDPOINTS))
            remove(key(profileId, FIELD_CREATED))
            remove(key(profileId, FIELD_UPDATED))
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
        return VpnProfile(
            id = id,
            name = name,
            kind = kind,
            endpoints = decodeEndpoints(prefs.getString(key(id, FIELD_ENDPOINTS), null).orEmpty()),
            createdAtEpochMs = created,
            updatedAtEpochMs = updated
        )
    }

    private fun mergeProfileIds(newId: String): String = (listOf(newId) + profileIds().filterNot { it == newId })
        .joinToString(ID_SEPARATOR)

    private fun profileIds(): List<String> = prefs.getString(KEY_PROFILE_IDS, null)
        ?.split(ID_SEPARATOR)
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()

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

    private fun String.encodeField(): String = Base64.encodeToString(toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)

    private fun String.decodeField(): String = String(Base64.decode(this, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)

    private fun key(profileId: String, field: String): String = "profile.$profileId.$field"

    private companion object {
        const val PREFS_NAME = "vpn_project_profiles"
        const val KEY_PROFILE_IDS = "profile_ids"
        const val KEY_LAST_PROFILE_ID = "last_profile_id"
        const val FIELD_NAME = "name"
        const val FIELD_KIND = "kind"
        const val FIELD_ENDPOINTS = "endpoints"
        const val FIELD_CREATED = "created_at"
        const val FIELD_UPDATED = "updated_at"
        const val FIELD_RAW_CONFIG = "raw_config"
        const val ID_SEPARATOR = ","
        const val ENDPOINT_SEPARATOR = ";"
        const val FIELD_SEPARATOR = ":"
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
