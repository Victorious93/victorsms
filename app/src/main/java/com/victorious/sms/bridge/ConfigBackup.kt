package com.victorious.sms.bridge

/**
 * config.export / config.import (droidcommand-AI docs/specs/UNIFIED_BACKUP.md, DCA #236).
 * Skeleton only: no live bind, no on-device confirm UI yet.
 */
object ConfigCapabilities {
    const val EXPORT = "config.export"
    const val IMPORT = "config.import"
    val export = CapabilityDescriptor(EXPORT, kind = "action", remoteAllowed = false, requiresConfirm = false)
    val import = CapabilityDescriptor(IMPORT, kind = "action", remoteAllowed = false, requiresConfirm = true)
    val all = listOf(NodeStatusCapability.descriptor, export, import)
}

/** Thrown when a payload contains a forbidden key. Maps to `invalid_parameters`. */
class ForbiddenKeyException(val key: String) :
    IllegalArgumentException("invalid_parameters: forbidden key '$key'")

object ForbiddenKeyScanner {
    /** Case-insensitive substrings, matched against keys with `_`/`-` removed. */
    private val FORBIDDEN = listOf(
        "codeword", "secret", "whitelist", "allowlist",
        "token", "accesstoken", "matrixtoken", "apikey", "bearer",
        "phone", "number", "msisdn", "address",
        "body", "messagebody", "message",
        "password", "passwd", "privatekey", "keystore", "signing", "pem", "cookie",
        "twilio", "authtoken", "numberpool",
    )

    fun isForbidden(key: String): Boolean {
        val k = key.lowercase().replace("_", "").replace("-", "")
        return FORBIDDEN.any { k.contains(it) }
    }

    /** Returns the first forbidden key found anywhere in the tree, or null. */
    fun findForbidden(value: Any?): String? = when (value) {
        is Map<*, *> -> value.entries.firstNotNullOfOrNull { (k, v) ->
            val key = k.toString()
            if (isForbidden(key)) key else findForbidden(v)
        }
        is Iterable<*> -> value.firstNotNullOfOrNull { findForbidden(it) }
        else -> null
    }

    fun requireClean(value: Any?) {
        findForbidden(value)?.let { throw ForbiddenKeyException(it) }
    }
}

class ConfigExporter(private val schemaVersion: Int = 1) {
    /** Builds the node entry; refuses (throws) if any forbidden key is present. */
    fun export(packageName: String, settings: Map<String, Any?>): Map<String, Any?> {
        ForbiddenKeyScanner.requireClean(settings)
        return mapOf(
            "packageName" to packageName,
            "schemaVersion" to schemaVersion,
            "payload" to settings,
        )
    }
}

enum class ConflictPolicy(val wire: String) {
    SKIP("skip"), OVERWRITE("overwrite"), MERGE_BY_ID("merge-by-id");
    companion object {
        fun fromWire(s: String?): ConflictPolicy =
            entries.firstOrNull { it.wire == s } ?: SKIP
    }
}

data class ImportPlan(val applied: Boolean, val changedKeys: List<String>)

class ConfigImporter(private val confirmOnDevice: () -> Boolean) {
    fun import(
        current: MutableMap<String, Any?>,
        incoming: Map<String, Any?>,
        dryRun: Boolean,
        policy: ConflictPolicy = ConflictPolicy.SKIP,
    ): ImportPlan {
        ForbiddenKeyScanner.requireClean(incoming)
        // Never auto-enable remote command / external control from an import.
        val filtered = incoming.filterKeys { !it.startsWith("remote.command") && it != "externalControlEnabled" }
        val changes = filtered.keys.filter { key ->
            when (policy) {
                ConflictPolicy.SKIP -> key !in current
                ConflictPolicy.OVERWRITE, ConflictPolicy.MERGE_BY_ID -> current[key] != filtered[key]
            }
        }
        if (dryRun || changes.isEmpty()) return ImportPlan(false, changes)
        if (!confirmOnDevice()) return ImportPlan(false, changes)
        changes.forEach { current[it] = filtered[it] }
        return ImportPlan(true, changes)
    }
}
