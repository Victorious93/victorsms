package com.victorious.sms.sms

import android.app.Activity
import android.database.sqlite.SQLiteDatabase
import android.telephony.SmsManager
import com.victorious.comms.Author
import com.victorious.sms.archive.AndroidSqlDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmsSendStoreTest {
    private val sqlite = SQLiteDatabase.create(null)
    private val db = AndroidSqlDb(sqlite)
    private val store = SmsSendStore(db)
    private val sim = SmsSim(7, "SIM 2")
    private val now = 1000L

    @After fun close() { sqlite.close() }

    private fun begin(key: String = "request-1", parts: Int = 2) =
        store.begin(key, "+15550100142", "Hello", sim, Author.OWNER, parts, now)

    @Test fun anExistingSendCannotReclaimDispatchAfterReopeningTheStore() {
        val first = begin()
        val reopened = SmsSendStore(db)
        val retry = reopened.begin("request-1", "+15550100142", "Hello", sim, Author.OWNER, 2, now)
        assertTrue(first.created)
        assertFalse(retry.created)
        assertEquals(first.id, retry.id)
        assertEquals(1, reopened.recent().size)
    }

    @Test fun sentAndDeliveredRequireAllPartsAndHandleDuplicateCallbacks() {
        val send = begin()
        store.sent(send.id, 1, Activity.RESULT_OK)
        assertNull(store.sent(send.id, 1, SmsManager.RESULT_ERROR_GENERIC_FAILURE))
        assertNull(store.sent(send.id, 99, Activity.RESULT_OK))
        assertEquals(SmsSubmissionState.SENDING, store.byId(send.id)!!.state(now))
        store.sent(send.id, 0, Activity.RESULT_OK)
        assertEquals(SmsSubmissionState.SENT, store.byId(send.id)!!.state(now))
        store.delivered(send.id, 0, SmsDelivery.DELIVERED)
        assertEquals(SmsDelivery.UNCONFIRMED, store.byId(send.id)!!.delivery)
        store.delivered(send.id, 1, SmsDelivery.DELIVERED)
        assertEquals(SmsDelivery.DELIVERED, store.byId(send.id)!!.delivery)
    }

    @Test fun mixedModemResultsStayPartial() {
        val send = begin()
        store.sent(send.id, 0, Activity.RESULT_OK)
        store.sent(send.id, 1, SmsManager.RESULT_ERROR_NO_SERVICE)
        val receipt = store.byId(send.id)!!
        assertEquals(SmsSubmissionState.PARTIAL, receipt.state(now))
        assertEquals(SmsDelivery.UNCONFIRMED, receipt.delivery)
        assertEquals("Partially sent (1 of 2 parts)", receipt.statusLabel(now))
    }

    @Test fun aMissingOrInterruptedCallbackIsUnknownAndCanBeResolvedLater() {
        val send = begin(parts = 1)
        val deadline = now + SmsSendReceipt.CALLBACK_WAIT_MS
        assertEquals(SmsSubmissionState.SENDING, store.byId(send.id)!!.state(deadline - 1))
        assertEquals(SmsSubmissionState.UNKNOWN, store.byId(send.id)!!.state(deadline))
        store.markDispatchUnknown(send.id)
        assertEquals(SmsSubmissionState.UNKNOWN, store.byId(send.id)!!.state(now))
        store.sent(send.id, 0, SmsManager.RESULT_ERROR_RADIO_OFF)
        assertEquals("Failed · Mobile radio is off", store.byId(send.id)!!.statusLabel(deadline + 1))
    }

    @Test fun pendingAndReplacementReportsAreNeverReportedAsDelivered() {
        assertNull(CarrierSms.deliveryStatus("3gpp", 2))
        assertNull(CarrierSms.deliveryStatus("3gpp", 0x20))
        assertEquals(SmsDelivery.DELIVERED, CarrierSms.deliveryStatus("3gpp", 0))
        assertEquals(SmsDelivery.FAILED, CarrierSms.deliveryStatus("3gpp", 0x40))
        assertEquals(SmsDelivery.DELIVERED, CarrierSms.deliveryStatus("3gpp2", 2 shl 16))
        assertEquals(SmsDelivery.FAILED, CarrierSms.deliveryStatus("3gpp2", 3 shl 24))
    }

    @Test fun choosingAnInactiveSimNeverFallsBackToAnotherSubscription() {
        val first = SmsSim(1, "SIM 1")
        val active = listOf(first, sim)
        assertEquals(sim, SmsSubscriptions.select(7, 1, active))
        assertEquals(first, SmsSubscriptions.select(null, 1, active))
        assertEquals(sim, SmsSubscriptions.select(null, -1, listOf(sim)))
        assertThrows(IllegalArgumentException::class.java) { SmsSubscriptions.select(7, 1, listOf(first)) }
        assertThrows(IllegalArgumentException::class.java) { SmsSubscriptions.select(7, 1, null) }
        assertThrows(IllegalArgumentException::class.java) { SmsSubscriptions.select(null, -1, active) }
    }
}
