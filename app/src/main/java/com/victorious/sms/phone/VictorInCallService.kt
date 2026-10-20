package com.victorious.sms.phone

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import kotlin.concurrent.thread

/** InCallService (needs ROLE_DIALER). Shows InCallActivity for every call. */
class VictorInCallService : InCallService() {
    override fun onCreate() { super.onCreate(); CallManager.service = this }
    override fun onDestroy() { CallManager.service = null; super.onDestroy() }

    override fun onCallAdded(call: Call) {
        CallManager.onCallAdded(call)
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        thread { CallManager.setLabel(CallerLookup(this).lookup(number)?.let { "${it.name} (${it.source})" }) }
        startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onCallRemoved(call: Call) = CallManager.onCallRemoved(call)
    @Deprecated("Deprecated in API 34")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) = CallManager.onAudioChanged()
}
