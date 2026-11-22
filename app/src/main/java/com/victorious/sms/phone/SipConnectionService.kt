package com.victorious.sms.phone

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.victorious.sms.voip.SipCalls
import com.victorious.sms.voip.SipRules

/**
 * Telecom ConnectionService for calls over the owner's own SIP server. Calls show up in the normal in-call UI (VictorInCallService);
 * answer/hangup/hold map onto the SIP engine. If no engine is built in or the account is not registered, the call fails with a clear reason
 * instead of silently using another route.
 */
class SipConnectionService : ConnectionService() {
    companion object {
        const val ACCOUNT_ID = "victor-sip"
        @Volatile private var current: SipConnection? = null

        fun handle(ctx: Context) = PhoneAccountHandle(ComponentName(ctx, SipConnectionService::class.java), ACCOUNT_ID)

        fun register(ctx: Context) {
            val acct = PhoneAccount.builder(handle(ctx), "Victorious SIP (own server)").setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL).addSupportedUriScheme(PhoneAccount.SCHEME_SIP).build()
            ctx.getSystemService(TelecomManager::class.java).registerPhoneAccount(acct)
        }

        /** Engine → Telecom: state changes of the active SIP call, and incoming calls. */
        fun onEngineCall(ctx: Context, state: String, peer: String?) {
            val phase = SipRules.phase(state)
            val c = current
            if (c == null && state.uppercase() == "INCOMINGRECEIVED") {
                val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("sip", peer ?: "unknown", null)) }
                runCatching { ctx.getSystemService(TelecomManager::class.java).addNewIncomingCall(handle(ctx), extras) }
                return
            }
            c?.apply(phase)
        }
    }

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val number = SipRules.dialable(request.address?.schemeSpecificPart.orEmpty())
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR, "Not a dialable number"))
        val engine = SipCalls.engine ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR, "VoIP engine not included in this build"))
        val c = SipConnection(engine).apply { setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED); setDialing() }
        current = c
        if (!engine.call(number)) { current = null; return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR, "SIP account is not registered")) }
        return c
    }

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val engine = SipCalls.engine ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.MISSED))
        val addr = request.extras?.getParcelable<Uri>(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS)
        return SipConnection(engine).apply { setAddress(addr, TelecomManager.PRESENTATION_ALLOWED); setRinging() }.also { current = it }
    }

    class SipConnection(private val engine: com.victorious.sms.voip.SipEngine) : Connection() {
        init { audioModeIsVoip = true; connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_SUPPORT_HOLD or CAPABILITY_HOLD }
        fun apply(p: SipRules.Phase) {
            when (p) {
                SipRules.Phase.DIALING -> if (state != STATE_DIALING) setDialing()
                SipRules.Phase.RINGING -> if (state == STATE_NEW || state == STATE_DIALING) setDialing()
                SipRules.Phase.ACTIVE -> if (state != STATE_ACTIVE) setActive()
                SipRules.Phase.ENDED, SipRules.Phase.FAILED -> if (state != STATE_DISCONNECTED) { setDisconnected(DisconnectCause(SipRules.cause(p))); destroy(); if (current === this) current = null }
            }
        }
        override fun onAnswer() { engine.answer() }
        override fun onReject() { engine.hangup(); setDisconnected(DisconnectCause(DisconnectCause.REJECTED)); destroy(); if (current === this) current = null }
        override fun onDisconnect() { engine.hangup(); setDisconnected(DisconnectCause(DisconnectCause.LOCAL)); destroy(); if (current === this) current = null }
        override fun onAbort() = onDisconnect()
    }
}
