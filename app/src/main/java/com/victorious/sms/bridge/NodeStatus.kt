package com.victorious.sms.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony

/** One domain row in a node status snapshot (STATUS_TRAY.md §2). */
data class StatusDomain(
    val name: String,
    val state: String,
    val detail: String? = null,
) {
    init {
        require(detail == null || detail.length <= 200) { "detail max 200 chars" }
    }
}

/** Frozen fleet status snapshot. Never carries bodies, numbers, code word or whitelist. */
data class NodeStatusSnapshot(
    val nodeId: String,
    val packageName: String?,
    val connectionClass: String,
    val updatedAtMs: Long,
    val domains: List<StatusDomain>,
)

/** Capability descriptor advertised in capabilities.get. */
data class CapabilityDescriptor(
    val name: String,
    val kind: String,
    val remoteAllowed: Boolean,
    val requiresConfirm: Boolean,
)

object NodeStatusCapability {
    const val OP = "node.status.get"
    val descriptor = CapabilityDescriptor(
        name = OP,
        kind = "read",
        remoteAllowed = true,
        requiresConfirm = false,
    )
}

/** Builds this node's snapshot for node.status.get. */
class NodeStatusProvider(
    private val context: Context,
    private val nodeId: String,
) {
    fun snapshot(nowMs: Long = System.currentTimeMillis()): NodeStatusSnapshot =
        NodeStatusSnapshot(
            nodeId = nodeId,
            packageName = context.packageName,
            connectionClass = "EXTERNAL_NODE",
            updatedAtMs = nowMs,
            domains = listOf(smsDomain(), StatusDomain("node_health", "ok")),
        )

    /** Ready to deliver, not authorization. */
    private fun smsDomain(): StatusDomain {
        val isDefault = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        val perms = listOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
        ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return when {
            isDefault && perms -> StatusDomain("sms", "ok")
            isDefault || perms -> StatusDomain("sms", "degraded", "default SMS role or permissions missing")
            else -> StatusDomain("sms", "down", "not default SMS app")
        }
    }
}
