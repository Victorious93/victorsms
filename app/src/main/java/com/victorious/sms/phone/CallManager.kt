package com.victorious.sms.phone

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CallUi(val number: String, val state: Int, val muted: Boolean, val speaker: Boolean, val label: String?)

/** Holds the active call from InCallService and exposes controls to the in-call screen. */
object CallManager {
    @Volatile var service: InCallService? = null
    @Volatile var call: Call? = null
        private set
    private val _ui = MutableStateFlow<CallUi?>(null)
    val ui: StateFlow<CallUi?> = _ui
    @Volatile private var label: String? = null

    private val cb = object : Call.Callback() {
        override fun onStateChanged(c: Call, state: Int) = publish()
    }

    fun onCallAdded(c: Call) { call?.unregisterCallback(cb); call = c; label = null; c.registerCallback(cb); publish() }
    fun onCallRemoved(c: Call) { if (call == c) { c.unregisterCallback(cb); call = null; _ui.value = null } }
    fun onAudioChanged() = publish()
    fun setLabel(l: String?) { label = l; publish() }

    @Suppress("DEPRECATION")
    fun publish() {
        val c = call ?: return
        val audio = service?.callAudioState
        _ui.value = CallUi(
            number = c.details.handle?.schemeSpecificPart.orEmpty(),
            state = c.details.state,
            muted = audio?.isMuted == true,
            speaker = audio?.route == CallAudioState.ROUTE_SPEAKER,
            label = label,
        )
    }

    fun answer() = call?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
    fun end() { call?.let { if (it.details.state == Call.STATE_RINGING) it.reject(false, null) else it.disconnect() } }
    fun toggleMute() { service?.setMuted(!(_ui.value?.muted ?: false)) }
    @Suppress("DEPRECATION")
    fun toggleSpeaker() { service?.setAudioRoute(if (_ui.value?.speaker == true) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER) }
    fun toggleHold() { call?.let { if (it.details.state == Call.STATE_HOLDING) it.unhold() else it.hold() } }
    fun dtmf(ch: Char) { call?.playDtmfTone(ch); call?.stopDtmfTone() }
}
