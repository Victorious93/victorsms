package com.victorious.sms.matrix

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Encrypted list of account ids (Matrix user ids) and the current one. */
class AccountRegistry(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "matrix_accounts",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun ids(): List<String> = prefs.getStringSet("ids", emptySet())!!.sorted()
    fun add(id: String) = prefs.edit().putStringSet("ids", (ids() + id).toSet()).apply()
    fun remove(id: String) {
        prefs.edit().putStringSet("ids", (ids() - id).toSet()).apply()
        if (current() == id) setCurrent(ids().firstOrNull())
    }
    fun current(): String? = prefs.getString("current", null)?.takeIf { it in ids() } ?: ids().firstOrNull()
    fun setCurrent(id: String?) = prefs.edit().putString("current", id).apply()

    /** Stable, filesystem-safe key for an account id. */
    companion object {
        fun key(id: String): String = id.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    }
}
