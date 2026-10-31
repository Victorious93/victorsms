package com.victorious.sms.phone

import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.twilio.voice.Call
import com.twilio.voice.CallException
import com.twilio.voice.ConnectOptions
import com.twilio.voice.Voice
import com.victorious.sms.twilio.TwilioCredentials
import com.victorious.sms.twilio.VoiceCallPlan
import com.victorious.sms.twilio.VoiceCalling
import java.util.concurrent.ConcurrentHashMap

/**
 * Telecom ConnectionService for alias calls over Twilio Voice. Calls appear in VictorInCallService /
 * InCallActivity like any SIM call (answer, end, mute, hold, DTMF all map onto the Twilio call).
 */
class TwilioConnectionService : ConnectionService() {
    companion object {
        private val bySid = ConcurrentHashMap<String, TwilioConnection>()
        fun cancel(callSid: String) { bySid.remove(callSid)?.let { it.setDisconnected(DisconnectCause(DisconnectCause.MISSED)); it.destroy() } }
    }

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val callee = request.address?.schemeSpecificPart.orEmpty()
        val callerId = request.extras?.getString(VoiceCalling.EXTRA_CALLER_ID)
            ?: request.extras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)?.getString(VoiceCalling.EXTRA_CALLER_ID)
        // Re-check against the owned list fetched from Twilio just before placeCall(); never spoof.
        val plan = callerId?.let { VoiceCallPlan.outbound(it, callee, VoiceCalling.ownedSnapshot).getOrNull() }
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR, "Caller ID is not one of your Twilio numbers"))
        val token = runCatching { VoiceCalling.token(TwilioCredentials(this)) }.getOrNull()
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR, "Twilio not configured"))
        val c = TwilioConnection().apply { setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED); setDialing() }
        c.call = Voice.connect(this, ConnectOptions.Builder(token).params(plan).build(), c.listener)
        return c
    }

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val sid = request.extras?.getString(VoiceCalling.EXTRA_CALL_SID)
        val invite = sid?.let { VoiceCalling.invites[it] }
            ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.MISSED))
        val addr = request.extras?.getParcelable<android.net.Uri>(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS)
        val c = TwilioConnection().apply { setAddress(addr, TelecomManager.PRESENTATION_ALLOWED); setRinging(); this.invite = invite; this.sid = sid }
        bySid[sid] = c
        return c
    }

    inner class TwilioConnection : Connection() {
        var call: Call? = null
        var invite: com.twilio.voice.CallInvite? = null
        var sid: String? = null

        init {
            audioModeIsVoip = true
            connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD or CAPABILITY_MUTE
        }

        val listener = object : Call.Listener {
            override fun onConnectFailure(call: Call, e: CallException) = end(DisconnectCause(DisconnectCause.ERROR, e.message))
            override fun onRinging(call: Call) {}
            override fun onConnected(call: Call) { setActive() }
            override fun onReconnecting(call: Call, e: CallException) {}
            override fun onReconnected(call: Call) {}
            override fun onDisconnected(call: Call, e: CallException?) =
                end(if (e == null) DisconnectCause(DisconnectCause.REMOTE) else DisconnectCause(DisconnectCause.ERROR, e.message))
        }

        private fun end(cause: DisconnectCause) {
            sid?.let { bySid.remove(it); VoiceCalling.invites.remove(it) }
            if (state != STATE_DISCONNECTED) { setDisconnected(cause); destroy() }
        }

        override fun onAnswer() {
            val i = invite ?: return
            call = i.accept(this@TwilioConnectionService, listener); invite = null
        }
        override fun onReject() { invite?.reject(this@TwilioConnectionService); invite = null; end(DisconnectCause(DisconnectCause.REJECTED)) }
        override fun onDisconnect() { call?.disconnect(); invite?.reject(this@TwilioConnectionService); end(DisconnectCause(DisconnectCause.LOCAL)) }
        override fun onAbort() = onDisconnect()
        override fun onHold() { call?.hold(true); setOnHold() }
        override fun onUnhold() { call?.hold(false); setActive() }
        override fun onPlayDtmfTone(c: Char) { call?.sendDigits(c.toString()) }
        @Deprecated("Deprecated in API 34")
        override fun onCallAudioStateChanged(state: CallAudioState) { call?.mute(state.isMuted) }
    }
}
