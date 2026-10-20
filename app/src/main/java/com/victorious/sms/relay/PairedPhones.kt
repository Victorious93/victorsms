package com.victorious.sms.relay

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/** A paired phone. Every permission is OFF until Don turns it on. */
data class PairedPhone(
    val fingerprint: String,
    val name: String,
    val address: String, // host:port on the Victorious Mesh
    val allowSmsSend: Boolean = false,
    val allowCalls: Boolean = false,
    val mirrorInboxToPeer: Boolean = false,
)

class PairedPhones(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "paired_phones",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun all(): List<PairedPhone> = prefs.all.mapNotNull { (k, v) ->
        runCatching {
            val o = JSONObject(v as String)
            PairedPhone(k, o.getString("name"), o.getString("address"), o.optBoolean("sms"), o.optBoolean("calls"), o.optBoolean("mirror"))
        }.getOrNull()
    }.sortedBy { it.name }

    fun find(fp: String): PairedPhone? = all().firstOrNull { it.fingerprint == fp }

    fun save(p: PairedPhone) = prefs.edit().putString(p.fingerprint, JSONObject()
        .put("name", p.name).put("address", p.address)
        .put("sms", p.allowSmsSend).put("calls", p.allowCalls).put("mirror", p.mirrorInboxToPeer).toString()).apply()

    fun revoke(fp: String) = prefs.edit().remove(fp).apply()
}
