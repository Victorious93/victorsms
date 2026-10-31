package com.victorious.sms.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.victorious.sms.assist.PcSettings
import com.victorious.sms.phone.LookupSettings
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The only settings config.export / config.import may read or write. Everything here is non-secret.
 * Secrets (code word, whitelist, tokens, Twilio creds, numbers, bodies) and the remote/external-control
 * switches are not in this list, so they can never be exported or imported. The online caller-ID
 * toggle is deliberately excluded too (it would send numbers to a provider).
 */
object ConfigSchema {
    val types: Map<String, Class<*>> = linkedMapOf(
        "assistant.llmUrl" to String::class.java,
        "assistant.llmModel" to String::class.java,
        "assistant.ttsUrl" to String::class.java,
        "lookup.pcUrl" to String::class.java,
    )

    /** Keeps only allowlisted keys with the right type; drops protected keys. */
    fun filter(m: Map<String, Any?>): Map<String, Any?> = m.filter { (k, v) ->
        !ConfigImporter.isProtected(k) && types[k]?.isInstance(v) == true &&
            (v !is String || v.length <= 512)
    }

    /** Snapshot of current values (from a key reader so it is testable without Android). */
    fun snapshot(read: (String) -> Any?): Map<String, Any?> =
        types.keys.associateWith { read(it) }.filterValues { it != null }
}

/** Android-backed reader/writer for [ConfigSchema]. */
class AppConfigStore(context: Context) {
    private val pc = PcSettings(context)
    private val lookup = LookupSettings(context)

    fun snapshot(): Map<String, Any?> = ConfigSchema.snapshot {
        when (it) {
            "assistant.llmUrl" -> pc.llmUrl; "assistant.llmModel" -> pc.llmModel; "assistant.ttsUrl" -> pc.ttsUrl
            "lookup.pcUrl" -> lookup.pcUrl
            else -> null
        }
    }

    fun apply(changes: Map<String, Any?>) {
        ConfigSchema.filter(changes).forEach { (k, v) ->
            when (k) {
                "assistant.llmUrl" -> pc.llmUrl = v as String
                "assistant.llmModel" -> pc.llmModel = v as String
                "assistant.ttsUrl" -> pc.ttsUrl = v as String
                "lookup.pcUrl" -> lookup.pcUrl = v as String
            }
        }
    }
}

enum class ImportState { PENDING, APPLIED, DENIED, EXPIRED }

data class PendingImport(
    val importId: String, val callerPackage: String, val changes: Map<String, Any?>,
    val expiresAtMs: Long, @Volatile var state: ImportState = ImportState.PENDING,
)

/** Live config.export / config.import. Import never applies without an on-device confirmation. */
object ConfigOps {
    const val STATUS_OP = "config.import.status"
    val statusDescriptor = CapabilityDescriptor(STATUS_OP, kind = "query", remoteAllowed = false, requiresConfirm = false)
    private const val CHANNEL = "config_import_approval"
    private val pending = ConcurrentHashMap<String, PendingImport>()

    fun export(packageName: String, snapshot: Map<String, Any?>): JSONObject =
        toJson(ConfigExporter().export(packageName, ConfigSchema.filter(snapshot))) as JSONObject

    sealed class Prepared {
        data class Plan(val changes: Map<String, Any?>) : Prepared()
        data class Rejected(val code: String) : Prepared()
    }

    /** Pure: validates and computes the change set (key -> new value). Applies nothing. */
    fun prepare(current: Map<String, Any?>, payload: Any?, policy: ConflictPolicy): Prepared {
        @Suppress("UNCHECKED_CAST")
        val incoming = (payload as? Map<String, Any?>) ?: return Prepared.Rejected("invalid_parameters")
        if (ForbiddenKeyScanner.findForbidden(incoming) != null) return Prepared.Rejected("invalid_parameters")
        val sandbox = HashMap(current)
        val plan = ConfigImporter { true }.import(sandbox, ConfigSchema.filter(incoming), dryRun = false, policy = policy)
        return Prepared.Plan(plan.changedKeys.associateWith { sandbox[it] })
    }

    /** Pure core of submit: returns the pending record (state PENDING), or null for an empty change set. */
    internal fun submitInternal(callerPackage: String, changes: Map<String, Any?>, ttlMs: Long, nowMs: Long, notify: (PendingImport) -> Unit): PendingImport? {
        sweep(nowMs)
        if (changes.isEmpty()) return null
        val p = PendingImport(UUID.randomUUID().toString(), callerPackage, changes, nowMs + ttlMs)
        pending[p.importId] = p
        notify(p)
        return p
    }

    fun submit(context: Context, callerPackage: String, changes: Map<String, Any?>, ttlMs: Long): PendingImport? =
        submitInternal(callerPackage, changes, ttlMs, System.currentTimeMillis()) { notify(context, it) }

    fun get(importId: String): PendingImport? = pending[importId]

    fun status(callerPackage: String, importId: String, nowMs: Long = System.currentTimeMillis()): ImportState? {
        sweep(nowMs)
        return pending[importId]?.takeIf { it.callerPackage == callerPackage }?.state
    }

    /** Applies via [apply] only if still pending and unexpired. Protected keys are filtered again here. */
    @Synchronized
    internal fun decide(importId: String, approve: Boolean, nowMs: Long, apply: (Map<String, Any?>) -> Unit): ImportState? {
        val p = pending[importId] ?: return null
        if (p.state != ImportState.PENDING) return p.state
        if (nowMs > p.expiresAtMs) { p.state = ImportState.EXPIRED; return p.state }
        if (approve) { apply(ConfigSchema.filter(p.changes)); p.state = ImportState.APPLIED } else p.state = ImportState.DENIED
        return p.state
    }

    fun approve(context: Context, importId: String) {
        decide(importId, true, System.currentTimeMillis()) { AppConfigStore(context).apply(it) }
        NotificationManagerCompat.from(context).cancel(importId.hashCode())
    }

    fun deny(context: Context, importId: String) {
        decide(importId, false, System.currentTimeMillis()) {}
        NotificationManagerCompat.from(context).cancel(importId.hashCode())
    }

    private fun sweep(nowMs: Long) {
        pending.values.forEach { if (it.state == ImportState.PENDING && nowMs > it.expiresAtMs) it.state = ImportState.EXPIRED }
        pending.entries.removeIf { nowMs > it.value.expiresAtMs + ReplayGuard.MAX_TTL_MS }
    }

    private fun notify(context: Context, p: PendingImport) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Config import approvals", NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent(context, ConfirmImportActivity::class.java)
            .putExtra(ConfirmImportActivity.EXTRA_IMPORT_ID, p.importId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, p.importId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("${p.callerPackage} wants to import settings")
            .setContentText("${p.changes.size} setting(s). Tap to review.")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi).setAutoCancel(true)
            .setTimeoutAfter((p.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(1_000L)).build()
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify(p.importId.hashCode(), n)
        }
    }

    // --- JSON <-> Map --------------------------------------------------------
    fun fromJson(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().associateWith { fromJson(v.opt(it)) }
        is JSONArray -> (0 until v.length()).map { fromJson(v.opt(it)) }
        JSONObject.NULL -> null
        else -> v
    }

    fun toJson(v: Any?): Any? = when (v) {
        is Map<*, *> -> JSONObject().apply { v.forEach { (k, x) -> put(k.toString(), toJson(x) ?: JSONObject.NULL) } }
        is List<*> -> JSONArray().apply { v.forEach { put(toJson(it) ?: JSONObject.NULL) } }
        else -> v
    }
}
