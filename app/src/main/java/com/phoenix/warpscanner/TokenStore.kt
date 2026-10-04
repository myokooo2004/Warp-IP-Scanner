package com.phoenix.warpscanner

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the GitHub personal access token (PAT) used by the Stable tab's
 * auto-push, in EncryptedSharedPreferences. The token value is never
 * logged or displayed — callers only see set/unset state.
 */
object TokenStore {

    private const val FILE = "secure_prefs"
    private const val KEY_TOKEN = "github_pat"

    private fun prefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** @return the saved token, or null if none is set. Handle with care: never log it. */
    fun getToken(ctx: Context): String? {
        return try {
            prefs(ctx).getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun hasToken(ctx: Context): Boolean = getToken(ctx) != null

    fun setToken(ctx: Context, token: String) {
        try {
            prefs(ctx).edit().putString(KEY_TOKEN, token.trim()).apply()
        } catch (_: Exception) { /* best effort */ }
    }

    fun clearToken(ctx: Context) {
        try {
            prefs(ctx).edit().remove(KEY_TOKEN).apply()
        } catch (_: Exception) { /* best effort */ }
    }
}
