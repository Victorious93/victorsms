package com.victorious.sms.sms

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Telephony
import com.victorious.sms.MainActivity

/** SMS_DELIVER: as default SMS app we must store incoming SMS ourselves. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val address = parts[0].displayOriginatingAddress.orEmpty()
        val body = parts.joinToString("") { it.displayMessageBody.orEmpty() }
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.READ, 0)
        })
        com.victorious.sms.relay.RelayNode.get(context).mirrorIncoming(address, body) // opt-in per peer only
        // Fleet SMS trigger (whitelist + code word -> forward to droidcommand-AI) is planned, not here.
    }
}

/** WAP_PUSH_DELIVER for MMS. Required for the default SMS role; MMS download is planned. */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { /* planned: download + store MMS */ }
}

/** RESPOND_VIA_MESSAGE ("reply with text" on incoming calls). Sends only text the user typed. */
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.data?.schemeSpecificPart?.substringBefore("?")?.let(Uri::decode)
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!to.isNullOrBlank() && !body.isNullOrBlank()) SmsRepository(this).send(to, body)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** SENDTO sms:/smsto:/mms:/mmsto: → opens the composer in the main UI. */
class ComposeSmsActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val to = intent?.data?.schemeSpecificPart?.substringBefore("?")?.let(Uri::decode).orEmpty()
        val body = intent?.getStringExtra("sms_body") ?: intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_COMPOSE_TO, to)
            .putExtra(MainActivity.EXTRA_COMPOSE_BODY, body))
        finish()
    }
}
