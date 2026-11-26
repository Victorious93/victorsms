package com.victorious.sms.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject

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

/** The wire form of a snapshot. States and short fixed details only; never message text, numbers, the code word or the whitelist. */
fun NodeStatusSnapshot.toJson(): org.json.JSONObject = org.json.JSONObject()
    .put("nodeId", nodeId)
    .put("packageName", packageName ?: org.json.JSONObject.NULL)
    .put("connectionClass", connectionClass)
    .put("updatedAtMs", updatedAtMs)
    .put(
        "domains",
        org.json.JSONArray().also { arr ->
            domains.forEach { d ->
                arr.put(org.json.JSONObject().put("name", d.name).put("state", d.state).also { o -> d.detail?.let { o.put("detail", it) } })
            }
        },
    )

/** Capability descriptor advertised in capabilities.get. */
data class CapabilityDescriptor(
    val name: String,
    val kind: String,
    val remoteAllowed: Boolean,
    val requiresConfirm: Boolean,
)

/**
 * The ops that read this node's own state. The authorizer ([opGroupOf]) and the dispatcher (`BridgeService`) both use these
 * sets, so an op cannot be allowed by one and unknown to the other. droidcommand-AI's fleet screens ask for `sms.status`
 * and `sms.capabilities.get`; the frozen remote-control names are `node.status.get` and `capabilities.get`.
 */
object BridgeReads {
    val CAPABILITIES: Set<String> = setOf("capabilities.get", "sms.capabilities.get")
    val STATUS: Set<String> = setOf("node.status.get", "sms.status")

    /**
     * Every op this node answers. The archive ops are listed so a paired assistant can discover them; listing is not access:
     * each one still needs the app's ARCHIVE_READ grant and the owner's per-conversation switch (all reads, no confirm tap).
     */
    fun descriptors(): List<CapabilityDescriptor> = listOf(
        NodeStatusCapability.descriptor, SmsSendCapability.descriptor, SmsSendCapability.statusDescriptor, DraftCapability.deliver,
        DraftCapability.fromArchive, DraftCapability.status, ConfigCapabilities.export, ConfigCapabilities.import, ConfigOps.statusDescriptor,
    ) + com.victorious.comms.ArchiveWire.OPS.map { CapabilityDescriptor(it, kind = "read", remoteAllowed = true, requiresConfirm = false) }

    /** The internal reply for a status or capabilities read, or null when [op] is not one of them. Never throws for those ops. */
    fun answer(context: Context, id: String, op: String): JSONObject? = when (op) {
        in STATUS -> reply(id, NodeStatusProvider(context, com.victorious.sms.fleet.NodeId.get(context)).snapshot().toJson())
        in CAPABILITIES -> reply(
            id,
            JSONObject().put(
                "ops",
                JSONArray().apply {
                    descriptors().forEach {
                        put(JSONObject().put("name", it.name).put("kind", it.kind).put("remoteAllowed", it.remoteAllowed).put("requiresConfirm", it.requiresConfirm))
                    }
                },
            ),
        )
        else -> null
    }

    private fun reply(id: String, result: JSONObject) = JSONObject().put("id", id).put("ok", true).put("result", result)
}

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
