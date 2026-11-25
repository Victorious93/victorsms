package com.victorious.comms

import java.util.Locale

/**
 * What a provider label can do (Victorious Framework). The archive stores a provider label per conversation
 * (`sms`, `whatsapp`, `textnow`, ...), but a label that names an *export format* is not a channel VictorSMS can send on.
 * This table is the single place that says so, so a feature such as [Scheduler] cannot queue a message into a
 * conversation that has no way to deliver it.
 *
 * Unknown labels get [Capabilities.NONE]: nothing is assumed about a provider nobody has described.
 */
data class Capabilities(
    val provider: String,
    /** VictorSMS has a sender for this channel. Import-only labels are false. */
    val canSend: Boolean,
    /** Conservative text limit per message; the sender may still split (SMS) or refuse. */
    val maxBodyChars: Int,
    val attachments: Boolean,
    /** The channel reports delivery back (so SENT can become DELIVERED). */
    val deliveryReceipts: Boolean,
    val canImport: Boolean,
) {
    companion object {
        val NONE = Capabilities("", canSend = false, maxBodyChars = 0, attachments = false, deliveryReceipts = false, canImport = false)
    }
}

object Providers {
    private val table: Map<String, Capabilities> = listOf(
        // Android SmsManager splits long text into parts; 1,600 chars is a conservative ceiling, not a protocol limit.
        Capabilities("sms", canSend = true, maxBodyChars = 1_600, attachments = true, deliveryReceipts = true, canImport = true),
        Capabilities("matrix", canSend = true, maxBodyChars = 30_000, attachments = true, deliveryReceipts = false, canImport = false),
        Capabilities("email", canSend = true, maxBodyChars = 100_000, attachments = true, deliveryReceipts = false, canImport = false),
        // Export formats and labels with no sender here. Importing them is fine; sending on them is not possible.
        Capabilities("whatsapp", canSend = false, maxBodyChars = 0, attachments = false, deliveryReceipts = false, canImport = true),
        Capabilities("textnow", canSend = false, maxBodyChars = 0, attachments = false, deliveryReceipts = false, canImport = true),
        Capabilities("paste", canSend = false, maxBodyChars = 0, attachments = false, deliveryReceipts = false, canImport = true),
        Capabilities("zip", canSend = false, maxBodyChars = 0, attachments = false, deliveryReceipts = false, canImport = true),
    ).associateBy { it.provider }

    /** `sms-backup` is the label older imports used for SMS Backup XML; those conversations are SMS. */
    private val aliases = mapOf("sms-backup" to "sms")

    fun of(provider: String): Capabilities {
        val key = provider.trim().lowercase(Locale.ROOT).let { aliases[it] ?: it }
        return table[key] ?: Capabilities.NONE
    }

    fun all(): List<Capabilities> = table.values.sortedBy { it.provider }

    /** Null if [body] can be sent on [provider], otherwise a sentence for the owner. */
    fun refusal(provider: String, body: String): String? {
        val c = of(provider)
        return when {
            !c.canSend -> "VictorSMS cannot send on \"$provider\" conversations; it only holds them as history."
            body.isBlank() -> "The message is empty."
            body.length > c.maxBodyChars -> "The message is ${body.length} characters; $provider allows ${c.maxBodyChars} here."
            else -> null
        }
    }
}
