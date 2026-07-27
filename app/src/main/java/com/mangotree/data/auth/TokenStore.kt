package com.mangotree.data.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class TokenStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "mangotree_secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveToken(token: String) = prefs.edit().putString(KEY_TOKEN, token).apply()

    fun getToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun clearToken() = prefs.edit().remove(KEY_TOKEN).apply()

    fun isLoggedIn() = getToken() != null

    fun saveClientCredentials(clientId: String, clientSecret: String) {
        prefs.edit()
            .putString(KEY_CLIENT_ID, clientId)
            .putString(KEY_CLIENT_SECRET, clientSecret)
            .apply()
    }

    fun getClientId(): String? = prefs.getString(KEY_CLIENT_ID, null)

    fun getClientSecret(): String? = prefs.getString(KEY_CLIENT_SECRET, null)

    companion object {
        private const val KEY_TOKEN = "github_oauth_token"
        private const val KEY_CLIENT_ID = "github_oauth_client_id"
        private const val KEY_CLIENT_SECRET = "github_oauth_client_secret"
    }
}
