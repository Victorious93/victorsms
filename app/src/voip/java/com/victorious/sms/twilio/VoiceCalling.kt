package com.victorious.sms.twilio

import android.content.Context
import android.content.pm.PackageManager

/** voip edition (GPLv3): the proprietary Twilio Voice SDK is NOT included, so alias calling over Twilio is unavailable. Twilio SMS/REST features are unaffected. */
object VoiceCalling {
    const val IDENTITY = "don"
    fun micGranted(ctx: Context) = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun initFirebaseIfConfigured(ctx: Context) = false
    fun registerIncoming(ctx: Context, onResult: (String) -> Unit) = onResult("Not available in the voip edition (it has no Twilio Voice SDK). Use your own SIP server.")
    fun placeCall(ctx: Context, alias: String, callee: String): Result<Unit> = Result.failure(IllegalStateException("Twilio alias calls are not in the voip edition. Use SIP / VoIP."))
}
