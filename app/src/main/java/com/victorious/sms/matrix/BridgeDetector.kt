package com.victorious.sms.matrix

import org.json.JSONObject

/** Where a conversation comes from; shown as a badge in the unified inbox. */
enum class MessageSource(val label: String) {
    SMS("SMS"), MATRIX("Matrix"), TELEGRAM("Telegram"), SIGNAL("Signal"), WHATSAPP("WhatsApp"),
    MESSENGER("Messenger"), INSTAGRAM("Instagram"), DISCORD("Discord"), GMESSAGES("Google Messages"),
}

/**
 * Detects mautrix-bridged rooms, strongest signal first:
 *  1. `m.bridge` / `uk.half-shot.bridge` state event content (`protocol.id`, MSC2346);
 *  2. the bridge bot is a member of the room (exact localpart, e.g. @telegrambot:server);
 *  3. fallback: puppet/bot localpart prefixes among the room heroes (@whatsapp_123:server).
 */
object BridgeDetector {
    val STATE_EVENT_TYPES = listOf("m.bridge", "uk.half-shot.bridge")

    private val names = listOf(
        "whatsapp" to MessageSource.WHATSAPP,
        "telegram" to MessageSource.TELEGRAM,
        "signal" to MessageSource.SIGNAL,
        "instagram" to MessageSource.INSTAGRAM,
        "messenger" to MessageSource.MESSENGER,
        "facebook" to MessageSource.MESSENGER,
        "discord" to MessageSource.DISCORD,
        "gmessages" to MessageSource.GMESSAGES,
    )

    private fun byName(id: String): MessageSource? {
        val s = id.lowercase()
        return names.firstOrNull { (n, _) -> s == n || s.startsWith(n) }?.second
    }

    /** From raw `content` JSON of bridge state events. Uses protocol.id, then bridgebot localpart. */
    fun fromStateContent(contents: List<String>): MessageSource? {
        for (c in contents) {
            val o = runCatching { JSONObject(c) }.getOrNull() ?: continue
            o.optJSONObject("protocol")?.optString("id")?.takeIf { it.isNotBlank() }?.let { byName(it) }?.let { return it }
            o.optString("bridgebot").takeIf { it.isNotBlank() }?.let { fromBotMembership(listOf(it)) }?.let { return it }
        }
        return null
    }

    /** True bridge bot membership: localpart is exactly `<network>bot` (mautrix default bot names). */
    fun fromBotMembership(memberIds: List<String>): MessageSource? {
        for (uid in memberIds) {
            val local = uid.removePrefix("@").substringBefore(":").lowercase()
            names.firstOrNull { (n, _) -> local == n + "bot" }?.let { return it.second }
        }
        return null
    }

    /** Fallback heuristic (kept from the original): `<network>_...` puppets or `<network>bot`. */
    fun detect(userIds: List<String>): MessageSource {
        for (uid in userIds) {
            val local = uid.removePrefix("@").substringBefore(":").lowercase()
            names.firstOrNull { (p, _) -> local.startsWith(p + "_") || local == p + "bot" }?.let { return it.second }
        }
        return MessageSource.MATRIX
    }

    fun resolve(stateContents: List<String>, memberIds: List<String>, heroIds: List<String>): MessageSource =
        fromStateContent(stateContents) ?: fromBotMembership(memberIds) ?: detect(heroIds)
}
