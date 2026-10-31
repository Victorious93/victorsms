package com.victorious.sms.twilio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.twilio.voice.CallInvite
import com.twilio.voice.CallException
import com.twilio.voice.CancelledCallInvite
import com.twilio.voice.MessageListener
import com.twilio.voice.RegistrationException
import com.twilio.voice.RegistrationListener
import com.twilio.voice.Voice
import com.victorious.sms.phone.TwilioConnectionService
import java.util.concurrent.ConcurrentHashMap

/**
 * Alias calling over the Twilio Voice Android SDK (Twilio TOS licence, not GPL; see docs/ARCHITECTURE.md).
 * Calls go through Telecom via [TwilioConnectionService], so the existing InCallService / InCallActivity
 * show them like SIM calls. Caller ID is only ever one of Don's own Twilio numbers.
 */
object VoiceCalling {
    private const val TAG = "VoiceCalling"
    const val IDENTITY = "don"
    const val EXTRA_CALLER_ID = "com.victorious.sms.voice.CALLER_ID"
    const val EXTRA_CALL_SID = "com.victorious.sms.voice.CALL_SID"

    /** Owned numbers from the most recent fresh Twilio fetch; the ConnectionService re-checks against it. */
    @Volatile var ownedSnapshot: List<String> = emptyList()
    val invites = ConcurrentHashMap<String, CallInvite>()

    fun accountHandle(ctx: Context) =
        PhoneAccountHandle(ComponentName(ctx, TwilioConnectionService::class.java), "twilio-alias")

    fun registerAccount(ctx: Context) {
        val acct = PhoneAccount.builder(accountHandle(ctx), "VictorSMS alias (Twilio)")
            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
            .build()
        ctx.getSystemService(TelecomManager::class.java).registerPhoneAccount(acct)
    }

    fun micGranted(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun token(creds: TwilioCredentials): String = VoiceToken.mint(
        creds.accountSid, creds.apiKeySid, creds.apiKeySecret, IDENTITY,
        creds.twimlAppSid.ifBlank { null }, creds.pushCredentialSid.ifBlank { null },
    )

    /**
     * Places an alias call. Must be called OFF the main thread: it fetches Don's numbers from Twilio
     * right now and refuses any caller ID not on that list (FromNumberGuard). Never spoofs.
     */
    fun placeCall(ctx: Context, alias: String, callee: String): Result<Unit> = runCatching {
        val creds = TwilioCredentials(ctx)
        check(creds.isSet()) { "Twilio credentials not set." }
        check(creds.twimlAppSid.startsWith("AP")) { "Set up the TwiML App first (Twilio screen)." }
        check(micGranted(ctx)) { "Microphone permission is needed for alias calls." }
        val owned = TwilioClient(creds).listNumbers().map { it.phoneNumber }
        val plan = VoiceCallPlan.outbound(alias, callee, owned).getOrThrow()
        ownedSnapshot = owned
        registerAccount(ctx)
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, accountHandle(ctx))
            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply { putString(EXTRA_CALLER_ID, plan.getValue(VoiceCallPlan.PARAM_CALLER_ID)) })
        }
        ctx.getSystemService(TelecomManager::class.java)
            .placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, plan.getValue(VoiceCallPlan.PARAM_TO), null), extras)
    }

    /** Initialises Don's own Firebase project from runtime settings (no google-services.json in the repo). */
    fun initFirebaseIfConfigured(ctx: Context): Boolean {
        if (FirebaseApp.getApps(ctx).isNotEmpty()) return true
        val c = runCatching { TwilioCredentials(ctx) }.getOrNull() ?: return false
        if (!c.fcmSet()) return false
        return runCatching {
            FirebaseApp.initializeApp(ctx, FirebaseOptions.Builder().setProjectId(c.fcmProjectId)
                .setApplicationId(c.fcmAppId).setApiKey(c.fcmApiKey).setGcmSenderId(c.fcmSenderId).build())
        }.isSuccess
    }

    /** Registers this phone for incoming alias calls (Twilio push via FCM). Off the main thread is fine. */
    fun registerIncoming(ctx: Context, onResult: (String) -> Unit) {
        if (!initFirebaseIfConfigured(ctx)) { onResult("Enter your Firebase project settings first."); return }
        val creds = TwilioCredentials(ctx)
        if (!creds.pushCredentialSid.startsWith("CR")) { onResult("Enter your Twilio Push Credential SID (CR…) first."); return }
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { fcm -> registerWithTwilio(ctx, fcm, onResult) }
            .addOnFailureListener { onResult("FCM token failed: ${it.javaClass.simpleName}") }
    }

    fun registerWithTwilio(ctx: Context, fcmToken: String, onResult: (String) -> Unit = {}) {
        val creds = TwilioCredentials(ctx)
        if (!creds.isSet() || !creds.pushCredentialSid.startsWith("CR")) return
        val t = runCatching { token(creds) }.getOrElse { onResult("Token failed: ${it.message}"); return }
        registerAccount(ctx)
        Voice.register(t, Voice.RegistrationChannel.FCM, fcmToken, object : RegistrationListener {
            override fun onRegistered(accessToken: String, fcmToken: String) = onResult("Registered for incoming alias calls.")
            override fun onError(e: RegistrationException, accessToken: String, fcmToken: String) = onResult("Registration failed: ${e.message}")
        })
    }

    /** Called from the FCM service. Returns true if the push was a Twilio Voice message. */
    fun handlePush(ctx: Context, data: Map<String, String>): Boolean = Voice.handleMessage(ctx, data, object : MessageListener {
        override fun onCallInvite(invite: CallInvite) {
            // Inbound calls only reach us via an owned alias (pc/voice_shim.py /incoming checks To).
            invites[invite.callSid] = invite
            val from = invite.from?.removePrefix("client:").orEmpty()
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts(PhoneAccount.SCHEME_TEL, from, null))
                putString(EXTRA_CALL_SID, invite.callSid)
            }
            runCatching { registerAccount(ctx); ctx.getSystemService(TelecomManager::class.java).addNewIncomingCall(accountHandle(ctx), extras) }
                .onFailure { Log.w(TAG, "addNewIncomingCall failed: ${it.javaClass.simpleName}") }
        }
        override fun onCancelledCallInvite(cancelled: CancelledCallInvite, e: CallException?) {
            invites.remove(cancelled.callSid)
            TwilioConnectionService.cancel(cancelled.callSid)
        }
    })
}
