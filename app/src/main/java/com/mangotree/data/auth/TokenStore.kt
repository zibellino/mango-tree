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

    companion object {
        private const val KEY_TOKEN = "github_oauth_token"
    }
}
