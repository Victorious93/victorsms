package com.victorious.sms.bridge

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable store for pending remote requests (sms.send, relay.call.place) so an on-device approval
 * survives process death. Values are opaque JSON strings; every record carries its own expiry and is
 * dropped by the owning queue once expired. Never logged.
 */
interface PendingStore {
    fun loadAll(): Map<String, String>
    fun put(id: String, json: String)
    fun remove(id: String)
}

/** AES-256-GCM EncryptedSharedPreferences (key in Android Keystore). */
class EncryptedPendingStore(context: Context, name: String) : PendingStore {
    private val prefs = EncryptedSharedPreferences.create(
        context, name,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    override fun loadAll(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    override fun put(id: String, json: String) { prefs.edit().putString(id, json).apply() }
    override fun remove(id: String) { prefs.edit().remove(id).apply() }
}

/** In-memory store (unit tests / fallback if the Keystore is unavailable). */
class MemoryPendingStore : PendingStore {
    val map = ConcurrentHashMap<String, String>()
    override fun loadAll(): Map<String, String> = HashMap(map)
    override fun put(id: String, json: String) { map[id] = json }
    override fun remove(id: String) { map.remove(id) }
}
