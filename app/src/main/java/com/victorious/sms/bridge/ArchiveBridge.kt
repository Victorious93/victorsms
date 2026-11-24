package com.victorious.sms.bridge

import com.victorious.comms.Archive
import com.victorious.comms.ArchiveOps
import com.victorious.comms.ArchiveReply
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/**
 * Victorious Framework: carries one authorised archive op from the bridge's JSON to [ArchiveOps] and back. Authorisation
 * (External control, approved app, `ARCHIVE_READ` group) has already happened in [PairingPolicy]; the per-conversation
 * switch is enforced inside [ArchiveOps]. This only translates, and never lets an exception reach the caller.
 */
object ArchiveBridge {
    fun reply(archive: Archive, id: String, caller: String, op: String, params: JSONObject): JSONObject {
        val args = runCatching { Json.parseToJsonElement(params.toString()) as JsonObject }.getOrNull()
            ?: return JSONObject().put("id", id).put("ok", false).put("error", "invalid_parameters")
        return try {
            when (val r = ArchiveOps(archive).call(caller, op, args)) {
                is ArchiveReply.Ok -> JSONObject().put("id", id).put("ok", true).put("result", JSONObject(r.result.toString()))
                is ArchiveReply.Error -> JSONObject().put("id", id).put("ok", false).put("error", r.code).put("message", r.message)
            }
        } catch (_: Exception) {
            JSONObject().put("id", id).put("ok", false).put("error", "internal_error")
        }
    }
}
