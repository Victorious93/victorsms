package com.victorious.sms.bridge

import com.victorious.comms.ArchiveWire
import org.json.JSONObject

/**
 * Victorious Framework: translation between droidcommand-AI's Messenger frames (`MessengerWire` / `BridgeRequest` /
 * `BridgeResponse`) and this app's internal op frame (`{"protocol_major","id","op","params"}`). Pure and unit-tested,
 * because the other side validates every reply strictly and rejects it as a whole when a field is missing:
 *
 *  - the reply must carry `node_id`, equal to the `target_node_id` the request named. It is a **correlation echo**, not
 *    an identity proof. Identity on this transport is the OS: the caller is `Message.sendingUid` resolved to a package
 *    and signing certificate here, and the caller binds only a package whose certificate it paired.
 *  - an error must be `{"category","message"}`; `code` is kept as well for older readers.
 */
object DcaFrames {
    private val NODE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

    class Request(
        /** The internal frame handed to the op dispatcher. */
        val legacy: JSONObject,
        /** The id to echo back as `request_id`. */
        val requestId: String,
        /** The `target_node_id` to echo back as `node_id`, or null when the request carried none or it was malformed. */
        val targetNodeId: String?,
    )

    fun parseRequest(json: String?): Request {
        val req = runCatching { JSONObject(json.orEmpty()) }.getOrNull()
        val requestId = req?.optString("request_id").orEmpty()
        val op = req?.optString("operation").orEmpty()
        val params = req?.optJSONObject("parameters") ?: JSONObject()
        // Existing ops read their correlation id from params; the archive ops take strict parameter lists and need no id.
        if (!ArchiveWire.isArchiveOp(op) && !params.has("requestId")) params.put("requestId", requestId)
        val legacy = JSONObject()
            .put("protocol_major", req?.optInt("protocol_major", -1) ?: -1)
            .put("id", requestId)
            .put("op", op)
            .put("params", params)
        val target = req?.optString("target_node_id").orEmpty().takeIf { NODE_ID.matches(it) }
        return Request(legacy, requestId, target)
    }

    /** The reply the other side expects, from an internal reply `{"id","ok","result"?,"error"?}`. */
    fun response(request: Request, internal: JSONObject): JSONObject {
        val ok = internal.optBoolean("ok")
        val out = JSONObject()
            .put("protocol_major", 1)
            .put("request_id", internal.optString("id").ifEmpty { request.requestId })
            .put("ok", ok)
        request.targetNodeId?.let { out.put("node_id", it) }
        if (ok && internal.has("result")) out.put("result", internal.get("result"))
        if (!ok) {
            val code = internal.optString("error").ifEmpty { "error" }
            out.put("error", JSONObject().put("category", code).put("message", internal.optString("message").ifEmpty { describe(code) }).put("code", code))
        }
        return out
    }

    fun describe(code: String): String = when (code) {
        "external_control_disabled" -> "External control is switched off in VictorSMS."
        "not_paired" -> "This app is not approved in VictorSMS under Connected apps."
        "permission_denied" -> "This app has not been granted that permission in VictorSMS."
        "unsupported_operation" -> "VictorSMS does not offer that operation to paired apps."
        "unsupported_protocol" -> "VictorSMS speaks protocol 1 only."
        "invalid_frame" -> "The request could not be read."
        "invalid_parameters" -> "A parameter was missing, unknown or out of range."
        "not_found" -> "No such conversation is shared with the assistant."
        "not_implemented" -> "VictorSMS does not implement that operation."
        else -> "VictorSMS could not complete the request ($code)."
    }
}
