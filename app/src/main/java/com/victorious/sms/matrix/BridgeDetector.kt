package com.victorious.sms.matrix

/** Where a conversation comes from; shown as a badge in the unified inbox. */
enum class MessageSource(val label: String) {
    SMS("SMS"), MATRIX("Matrix"), TELEGRAM("Telegram"), SIGNAL("Signal"), WHATSAPP("WhatsApp"),
    MESSENGER("Messenger"), INSTAGRAM("Instagram"), DISCORD("Discord"), GMESSAGES("Google Messages"),
}

/**
 * Detects mautrix-bridged rooms from member user ids (bridge bots and puppets use fixed
 * localpart prefixes by default, e.g. @whatsapp_123:server, @telegrambot:server).
 * Heuristic: reading m.bridge / uk.half-shot.bridge state is a follow-up.
 */
object BridgeDetector {
    private val prefixes = listOf(
        "whatsapp" to MessageSource.WHATSAPP,
        "telegram" to MessageSource.TELEGRAM,
        "signal" to MessageSource.SIGNAL,
        "instagram" to MessageSource.INSTAGRAM,
        "messenger" to MessageSource.MESSENGER,
        "facebook" to MessageSource.MESSENGER,
        "discord" to MessageSource.DISCORD,
        "gmessages" to MessageSource.GMESSAGES,
    )

    fun detect(userIds: List<String>): MessageSource {
        for (uid in userIds) {
            val local = uid.removePrefix("@").substringBefore(":").lowercase()
            prefixes.firstOrNull { (p, _) -> local.startsWith(p + "_") || local == p + "bot" }?.let { return it.second }
        }
        return MessageSource.MATRIX
    }
}
