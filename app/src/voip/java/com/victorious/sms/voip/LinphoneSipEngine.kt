package com.victorious.sms.voip

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.linphone.core.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Linphone SDK (GPLv3) implementation of [SipEngine]. Compiles against linphone-sdk-android 5.3.x (CI builds the voip edition);
 * it has NOT been run on a device or against a SIP server.
 *
 * Security: TLS only, SRTP mandatory, and the server certificate is PINNED (see [CertPin]):
 *  1. [register] first connects to the server and reads the certificate chain it presents (off the main thread);
 *  2. the chain must carry the owner's SHA-256 pin (certificate or public key), otherwise nothing is registered (fail closed);
 *  3. only that exact chain is given to Linphone as its trusted roots (`rootCa` + `rootCaData`), so Linphone itself rejects any
 *     other certificate on the real connection. A self-signed server is therefore accepted only when it matches the pin, and
 *     the system CA store is not consulted. Hostname (CN) matching is off because the pin identifies the server.
 * Nothing here logs the password, the pin or certificate contents; failure text is fixed strings.
 * Limit: a CA-issued server certificate whose issuer the server does not send may be refused by Linphone's chain check even though
 * the pin matched (fails closed, never open). victor-voip's self-signed servers are the supported case.
 */
class LinphoneSipEngine(private val ctx: Context) : SipEngine {
    override var listener: SipEngine.Listener? = null
    private val factory = Factory.instance()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "sip-pin").apply { isDaemon = true } }
    /** Bumped by [unregister] and every [register]; a pin check that finishes for an older generation is dropped. */
    private val generation = AtomicInteger()

    private val core: Core = factory.createCore(null, null, ctx).apply {
        mediaEncryption = MediaEncryption.SRTP; isMediaEncryptionMandatory = true
        verifyServerCertificates(true)
        verifyServerCn(false)                 // the pin identifies the server; self-signed certificates rarely carry a matching CN
        addListener(object : CoreListenerStub() {
            override fun onAccountRegistrationStateChanged(core: Core, account: Account, state: RegistrationState?, message: String) { listener?.onRegistration(state?.name ?: "?", message) }
            override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) { listener?.onCall(state?.name ?: "?", call.remoteAddress?.asStringUriOnly()) }
        })
        start()
    }

    override fun register(c: SipConfig) {
        val gen = generation.incrementAndGet()
        listener?.onRegistration("Progress", "checking the server certificate")
        io.execute {
            val verdict = CertPin.check(c.domain, c.port, c.serverCertSha256)
            main.post {
                if (gen != generation.get()) return@post                       // unregistered or re-registered meanwhile
                when (verdict) {
                    is CertPin.Verdict.Refused -> listener?.onRegistration("Failed", "not registered: " + verdict.reason)
                    is CertPin.Verdict.Pinned -> registerPinned(c, verdict.trustPem)
                }
            }
        }
    }

    private fun registerPinned(c: SipConfig, trustPem: String) {
        val file = File(ctx.noBackupFilesDir, "sip_pinned_ca.pem")
        val written = runCatching { file.writeText(trustPem); file.setReadable(false, false); file.setReadable(true, true) }.isSuccess
        if (!written) { listener?.onRegistration("Failed", "not registered: could not store the pinned certificate"); return }
        // Only the pinned chain is trusted, however Linphone prefers the file or the in-memory copy.
        core.rootCa = file.absolutePath
        core.setRootCaData(trustPem)
        val auth = factory.createAuthInfo(c.username, null, c.password, null, null, c.domain)
        val params = core.createAccountParams().apply {
            identityAddress = factory.createAddress("sip:${c.username}@${c.domain}")
            serverAddress = factory.createAddress("sip:${c.domain}:${c.port};transport=tls")
            isRegisterEnabled = true
        }
        core.addAuthInfo(auth); core.addAccount(core.createAccount(params).also { core.defaultAccount = it })
    }

    override fun unregister() {
        generation.incrementAndGet()
        core.defaultAccount?.let { core.removeAccount(it) }; core.clearAllAuthInfo()
    }
    override fun call(number: String): Boolean { val a = core.interpretUrl(number, false) ?: return false; return core.inviteAddress(a) != null }
    override fun answer() { core.currentCall?.accept() }
    override fun hangup() { core.currentCall?.terminate() ?: core.terminateAllCalls() }
}
