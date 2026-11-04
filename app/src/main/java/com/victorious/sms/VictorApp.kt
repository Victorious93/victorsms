package com.victorious.sms

import android.app.Application
import com.victorious.sms.twilio.VoiceCalling

class VictorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firebase is only used for incoming alias-call pushes, and only if Don configured his own project.
        VoiceCalling.initFirebaseIfConfigured(this)
    }
}
