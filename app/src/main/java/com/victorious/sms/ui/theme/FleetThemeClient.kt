package com.victorious.sms.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Palette from the VictorLaunch fleet theme (FLEET_THEME.md, schemaVersion 1).
 * Each key is an ARGB value, or null when absent / blank / invalid. Per spec, a null key keeps
 * this app's default for the mapped Material3 role; it never fails the whole snapshot.
 */
data class FleetPalette(
    val background: Long? = null,
    val surface: Long? = null,
    val primary: Long? = null,
    val secondary: Long? = null,
    val onBackground: Long? = null,
    val muted: Long? = null,
    val danger: Long? = null,
    val stale: Long? = null,
    val tile: Long? = null,
) {
    companion object {
        /** No fleet snapshot: every role uses the built-in neon theme. */
        val NONE = FleetPalette()
    }
}

/** Pure parsing of the v1 snapshot. Only org.json; no Android framework calls. */
object FleetThemeCodec {
    const val SCHEMA_VERSION = 1
    val KEYS = listOf("background", "surface", "primary", "secondary", "onBackground", "muted", "danger", "stale", "tile")

    /** Parses "#RRGGBB" (opaque) or "#AARRGGBB" into ARGB; null otherwise. */
    fun parseHex(s: String?): Long? {
        val t = s?.trim() ?: return null
        if (!t.startsWith("#")) return null
        val h = t.substring(1)
        if (h.length != 6 && h.length != 8) return null
        if (!h.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        val v = h.toLong(16)
        return if (h.length == 6) (0xFF000000L or v) else v
    }

    /** Builds a palette from a key -> hex lookup, applying per key. */
    fun fromLookup(get: (String) -> String?): FleetPalette {
        fun c(k: String) = parseHex(get(k))
        return FleetPalette(
            background = c("background"), surface = c("surface"), primary = c("primary"),
            secondary = c("secondary"), onBackground = c("onBackground"), muted = c("muted"),
            danger = c("danger"), stale = c("stale"), tile = c("tile"),
        )
    }

    /** Returns null (caller falls back to neon) unless the JSON is valid and schemaVersion == 1. */
    fun parseJson(json: String?): FleetPalette? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            if (!o.has("schemaVersion") || o.optInt("schemaVersion", -1) != SCHEMA_VERSION) return null
            val p = o.optJSONObject("palette") ?: return null
            fromLookup { k -> if (p.has(k) && !p.isNull(k)) p.optString(k, "") else null }
        } catch (e: Exception) {
            null
        }
    }

    /** Publisher cert check: accept current/next pins, always reject revoked. Normalizes case/colons. */
    fun certAllowed(sha256: String?, allowed: Set<String>, revoked: Set<String>): Boolean {
        val n = normalizeCert(sha256) ?: return false
        if (revoked.any { normalizeCert(it) == n }) return false
        return allowed.any { normalizeCert(it) == n }
    }

    private fun normalizeCert(s: String?): String? {
        val h = s?.replace(":", "")?.trim()?.uppercase() ?: return null
        return if (h.length == 64 && h.all { it in '0'..'9' || it in 'A'..'F' }) h else null
    }
}

/**
 * Read-only client for VictorLaunch's fleet theme provider.
 * VictorLaunch is the sole writer; this class never calls insert/update/delete.
 */
object FleetThemeClient {
    private const val TAG = "FleetThemeClient"
    const val PROVIDER_PACKAGE = "com.victorious.launch"
    const val AUTHORITY = "com.victorious.launch.theme"
    val URI: Uri = Uri.parse("content://$AUTHORITY/current")
    const val ACTION_CHANGED = "com.victorious.launch.action.FLEET_THEME_CHANGED"

    /**
     * VictorLaunch signing cert SHA-256 pins. Source: victorlaunch keystore/README.md
     * (the dedicated *debug* key). No release-key cert is published yet; add it here when it is.
     */
    /** Current (debug) publisher cert. "next" and "release" are unpublished in the spec, so not listed. */
    val PINNED_CERTS: Set<String> = setOf(
        "B9:9A:AF:E2:C1:41:8B:5D:64:AA:0D:4B:AC:61:FC:F7:A7:BA:81:44:35:E0:EF:89:E8:AA:55:BE:56:4E:4A:69",
    )

    /** Revoked publisher certs (old committed VictorLaunch debug keystore). Always refused. */
    val REVOKED_CERTS: Set<String> = setOf(
        "1B:06:A8:9E:7F:F5:BA:21:C9:A4:F8:F1:F0:4F:95:E5:62:78:A1:22:18:AD:37:33:28:CE:E7:83:1D:4F:BA:CA",
    )

    private val _palette = MutableStateFlow(FleetPalette.NONE)
    val palette: StateFlow<FleetPalette> = _palette.asStateFlow()

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var started = false

    /** Idempotent: first query + ContentObserver registration. */
    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (started) return
            started = true
        }
        runCatching {
            app.contentResolver.registerContentObserver(
                URI, false,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = refresh(app)
                },
            )
        }.onFailure { Log.w(TAG, "observer not registered: ${it.message}") }
        refresh(app)
    }

    /** Re-query off the main thread and publish the result (or the fallback). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        io.execute { _palette.value = load(app) ?: FleetPalette.NONE }
    }

    private fun load(ctx: Context): FleetPalette? {
        if (!providerIsPinned(ctx)) return null
        return try {
            ctx.contentResolver.query(URI, null, null, null, null)?.use { cur ->
                if (!cur.moveToFirst()) return null
                val vIdx = cur.getColumnIndex("schemaVersion")
                if (vIdx < 0 || cur.getInt(vIdx) != FleetThemeCodec.SCHEMA_VERSION) return null
                val jIdx = cur.getColumnIndex("json")
                if (jIdx < 0) return null
                FleetThemeCodec.parseJson(cur.getString(jIdx))
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "not pinned by VictorLaunch; using built-in theme")
            null
        } catch (e: Exception) {
            Log.w(TAG, "fleet theme query failed: ${e.message}")
            null
        }
    }

    /** The authority must resolve to com.victorious.launch and that package's cert must be pinned. */
    private fun providerIsPinned(ctx: Context): Boolean {
        val pm = ctx.packageManager
        val info = runCatching { pm.resolveContentProvider(AUTHORITY, 0) }.getOrNull() ?: return false
        if (info.packageName != PROVIDER_PACKAGE) return false
        val cert = certSha256(pm, PROVIDER_PACKAGE) ?: return false
        return FleetThemeCodec.certAllowed(cert, PINNED_CERTS, REVOKED_CERTS)
    }

    @Suppress("DEPRECATION")
    private fun certSha256(pm: PackageManager, pkg: String): String? = runCatching {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return null
            if (info.hasMultipleSigners()) return null
            info.signingCertificateHistory
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = sigs?.lastOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrNull()
}

/** Wake-up from VictorLaunch. Extras are not trusted; we just re-query the pinned provider. */
class FleetThemeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FleetThemeClient.ACTION_CHANGED) return
        FleetThemeClient.refresh(context)
    }
}
