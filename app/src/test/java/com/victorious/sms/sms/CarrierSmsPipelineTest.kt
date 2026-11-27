package com.victorious.sms.sms

import android.Manifest
import android.app.Activity
import android.app.Application
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import com.victorious.comms.Author
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CarrierSmsPipelineTest {
    @Test fun theComposerPathSuppliesCallbacksAndARequestCanReachAndroidOnlyOnce() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.SEND_SMS)
        val subscription = 7
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(subscription)
        val modem = shadowOf(app.getSystemService(SmsManager::class.java)!!.createForSubscriptionId(subscription))
        val repository = SmsRepository(app)
        val address = "+15550100142"
        val body = "SMS test message"
        val key = "composer-callback-test"
        val first = repository.send(address, body, Author.OWNER, requestKey = key) as SmsSubmission.Tracked
        val sent = modem.lastSentMultipartTextMessageParams!!
        assertEquals(address, sent.destinationAddress)
        assertEquals(SmsSubmissionState.SENDING, first.receipt.state())
        assertEquals(sent.parts.size, sent.sentIntents.size)
        assertEquals(sent.parts.size, sent.deliveryIntents.size)
        for (intent in sent.sentIntents) {
            val callback = shadowOf(intent).savedIntent
            assertEquals(SmsCallbackReceiver::class.java.name, callback.component?.className)
            CarrierSms.handle(app, callback, Activity.RESULT_OK)
        }
        assertEquals(SmsSubmissionState.SENT, CarrierSms.receipt(app, key)?.state())
        assertEquals(SmsDelivery.UNCONFIRMED, CarrierSms.receipt(app, key)?.delivery)
        modem.clearLastSentMultipartTextMessageParams()
        val repeat = repository.send(address, body, Author.OWNER, requestKey = key) as SmsSubmission.Tracked
        assertEquals(first.receipt.id, repeat.receipt.id)
        assertNull(modem.lastSentMultipartTextMessageParams)
    }
}
