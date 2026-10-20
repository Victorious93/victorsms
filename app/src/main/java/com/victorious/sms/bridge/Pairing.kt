package com.victorious.sms.bridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/** Op groups a paired app may be granted. Nothing is granted by default. */
enum class OpGroup { READ, SEND }

/** Which group an op belongs to; null = not exposed to paired apps. */
fun opGroupOf(op: String): OpGroup? = when (op) {
    "capabilities.get", NodeStatusCapability.OP, "sms.list_threads", "sms.read_thread", "sms.status" -> OpGroup.READ
    "sms.send", "sms.send.status" -> OpGroup.SEND
    else -> null
}

/** Identity of a caller, derived from the system (never from the payload). */
data class CallerIdentity(val packageName: String, val certSha256: String)

data class PairedApp(
    val packageName: String,
    val certSha256: String,
    val approved: Boolean,
    val groups: Set<OpGroup>,
)

/** Resolves Message.sendingUid to package + signing cert SHA-256 via PackageManager. */
object CallerVerifier {
    fun identify(context: Context, uid: Int): List<CallerIdentity> {
        val pm = context.packageManager
        val pkgs = pm.getPackagesForUid(uid) ?: return emptyList()
        return pkgs.mapNotNull { pkg -> certSha256(pm, pkg)?.let { CallerIdentity(pkg, it) } }
    }

    @Suppress("DEPRECATION")
    private fun certSha256(pm: PackageManager, pkg: String): String? = runCatching {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                ?: return null
            if (info.hasMultipleSigners()) return null // keep identity unambiguous
            info.signingCertificateHistory
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = sigs?.lastOrNull() ?: return null
        MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }.getOrNull()
}

/**
 * Encrypted registry of fleet apps keyed by package + cert SHA-256.
 * Unknown callers are recorded as pending (not approved, no groups) for Don to review.
 */
class PairingRegistry(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "paired_apps",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private fun k(pkg: String, cert: String) = "$pkg|$cert"

    fun all(): List<PairedApp> = prefs.all.keys.mapNotNull { key ->
        val parts = key.split("|")
        if (parts.size != 2) return@mapNotNull null
        val v = prefs.getString(key, "") ?: ""
        val groups = v.substringAfter(";", "").split(",").filter { it.isNotBlank() }
            .mapNotNull { runCatching { OpGroup.valueOf(it) }.getOrNull() }.toSet()
        PairedApp(parts[0], parts[1], v.startsWith("1"), groups)
    }.sortedBy { it.packageName }

    fun find(id: CallerIdentity): PairedApp? =
        all().firstOrNull { it.packageName == id.packageName && it.certSha256 == id.certSha256 }

    fun recordPending(id: CallerIdentity) {
        val key = k(id.packageName, id.certSha256)
        if (!prefs.contains(key)) prefs.edit().putString(key, "0;").apply()
    }

    fun set(app: PairedApp) {
        prefs.edit().putString(
            k(app.packageName, app.certSha256),
            (if (app.approved) "1" else "0") + ";" + app.groups.joinToString(","),
        ).apply()
    }

    fun revoke(app: PairedApp) = set(app.copy(approved = false, groups = emptySet()))
    fun forget(app: PairedApp) = prefs.edit().remove(k(app.packageName, app.certSha256)).apply()
}

/** External-control master switch. OFF by default. */
class BridgeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_settings", Context.MODE_PRIVATE)
    var externalControlEnabled: Boolean
        get() = prefs.getBoolean("externalControlEnabled", false)
        set(v) = prefs.edit().putBoolean("externalControlEnabled", v).apply()
}

sealed class AuthDecision {
    data class Allowed(val caller: CallerIdentity, val needsUserConfirm: Boolean) : AuthDecision()
    data class Denied(val code: String) : AuthDecision()
}

/** Decision logic for an inbound op from a uid. */
class BridgeAuthorizer(private val context: Context) {
    private val registry = PairingRegistry(context)
    private val settings = BridgeSettings(context)

    fun authorize(uid: Int, op: String): AuthDecision {
        if (!settings.externalControlEnabled) return AuthDecision.Denied("external_control_disabled")
        val ids = CallerVerifier.identify(context, uid)
        if (ids.isEmpty()) return AuthDecision.Denied("not_paired")
        val app = ids.firstNotNullOfOrNull { registry.find(it) }
        if (app == null || !app.approved) {
            ids.forEach { registry.recordPending(it) }
            return AuthDecision.Denied("not_paired")
        }
        val group = opGroupOf(op) ?: return AuthDecision.Denied("unsupported_operation")
        if (group !in app.groups) return AuthDecision.Denied("permission_denied")
        return AuthDecision.Allowed(
            CallerIdentity(app.packageName, app.certSha256),
            needsUserConfirm = op == "sms.send",
        )
    }
}
