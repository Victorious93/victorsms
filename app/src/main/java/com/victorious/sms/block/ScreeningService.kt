package com.victorious.sms.block

import android.telecom.Call
import android.telecom.CallScreeningService

/** Call screening (ROLE_CALL_SCREENING): applies the local blocklist and number patterns. */
class ScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val action = if (number.isBlank()) BlockAction.ALLOW else Blocklist(this).decideNumber(number)
        val resp = CallResponse.Builder()
        when (action) {
            BlockAction.BLOCK -> resp.setDisallowCall(true).setRejectCall(true).setSkipCallLog(false).setSkipNotification(true)
            BlockAction.SILENCE -> resp.setSilenceCall(true)
            BlockAction.ALLOW -> {}
        }
        respondToCall(details, resp.build())
    }
}
