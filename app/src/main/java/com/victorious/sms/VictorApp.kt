package com.victorious.sms

import android.app.Application
import com.victorious.sms.twilio.VoiceCalling

class VictorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firebase is only used for incoming alias-call pushes, and only if Don configured his own project.
        VoiceCalling.initFirebaseIfConfigured(this)
        // Own SIP server: register the Telecom account and (re)connect if provisioned. Never throws at startup.
        // Background mail sync only exists while at least one mail account is configured.
        runCatching { if (com.victorious.sms.mail.MailRepository.get(this).storedAccounts().isNotEmpty()) com.victorious.sms.mail.MailSyncJob.schedule(this) }
        // Alarms for owner-scheduled texts are lost on reboot/update; set the next one again (off the main thread: opens the archive).
        Thread { runCatching { com.victorious.sms.sms.ScheduledSms.arm(this) } }.start()
        runCatching { com.victorious.sms.phone.SipConnectionService.register(this); com.victorious.sms.voip.SipCalls.start(this) }
    }
}
