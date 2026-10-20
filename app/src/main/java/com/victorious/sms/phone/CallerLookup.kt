package com.victorious.sms.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import com.victorious.sms.block.BlockAction
import com.victorious.sms.block.Blocklist
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CallerInfo(val name: String, val source: String)

/** Lookup settings. The PC endpoint is empty (off) and the online provider is off by default. */
class LookupSettings(context: Context) {
    private val p = context.getSharedPreferences("caller_lookup", Context.MODE_PRIVATE)
    var pcUrl: String get() = p.getString("pcUrl", "").orEmpty(); set(v) = p.edit().putString("pcUrl", v.trim()).apply()
    var onlineEnabled: Boolean get() = p.getBoolean("online", false); set(v) = p.edit().putBoolean("online", v).apply()
}

/** An online caller-ID provider. None is configured; adding a paid one needs Don's approval. */
interface OnlineLookupProvider { fun lookup(number: String): CallerInfo? }

/**
 * Lookup order: (1) device contacts and local lists, (2) Don's PC over the mesh
 * (GET {pcUrl}?number=... -> {"name": "..."}), (3) optional online provider (off; none configured).
 * Call off the main thread.
 */
class CallerLookup(private val context: Context, private val online: OnlineLookupProvider? = null) {
    fun lookup(number: String): CallerInfo? {
        if (number.isBlank()) return null
        contact(number)?.let { return CallerInfo(it, "contacts") }
        when (Blocklist(context).decideNumber(number)) {
            BlockAction.BLOCK -> return CallerInfo("Blocked number", "blocklist")
            BlockAction.SILENCE -> return CallerInfo("Silenced number", "blocklist")
            else -> {}
        }
        val s = LookupSettings(context)
        if (s.pcUrl.startsWith("http")) pc(s.pcUrl, number)?.let { return CallerInfo(it, "PC") }
        if (s.onlineEnabled) online?.lookup(number)?.let { return it }
        return null
    }

    private fun contact(number: String): String? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }

    private fun pc(base: String, number: String): String? = runCatching {
        val sep = if (base.contains("?")) "&" else "?"
        val c = URL(base + sep + "number=" + URLEncoder.encode(number, "UTF-8")).openConnection() as HttpURLConnection
        c.connectTimeout = 2500; c.readTimeout = 2500
        try {
            if (c.responseCode != 200) null
            else JSONObject(c.inputStream.bufferedReader().use { it.readText().take(4096) }).optString("name").ifBlank { null }
        } finally { c.disconnect() }
    }.getOrNull()
}
