package com.victorious.sms.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BridgeDetectorTest {
    @Test fun stateEventProtocolWins() {
        val tg = """{"bridgebot":"@telegrambot:hs","protocol":{"id":"telegram","displayname":"Telegram"}}"""
        assertEquals(MessageSource.TELEGRAM, BridgeDetector.resolve(listOf(tg), listOf("@whatsappbot:hs"), listOf("@signal_1:hs")))
        assertEquals(MessageSource.SIGNAL, BridgeDetector.fromStateContent(listOf("""{"protocol":{"id":"signal"}}""")))
        assertEquals(MessageSource.WHATSAPP, BridgeDetector.fromStateContent(listOf("""{"protocol":{"id":"whatsapp"}}""")))
    }

    @Test fun stateEventBridgebotWhenNoProtocol() =
        assertEquals(MessageSource.SIGNAL, BridgeDetector.fromStateContent(listOf("""{"bridgebot":"@signalbot:hs"}""")))

    @Test fun badStateIgnored() {
        assertNull(BridgeDetector.fromStateContent(listOf("not json", """{"protocol":{"id":"irc"}}""")))
    }

    @Test fun botMembershipBeatsPrefix() {
        assertEquals(MessageSource.WHATSAPP, BridgeDetector.resolve(emptyList(), listOf("@don:hs", "@whatsappbot:hs"), listOf("@telegram_9:hs")))
        // A puppet-looking member is not the bot: membership check alone does not match it.
        assertNull(BridgeDetector.fromBotMembership(listOf("@telegram_123:hs", "@signalfan:hs")))
    }

    @Test fun prefixFallbackKept() {
        assertEquals(MessageSource.TELEGRAM, BridgeDetector.resolve(emptyList(), emptyList(), listOf("@telegram_42:hs")))
        assertEquals(MessageSource.MATRIX, BridgeDetector.resolve(emptyList(), listOf("@alice:hs"), listOf("@bob:hs")))
    }
}
