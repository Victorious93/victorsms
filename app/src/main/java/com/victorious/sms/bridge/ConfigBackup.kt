package com.victorious.sms.bridge

/**
 * config.export / config.import (droidcommand-AI docs/specs/UNIFIED_BACKUP.md, DCA #236).
 * Live ops and the on-device confirm screen are in ConfigOps / ConfirmImportActivity.
 */
object ConfigCapabilities {
    const val EXPORT = "config.export"
    const val IMPORT = "config.import"
    val export = CapabilityDescriptor(EXPORT, kind = "action", remoteAllowed = false, requiresConfirm = false)
    val import = CapabilityDescriptor(IMPORT, kind = "action", remoteAllowed = false, requiresConfirm = true)
    val all = listOf(NodeStatusCapability.descriptor, export, import, ConfigOps.statusDescriptor)
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
    companion object {
        /** Keys an import may never set: remote command and external control stay as they are on the device. */
        fun isProtected(key: String): Boolean =
            key.startsWith("remote.command") || key.startsWith("remote_command") || key == "externalControlEnabled"

        /** Removes protected keys at every depth. */
        fun stripProtected(v: Any?): Any? = when (v) {
            is Map<*, *> -> v.entries.filter { !isProtected(it.key.toString()) }
                .associate { it.key.toString() to stripProtected(it.value) }
            is List<*> -> v.map { stripProtected(it) }
            else -> v
        }

        private fun idOf(v: Any?): Any? = (v as? Map<*, *>)?.get("id")

        /**
         * merge-by-id: lists whose elements are all maps with an "id" are merged element-wise (incoming
         * wins per id, current-only ids kept, order: current first then new ids). Maps merge key-wise.
         * Anything else: incoming replaces current.
         */
        fun mergeById(current: Any?, incoming: Any?): Any? = when {
            current is Map<*, *> && incoming is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>()
                current.forEach { (k, v) -> out[k.toString()] = v }
                incoming.forEach { (k, v) -> out[k.toString()] = mergeById(current[k], v) }
                out
            }
            current is List<*> && incoming is List<*> &&
                (current + incoming).all { idOf(it) != null } -> {
                val out = LinkedHashMap<Any?, Any?>()
                current.forEach { out[idOf(it)] = it }
                incoming.forEach { out[idOf(it)] = mergeById(out[idOf(it)], it) }
                out.values.toList()
            }
            else -> incoming
        }
    }

    fun import(
        current: MutableMap<String, Any?>,
        incoming: Map<String, Any?>,
        dryRun: Boolean,
        policy: ConflictPolicy = ConflictPolicy.SKIP,
    ): ImportPlan {
        ForbiddenKeyScanner.requireClean(incoming)
        // Never auto-enable remote command / external control from an import (at any depth).
        @Suppress("UNCHECKED_CAST")
        val filtered = stripProtected(incoming) as Map<String, Any?>
        val next = filtered.mapValues { (k, v) ->
            if (policy == ConflictPolicy.MERGE_BY_ID && k in current) mergeById(current[k], v) else v
        }
        val changes = next.keys.filter { key ->
            when (policy) {
                ConflictPolicy.SKIP -> key !in current
                ConflictPolicy.OVERWRITE, ConflictPolicy.MERGE_BY_ID -> current[key] != next[key]
            }
        }
        if (dryRun || changes.isEmpty()) return ImportPlan(false, changes)
        if (!confirmOnDevice()) return ImportPlan(false, changes)
        changes.forEach { current[it] = next[it] }
        return ImportPlan(true, changes)
    }
}
