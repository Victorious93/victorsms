package com.victorious.sms.twilio

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Receives Twilio Voice incoming-call pushes from Don's own Firebase project. */
class VoicePushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) { VoiceCalling.handlePush(this, message.data) }
    override fun onNewToken(token: String) { VoiceCalling.registerWithTwilio(this, token) }
}
