package com.sportday.mobile.data.repository

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "sportday_prefs")

class TokenManager(private val context: Context) {

    companion object {
        private val TOKEN_KEY = stringPreferencesKey("jwt_token")
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val ROLE_KEY = stringPreferencesKey("role")
        private val USER_ID_KEY = stringPreferencesKey("user_id")
        private val FULL_NAME_KEY = stringPreferencesKey("full_name")
        val BASE_URL_KEY = stringPreferencesKey("base_url")
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8080/"

        // The fingerprint-guarded credential. `biometric_token_ct` is an
        // AES-256/GCM ciphertext and `biometric_token_iv` its IV — both useless
        // without the Keystore key, which only a fingerprint can unlock. The rest
        // is the greeting: the name is not a credential, and the password is not
        // stored here or anywhere else.
        private val BIOMETRIC_TOKEN_IV_KEY = stringPreferencesKey("biometric_token_iv")
        private val BIOMETRIC_TOKEN_CT_KEY = stringPreferencesKey("biometric_token_ct")
        private val BIOMETRIC_USERNAME_KEY = stringPreferencesKey("biometric_username")
        private val BIOMETRIC_FULL_NAME_KEY = stringPreferencesKey("biometric_full_name")
        private val BIOMETRIC_ROLE_KEY = stringPreferencesKey("biometric_role")
        private val BIOMETRIC_USER_ID_KEY = stringPreferencesKey("biometric_user_id")

        /**
         * The token of the session that is running now, shared by every
         * TokenManager (each screen builds its own from a different context).
         *
         * It lives in memory so that while a fingerprint guards the sign-in there
         * is no readable copy of the JWT on disk: only the sealed blob is.
         */
        @Volatile
        private var sessionToken: String? = null
    }

    /**
     * A password login, exactly as before, with one change: a credential stored
     * for the previous session is dropped rather than left behind, because we
     * cannot re-seal this new token without a fingerprint and a stale blob would
     * be a second, older credential on the phone. The enable step is offered
     * again afterwards.
     */
    suspend fun saveAuth(response: com.sportday.mobile.data.model.AuthResponse) {
        sessionToken = response.token
        context.dataStore.edit { prefs ->
            prefs[TOKEN_KEY] = response.token
            prefs[USERNAME_KEY] = response.username
            prefs[ROLE_KEY] = response.role
            prefs[USER_ID_KEY] = response.userId.toString()
            prefs[FULL_NAME_KEY] = response.fullName
            clearBiometricFields(prefs)
        }
        BiometricAuthManager(context).deleteKey()
    }

    suspend fun getToken(): String? {
        sessionToken?.let { return it }
        return context.dataStore.data.map { prefs ->
            prefs[TOKEN_KEY]
        }.first()
    }

    /** The live token of a session a fingerprint has just released. In memory only. */
    fun useSessionToken(token: String) {
        sessionToken = token
    }

    suspend fun getUsername(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[USERNAME_KEY]
        }.first()
    }

    suspend fun getRole(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[ROLE_KEY]
        }.first()
    }

    suspend fun getUserId(): Long? {
        return context.dataStore.data.map { prefs ->
            prefs[USER_ID_KEY]?.toLongOrNull()
        }.first()
    }

    suspend fun getFullName(): String? {
        return context.dataStore.data.map { prefs ->
            prefs[FULL_NAME_KEY]
        }.first()
    }

    // ------------------------------------------------- fingerprint credential

    /** Is there a credential on this phone for a fingerprint to release? */
    suspend fun hasBiometricCredential(): Boolean {
        return context.dataStore.data.map { prefs ->
            prefs[BIOMETRIC_TOKEN_CT_KEY] != null && prefs[BIOMETRIC_TOKEN_IV_KEY] != null
        }.first()
    }

    suspend fun getBiometricSealedToken(): SealedToken? {
        return context.dataStore.data.map { prefs ->
            val iv = prefs[BIOMETRIC_TOKEN_IV_KEY]?.let { decodeBase64(it) }
            val ciphertext = prefs[BIOMETRIC_TOKEN_CT_KEY]?.let { decodeBase64(it) }
            if (iv == null || ciphertext == null) null else SealedToken(iv, ciphertext)
        }.first()
    }

    /** Who the stored credential belongs to, so the screen can greet them by name. */
    suspend fun getBiometricIdentity(): BiometricIdentity? {
        return context.dataStore.data.map { prefs ->
            val username = prefs[BIOMETRIC_USERNAME_KEY] ?: return@map null
            BiometricIdentity(
                username = username,
                fullName = prefs[BIOMETRIC_FULL_NAME_KEY].orEmpty(),
                role = prefs[BIOMETRIC_ROLE_KEY].orEmpty(),
                userId = prefs[BIOMETRIC_USER_ID_KEY]?.toLongOrNull() ?: 0L
            )
        }.first()
    }

    /**
     * Keeps the fingerprint-guarded copy of the token and deletes the plaintext
     * one, so that from here on the sealed blob is the only JWT on disk.
     */
    suspend fun storeBiometricCredential(sealed: SealedToken) {
        context.dataStore.edit { prefs ->
            prefs[BIOMETRIC_TOKEN_IV_KEY] = encodeBase64(sealed.iv)
            prefs[BIOMETRIC_TOKEN_CT_KEY] = encodeBase64(sealed.ciphertext)
            prefs[BIOMETRIC_USERNAME_KEY] = prefs[USERNAME_KEY].orEmpty()
            prefs[BIOMETRIC_FULL_NAME_KEY] = prefs[FULL_NAME_KEY].orEmpty()
            prefs[BIOMETRIC_ROLE_KEY] = prefs[ROLE_KEY].orEmpty()
            prefs[BIOMETRIC_USER_ID_KEY] = prefs[USER_ID_KEY].orEmpty()
            prefs.remove(TOKEN_KEY)
        }
    }

    /**
     * Puts a fingerprint-released token back in charge of the session. Again the
     * plaintext slot is cleared: the token is remembered in memory for the API
     * client, never written back to a file.
     */
    suspend fun restoreBiometricSession(token: String, identity: BiometricIdentity) {
        sessionToken = token
        context.dataStore.edit { prefs ->
            prefs.remove(TOKEN_KEY)
            prefs[USERNAME_KEY] = identity.username
            prefs[ROLE_KEY] = identity.role
            prefs[USER_ID_KEY] = identity.userId.toString()
            prefs[FULL_NAME_KEY] = identity.fullName
        }
    }

    /**
     * Ends the fingerprint credential for good: the ciphertext, the greeting and
     * the Keystore key it was sealed with. Turning the feature off, logging out,
     * and finding the key invalidated all come through here, because a
     * credential that is no longer offered must not still exist.
     */
    suspend fun clearBiometricCredential() {
        context.dataStore.edit { prefs -> clearBiometricFields(prefs) }
        BiometricAuthManager(context).deleteKey()
    }

    private fun clearBiometricFields(prefs: MutablePreferences) {
        prefs.remove(BIOMETRIC_TOKEN_IV_KEY)
        prefs.remove(BIOMETRIC_TOKEN_CT_KEY)
        prefs.remove(BIOMETRIC_USERNAME_KEY)
        prefs.remove(BIOMETRIC_FULL_NAME_KEY)
        prefs.remove(BIOMETRIC_ROLE_KEY)
        prefs.remove(BIOMETRIC_USER_ID_KEY)
    }

    private fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decodeBase64(text: String): ByteArray? =
        try {
            Base64.decode(text, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            null
        }

    // ----------------------------------------------------------------- server

    suspend fun saveBaseUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[BASE_URL_KEY] = url
        }
    }

    suspend fun getBaseUrl(): String {
        return context.dataStore.data.map { prefs ->
            prefs[BASE_URL_KEY] ?: DEFAULT_BASE_URL
        }.first()
    }

    /**
     * Logging out ends the session and the credential that could restore it: the
     * fingerprint is not a way to keep a session the student has just ended.
     */
    suspend fun clearAll() {
        sessionToken = null
        context.dataStore.edit { it.clear() }
        BiometricAuthManager(context).deleteKey()
    }
}
