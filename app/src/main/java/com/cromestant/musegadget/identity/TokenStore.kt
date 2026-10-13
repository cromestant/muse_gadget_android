package com.cromestant.musegadget.identity

import android.content.Context
import android.content.SharedPreferences

/**
 * SDK token storage (SharedPreferences MODE_PRIVATE).
 * Token stays on-device, never logged, never leaves the device
 * except inside the pairing protocol later.
 */
class TokenStore(context: Context) {
    companion object {
        const val PREFS = "musegadget_identity"
        const val KEY_TOKEN = "sdk_token"
        const val KEY_MAC = "mac"
        val TOKEN_RE = Regex("mgst_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]")
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Validates regex; returns false (stores nothing) if malformed. */
    fun save(token: String): Boolean {
        if (!TOKEN_RE.matches(token)) return false
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return true
    }

    fun load(): String? = prefs.getString(KEY_TOKEN, null)

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun loadOrGenerateMac(generate: () -> String): String {
        prefs.getString(KEY_MAC, null)?.let {
            if (IdentityProtocol.isValidMac(it)) return it
        }
        val mac = generate()
        prefs.edit().putString(KEY_MAC, mac).apply()
        return mac
    }
}
