package com.victorious.sms

import android.app.Application
import com.victorious.sms.twilio.VoiceCalling

class VictorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firebase is only used for incoming alias-call pushes, and only if Don configured his own project.
        VoiceCalling.initFirebaseIfConfigured(this)
        // Own SIP server: register the Telecom account and (re)connect if provisioned. Never throws at startup.
        runCatching { com.victorious.sms.phone.SipConnectionService.register(this); com.victorious.sms.voip.SipCalls.start(this) }
    }
}
