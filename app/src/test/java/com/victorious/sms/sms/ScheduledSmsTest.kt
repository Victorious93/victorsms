package com.victorious.sms.sms

import android.app.AlarmManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.victorious.comms.Archive
import com.victorious.comms.Direction
import com.victorious.comms.LiveIngest
import com.victorious.comms.SchedState
import com.victorious.comms.Scheduler
import com.victorious.sms.archive.AndroidSqlDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Victorious Framework: the Android side of scheduled texts, on Android's SQLite under Robolectric. The real SMS send and
 * a real alarm firing are NOT exercised (no telephony stack, no device); the sender is a fake and the alarm is only inspected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScheduledSmsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private var now = System.currentTimeMillis()
    private val archive = Archive(AndroidSqlDb(SQLiteDatabase.create(null)), clock = { now })
    private val scheduler = Scheduler(archive)
    private val sent = ArrayList<Pair<String, String>>()
    private val savedSender = ScheduledSms.sender
    private val min = 60_000L

    @Before fun fresh() {
        ScheduledSms.resetForTests()
        ScheduledSms.sender = { _, to, body -> sent.add(to to body); true }
    }

    @After fun restore() { ScheduledSms.sender = savedSender }

    private fun conversation(address: String = "+1 (555) 010-0142"): Long {
        LiveIngest(archive).record(address, "hello", now, Direction.IN)
        return archive.conversations().first { it.title == address }.id
    }

    private fun run() = ScheduledSms.runDue(ctx, scheduler) { archive.smsRecipient(it) }

    @Test fun aDueTextGoesToTheConversationsNumberExactlyOnce() {
        val c = conversation()
        val m = scheduler.schedule(c, "running late", now + 5 * min)
        now += 6 * min
        assertEquals(1, run())
        assertEquals(listOf("+1 (555) 010-0142" to "running late"), sent)
        assertEquals(SchedState.SENT, scheduler.get(m.uid)!!.state)
        assertEquals("a second run has nothing left to send", 0, run())
        assertEquals(1, sent.size)
    }

    @Test fun nothingIsSentBeforeItsTime() {
        scheduler.schedule(conversation(), "later", now + 30 * min)
        now += 10 * min
        assertEquals(0, run())
        assertTrue(sent.isEmpty())
    }

    @Test fun whenAndroidRefusesTheTextItIsMarkedFailedWithAnHonestReason() {
        ScheduledSms.sender = { _, _, _ -> false }
        val m = scheduler.schedule(conversation(), "x", now + 5 * min)
        now += 6 * min
        assertEquals(0, run())
        val after = scheduler.get(m.uid)!!
        assertEquals(SchedState.FAILED, after.state)
        assertTrue(after.detail.contains("Check the thread"))
    }

    @Test fun aSenderThatThrowsIsAFailureNotACrash() {
        ScheduledSms.sender = { _, _, _ -> error("telephony gone") }
        val m = scheduler.schedule(conversation(), "x", now + 5 * min)
        now += 6 * min
        assertEquals(0, run())
        assertEquals(SchedState.FAILED, scheduler.get(m.uid)!!.state)
    }

    @Test fun aConversationWithoutASingleNumberIsRefusedAndNothingIsSent() {
        val named = conversation("Sam Smith") // a name, no number
        val m = scheduler.schedule(named, "hi", now + 5 * min)
        now += 6 * min
        assertEquals(0, run())
        assertTrue(sent.isEmpty())
        assertEquals(SchedState.FAILED, scheduler.get(m.uid)!!.state)
    }

    @Test fun aSendInFlightWhenTheProcessDiedIsUnknownAndNeverResent() {
        val m = scheduler.schedule(conversation(), "maybe sent", now + 5 * min)
        now += 6 * min
        scheduler.claimDue() // the previous process got this far, then died
        ScheduledSms.resetForTests() // a new process
        assertEquals(0, run())
        assertTrue("never resent", sent.isEmpty())
        assertEquals(SchedState.UNKNOWN, scheduler.get(m.uid)!!.state)
    }

    @Test fun aMessageTheAlarmCameTooLateForIsMissedNotSent() {
        val m = scheduler.schedule(conversation(), "too late", now + 5 * min)
        now += 3 * 60 * min
        assertEquals(0, run())
        assertTrue(sent.isEmpty())
        assertEquals(SchedState.MISSED, scheduler.get(m.uid)!!.state)
    }

    @Test fun armingSetsOneAlarmForTheEarliestMessageAndCancelClearsIt() {
        val alarms = shadowOf(ctx.getSystemService(AlarmManager::class.java))
        assertNull(alarms.nextScheduledAlarm)
        val c = conversation()
        val later = scheduler.schedule(c, "b", now + 90 * min)
        val sooner = scheduler.schedule(c, "a", now + 20 * min)
        ScheduledSms.arm(ctx, scheduler)
        assertNotNull(alarms.nextScheduledAlarm)
        assertEquals(sooner.sendAt, alarms.nextScheduledAlarm!!.triggerAtTime)
        scheduler.cancel(sooner.uid); scheduler.cancel(later.uid)
        ScheduledSms.arm(ctx, scheduler)
        assertNull(alarms.nextScheduledAlarm)
    }
}
