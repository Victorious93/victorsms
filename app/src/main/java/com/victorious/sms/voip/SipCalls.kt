package com.victorious.sms.voip

import android.content.Context
import android.telecom.DisconnectCause
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Pure helpers (unit-tested). */
object SipRules {
    /** Dial strings accepted for SIP: extensions and E.164 only. Anything else (URIs, headers, spaces) is refused so a number can never inject SIP syntax. */
    fun dialable(s: String): String? = s.trim().replace(" ", "").replace("-", "").takeIf { Regex("^\\+?[0-9]{2,20}$").matches(it) }

    enum class Phase { DIALING, RINGING, ACTIVE, ENDED, FAILED }
    fun phase(engineState: String): Phase = when (engineState.uppercase()) {
        "OUTGOINGINIT", "OUTGOINGPROGRESS" -> Phase.DIALING
        "OUTGOINGRINGING", "OUTGOINGEARLYMEDIA", "INCOMINGRECEIVED", "INCOMINGEARLYMEDIA" -> Phase.RINGING
        "CONNECTED", "STREAMSRUNNING", "UPDATEDBYREMOTE", "UPDATING", "PAUSED", "PAUSEDBYREMOTE", "RESUMING" -> Phase.ACTIVE
        "END", "RELEASED" -> Phase.ENDED
        "ERROR" -> Phase.FAILED
        else -> Phase.DIALING
    }
    fun cause(p: Phase) = when (p) { Phase.FAILED -> DisconnectCause.ERROR; else -> DisconnectCause.REMOTE }
}

/** Provisioning stored encrypted (Keystore). The password never appears in logs or UI. */
class SipStore(context: Context) {
    private val p = EncryptedSharedPreferences.create(context.applicationContext, "sip_account", MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun save(json: String): Result<SipConfig> = SipConfig.parse(json).onSuccess { p.edit().putString("json", json).apply() }
    fun load(): SipConfig? = p.getString("json", null)?.let { SipConfig.parse(it).getOrNull() }
    fun clear() { p.edit().clear().apply() }
}

/** Process-wide SIP state. The engine is the Linphone implementation when it was compiled in (`-PwithLinphone=true`), otherwise there is none and calls say so. */
object SipCalls {
    private val _status = MutableStateFlow("not configured")
    val status: StateFlow<String> = _status
    @Volatile var engine: SipEngine? = null; private set
    val engineAvailable: Boolean get() = runCatching { Class.forName("com.victorious.sms.voip.LinphoneSipEngine") }.isSuccess

    @Synchronized fun start(ctx: Context) {
        val cfg = SipStore(ctx).load() ?: run { _status.value = "not configured"; return }
        if (engine == null) engine = runCatching { Class.forName("com.victorious.sms.voip.LinphoneSipEngine").getConstructor(Context::class.java).newInstance(ctx.applicationContext) as SipEngine }.getOrNull()
        val e = engine ?: run { _status.value = "VoIP engine not included in this build (needs the Linphone build, see docs)"; return }
        e.listener = object : SipEngine.Listener {
            override fun onRegistration(state: String, reason: String?) { _status.value = "registration: ${state.lowercase()}" + (reason?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") }
            override fun onCall(state: String, peer: String?) { com.victorious.sms.phone.SipConnectionService.onEngineCall(ctx.applicationContext, state, peer) }
        }
        e.register(cfg); _status.value = "registering…"
    }

    fun stop() { engine?.unregister(); _status.value = "stopped" }
}
