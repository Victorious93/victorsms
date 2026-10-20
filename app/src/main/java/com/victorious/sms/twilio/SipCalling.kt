package com.victorious.sms.twilio

import android.content.Context

/**
 * SIP calling through Don's Twilio SIP domain: STUB.
 *
 * - android.net.sip.SipManager still exists in the SDK but is deprecated since API 31 and
 *   unsupported on most current devices (isApiSupported() is usually false), so it is not used.
 * - Linphone/liblinphone and PJSIP are GPL (or commercial) and must not be linked into this
 *   Apache-2.0 app.
 * - Candidates: baresip (BSD-3-Clause, native build via NDK), or Twilio's Voice Android SDK
 *   (proprietary Twilio licence, uses Twilio Voice rather than plain SIP). Either needs Don's call.
 */
object SipCalling {
    @Suppress("DEPRECATION")
    fun platformSipSupported(context: Context): Boolean =
        runCatching { android.net.sip.SipManager.isApiSupported(context) && android.net.sip.SipManager.isVoipSupported(context) }.getOrDefault(false)

    fun placeCall(to: String): Result<Unit> = Result.failure(UnsupportedOperationException("SIP calling is not implemented yet"))
}
