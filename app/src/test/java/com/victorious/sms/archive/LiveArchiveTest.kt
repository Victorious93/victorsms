package com.victorious.sms.archive

import androidx.test.core.app.ApplicationProvider
import com.victorious.comms.Author
import com.victorious.comms.Direction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Victorious Framework: the opt-in hook that copies live texts into the archive, on Android's SQLite under Robolectric.
 * The SMS receiver and sender call sites themselves are NOT exercised here (they need a real telephony stack).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveArchiveTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val saved = LiveArchive.executor
    private val none: () -> Set<String> = { emptySet() }

    @Before fun fresh() {
        ctx.getDatabasePath(ArchiveRuntime.DB_NAME).delete()
        ArchiveRuntime.resetForTests()
        LiveArchive.resetForTests(ctx) // the shipped default, not a value the test chose
        LiveArchive.executor = Executor { it.run() } // run inline so the test can look straight away
    }

    @After fun restore() { LiveArchive.executor = saved }

    private val archive get() = ArchiveRuntime.get(ctx).archive

    @Test fun nothingIsRecordedUntilTheOwnerTurnsItOnButTheCallerIsStillReleased() {
        var released = 0
        LiveArchive.recordIncoming(ctx, "+15550100101", "hello", 1_000L, none) { released++ }
        LiveArchive.recordOutgoing(ctx, "+15550100101", "hi", 2_000L, Author.OWNER, none)
        assertEquals(1, released)
        assertEquals(0, archive.conversations().size)
        assertFalse(LiveArchive.isEnabled(ctx))
    }

    @Test fun onceOnReceivedAndSentTextsLandInOneConversationNobodyCanSeeYet() {
        LiveArchive.setEnabled(ctx, true)
        assertTrue(LiveArchive.isEnabled(ctx))
        var released = 0
        LiveArchive.recordIncoming(ctx, "+1 (555) 010-0101", "Dinner at 7?", 1_000L, none) { released++ }
        LiveArchive.recordOutgoing(ctx, "5550100101", "yes!", 2_000L, Author.OWNER, none)
        assertEquals(1, released)
        val c = archive.conversations().single()
        assertEquals("sms", c.provider)
        assertFalse(c.assistantAccess)
        assertEquals(listOf("Dinner at 7?", "yes!"), archive.messages(c.id, 10).map { it.body })
        assertEquals(listOf(Direction.IN, Direction.OUT), archive.messages(c.id, 10).map { it.direction })
        assertTrue(archive.messages(c.id, 10).all { it.origin == "live" })
    }

    @Test fun aTextWithTheFleetCodeWordIsNeverRecordedInEitherDirection() {
        LiveArchive.setEnabled(ctx, true)
        val secret = { setOf("opensesame") }
        LiveArchive.recordIncoming(ctx, "+15550100101", "lights on opensesame", 1_000L, secret)
        LiveArchive.recordOutgoing(ctx, "+15550100101", "use opensesame please", 2_000L, Author.OWNER, secret)
        assertEquals(0, archive.conversations().size)
        LiveArchive.recordIncoming(ctx, "+15550100101", "an ordinary text", 3_000L, secret)
        assertEquals(1, archive.conversations().size)
    }

    @Test fun anEmptyCodeWordNeverBlocksRecording() {
        LiveArchive.setEnabled(ctx, true)
        LiveArchive.recordIncoming(ctx, "+15550100101", "hello", 1_000L, { setOf("") })
        assertEquals(1, archive.conversations().size)
    }

    @Test fun ifTheCodeWordCannotBeReadNothingIsRecordedAndNothingThrows() {
        LiveArchive.setEnabled(ctx, true)
        var released = 0
        LiveArchive.recordIncoming(ctx, "+15550100101", "hello", 1_000L, { error("keystore unavailable") }) { released++ }
        LiveArchive.recordOutgoing(ctx, "+15550100101", "hi", 2_000L, Author.OWNER, { error("keystore unavailable") })
        assertEquals("the caller is still released", 1, released)
        assertEquals(0, archive.conversations().size)
    }

    @Test fun aSendTextedByABotIsNeverRecordedAsTheOwnersWords() {
        LiveArchive.setEnabled(ctx, true)
        LiveArchive.recordOutgoing(ctx, "+15550100101", "omw", 1_000L, Author.OWNER, none)
        LiveArchive.recordOutgoing(ctx, "+15550100101", "I will arrive at precisely five o'clock.", 2_000L, Author.BOT_APPROVED, none)
        assertEquals(listOf("omw"), archive.outgoingForStyle().map { it.body })
        assertEquals(listOf("owner", "bot_approved"), archive.messages(archive.conversations().single().id, 10).map { it.author })
    }

    @Test fun aBrokenExecutorStillReleasesTheCallerAndNeverThrows() {
        LiveArchive.setEnabled(ctx, true)
        LiveArchive.executor = Executor { throw RejectedExecutionException("shut down") }
        var released = 0
        LiveArchive.recordIncoming(ctx, "+15550100101", "hello", 1_000L, none) { released++ }
        assertEquals(1, released)
    }
}
