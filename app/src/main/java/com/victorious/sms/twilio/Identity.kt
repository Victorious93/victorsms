package com.victorious.sms.twilio

/**
 * Who a message or call is sent as. Pure Kotlin (no Android) so it is unit-testable.
 * An alias is always a REAL number Don owns in his own Twilio account. Never spoofing.
 */
sealed class Identity {
    object Sim : Identity() { override fun toString() = "sim" }
    data class Alias(val number: String) : Identity() { override fun toString() = "alias:$number" }

    fun encode(): String = toString()

    companion object {
        fun decode(s: String?): Identity? = when {
            s == null -> null
            s == "sim" -> Sim
            s.startsWith("alias:") && s.length > 6 -> Alias(s.removePrefix("alias:"))
            else -> null
        }
    }
}

object PhoneNumbers {
    /** Light normalisation for comparison: keep digits and a leading '+'. Not a full E.164 parser. */
    fun normalize(n: String): String {
        val t = n.trim()
        val digits = t.filter(Char::isDigit)
        return if (t.startsWith("+")) "+$digits" else digits
    }
    fun isE164(n: String): Boolean = Regex("^\\+[1-9][0-9]{6,14}$").matches(n.trim())
    fun same(a: String, b: String) = normalize(a).isNotEmpty() && normalize(a) == normalize(b)
}

object IdentityResolver {
    /**
     * Thread override wins, then the default, then SIM. An alias that is not (or no longer) one of
     * Don's configured aliases falls back to SIM rather than sending from an unknown number.
     */
    fun resolve(threadChoice: Identity?, default: Identity?, aliases: Collection<String>): Identity {
        fun valid(i: Identity?): Identity? = when (i) {
            null -> null
            Identity.Sim -> Identity.Sim
            is Identity.Alias -> aliases.firstOrNull { PhoneNumbers.same(it, i.number) }?.let { Identity.Alias(it) }
        }
        return valid(threadChoice) ?: valid(default) ?: Identity.Sim
    }

    /** Short label for the composer, e.g. "SIM" or "Alias …4567". Masks all but the last 4 digits. */
    fun label(i: Identity): String = when (i) {
        Identity.Sim -> "SIM (your real number)"
        is Identity.Alias -> "Alias …" + PhoneNumbers.normalize(i.number).takeLast(4)
    }
}

class NotOwnedNumberException : IllegalArgumentException("From-number is not one of your Twilio numbers; refused.")

object FromNumberGuard {
    /**
     * [owned] must be the list freshly fetched from Twilio (IncomingPhoneNumbers). Anything not in
     * it is rejected, so the app can never send or call "from" a number Don does not own.
     */
    fun check(from: String, owned: Collection<String>): Result<String> {
        if (!PhoneNumbers.isE164(PhoneNumbers.normalize(from))) return Result.failure(NotOwnedNumberException())
        val match = owned.firstOrNull { PhoneNumbers.same(it, from) } ?: return Result.failure(NotOwnedNumberException())
        return Result.success(match)
    }
}

object TwiML {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    /** Bridge: when Don answers, dial the callee showing Don's owned alias as caller ID. */
    fun bridge(aliasCallerId: String, callee: String): String =
        "<Response><Say>Connecting your masked call.</Say><Dial callerId=\"${esc(aliasCallerId)}\"><Number>${esc(callee)}</Number></Dial></Response>"
}
