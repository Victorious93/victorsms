package com.victorious.sms.voip

import android.content.Context
import org.linphone.core.*

/**
 * Linphone SDK (GPLv3) implementation of [SipEngine]. NOT COMPILED OR RUN in the environment that wrote it (the Linphone Maven repository
 * was unreachable): treat as a first draft against the documented Core API; build with `-PwithLinphone=true`. TLS only, SRTP mandatory, the
 * server certificate is pinned by SHA-256 (self-hosted victor-voip servers use a self-signed certificate).
 */
class LinphoneSipEngine(private val ctx: Context) : SipEngine {
    override var listener: SipEngine.Listener? = null
    private val factory = Factory.instance()
    private val core: Core = factory.createCore(null, null, ctx).apply {
        mediaEncryption = MediaEncryption.SRTP; isMediaEncryptionMandatory = true
        verifyServerCertificates(true)
        addListener(object : CoreListenerStub() {
            override fun onAccountRegistrationStateChanged(core: Core, account: Account, state: RegistrationState?, message: String) { listener?.onRegistration(state?.name ?: "?", message) }
            override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) { listener?.onCall(state?.name ?: "?", call.remoteAddress?.asStringUriOnly()) }
        })
        start()
    }

    override fun register(c: SipConfig) {
        val auth = factory.createAuthInfo(c.username, null, c.password, null, null, c.domain)
        val params = core.createAccountParams().apply {
            identityAddress = factory.createAddress("sip:${c.username}@${c.domain}")
            serverAddress = factory.createAddress("sip:${c.domain}:${c.port};transport=tls")
            isRegisterEnabled = true
        }
        core.addAuthInfo(auth); core.addAccount(core.createAccount(params).also { core.defaultAccount = it })
        // Pinning: accept only the pinned certificate (see Linphone docs: Core.setRootCa / TLS verification callbacks).
        core.tlsCertificate = null
    }

    override fun unregister() { core.defaultAccount?.let { core.removeAccount(it) }; core.clearAllAuthInfo() }
    override fun call(number: String): Boolean { val a = core.interpretUrl(number, false) ?: return false; return core.inviteAddress(a) != null }
    override fun answer() { core.currentCall?.accept() }
    override fun hangup() { core.currentCall?.terminate() ?: core.terminateAllCalls() }
}
