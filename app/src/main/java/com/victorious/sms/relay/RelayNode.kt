package com.victorious.sms.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.victorious.sms.bridge.CallerIdentity
import com.victorious.sms.bridge.RemoteSmsQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Phone-to-phone relay over the Victorious Mesh (any IP-reachable peer address).
 * Wire: mutual TLS 1.3 with pinned peer certs; one JSON request line, one JSON reply line.
 * Frame: {"protocol_major":1,"id","op","params":{requestId, nonce, timestampMs, ttlMs, ...}}.
 */
class RelayNode(private val context: Context) {
    val phones = PairedPhones(context)

    /** Pairing: peer certs accepted during a short window; saved only after code confirmation. */
    @Volatile private var pairingUntilMs = 0L
    data class PairingCandidate(val fingerprint: String, val name: String, val address: String, val code: String)
    private val _candidate = MutableStateFlow<PairingCandidate?>(null)
    val candidate: StateFlow<PairingCandidate?> = _candidate

    fun startPairingWindow(ms: Long = 5 * 60_000L) { pairingUntilMs = System.currentTimeMillis() + ms }
    private fun pairing() = System.currentTimeMillis() < pairingUntilMs
    private fun accept(fp: String) = PeerPin.acceptInbound(fp, phones.all().map { it.fingerprint }, pairing())

    fun confirmCandidate() {
        val c = _candidate.value ?: return
        // Pin: a phone already paired at this address keeps its cert; a different cert is refused.
        val existing = phones.all().firstOrNull { it.address.isNotBlank() && it.address == c.address }
        if (existing != null && !PeerPin.matches(existing.fingerprint, c.fingerprint)) {
            _candidate.value = null; pairingUntilMs = 0; return
        }
        phones.save(PairedPhone(c.fingerprint, c.name, c.address))
        _candidate.value = null
        pairingUntilMs = 0
    }
    fun rejectCandidate() { _candidate.value = null; pairingUntilMs = 0 }

    // ---------------- server ----------------
    @Volatile private var server: SSLServerSocket? = null

    fun startServer(port: Int = DEFAULT_PORT) {
        if (server != null) return
        val ss = RelayCrypto.sslContext(::accept).serverSocketFactory.createServerSocket() as SSLServerSocket
        ss.reuseAddress = true
        ss.needClientAuth = true
        ss.enabledProtocols = arrayOf("TLSv1.3")
        ss.bind(InetSocketAddress(port))
        server = ss
        Thread({
            while (!ss.isClosed) {
                val s = runCatching { ss.accept() as SSLSocket }.getOrNull() ?: continue
                Thread({ serve(s) }, "relay-conn").start()
            }
        }, "relay-server").start()
    }

    fun stopServer() { runCatching { server?.close() }; server = null }

    private fun serve(s: SSLSocket): Unit = s.use {
        it.soTimeout = 15_000
        val peerCert = runCatching { it.session.peerCertificates.first() as X509Certificate }.getOrNull() ?: return
        val fp = RelayCrypto.fingerprint(peerCert)
        val reader = BufferedReader(InputStreamReader(it.inputStream, Charsets.UTF_8))
        val line = reader.readLine()?.take(16_384) ?: return
        val reply = handle(fp, line)
        OutputStreamWriter(it.outputStream, Charsets.UTF_8).apply { write(reply.toString() + "\n"); flush() }
        Unit
    }

    internal fun handle(peerFp: String, line: String): JSONObject {
        val req = runCatching { JSONObject(line) }.getOrNull() ?: return err("", "invalid_frame")
        val id = req.optString("id").take(64)
        if (req.optInt("protocol_major", -1) != 1) return err(id, "unsupported_protocol")
        val p = req.optJSONObject("params") ?: JSONObject()
        val op = req.optString("op")
        if (op == "relay.pair.hello") {
            if (!pairing()) return err(id, "not_pairing")
            val code = RelayCrypto.pairingCode(RelayCrypto.myFingerprint(), peerFp)
            _candidate.value = PairingCandidate(peerFp, p.optString("name").take(40).ifBlank { "Phone" },
                p.optString("address").take(100), code)
            return ok(id, JSONObject().put("name", Build.MODEL))
        }
        val peer = phones.all().firstOrNull { PeerPin.matches(it.fingerprint, peerFp) } ?: return err(id, "not_paired")
        return when (op) {
            "relay.sms.send" -> {
                if (!peer.allowSmsSend) return err(id, "permission_denied")
                when (val r = RemoteSmsQueue.submit(context, CallerIdentity("phone:" + peer.name, peer.fingerprint),
                    p.optString("requestId"), p.optString("to"), p.optString("body"), p.optString("nonce"),
                    p.optLong("timestampMs"), if (p.has("ttlMs")) p.optLong("ttlMs") else null)) {
                    is RemoteSmsQueue.Result.Accepted -> ok(id, JSONObject().put("requestId", r.requestId).put("status", "pending"))
                    is RemoteSmsQueue.Result.Rejected -> err(id, r.code)
                }
            }
            "relay.sms.send.status" -> {
                RemoteSmsQueue.ensureLoaded(context)
                val st = RemoteSmsQueue.status(CallerIdentity("phone:" + peer.name, peer.fingerprint), p.optString("requestId"))
                    ?: return err(id, "not_found")
                ok(id, JSONObject().put("status", st.name.lowercase()))
            }
            "relay.call.place" -> {
                if (!peer.allowCalls) return err(id, "permission_denied")
                RemoteCallQueue.submit(context, peer, p.optString("requestId"), p.optString("to"),
                    p.optString("nonce"), p.optLong("timestampMs"), if (p.has("ttlMs")) p.optLong("ttlMs") else null)
                    ?.let { return err(id, it) }
                ok(id, JSONObject().put("requestId", p.optString("requestId")).put("status", "pending"))
            }
            "relay.call.status" -> {
                val st = RemoteCallQueue.status(context, peer.fingerprint, p.optString("requestId")) ?: return err(id, "not_found")
                ok(id, JSONObject().put("status", st.name.lowercase()))
            }
            "relay.sms.inbox" -> {
                // Mirror pushed by a paired phone that opted in on ITS side. Shown as a notification only.
                notifyMirror(peer.name, p.optString("from").take(40), p.optString("body").take(1600))
                ok(id, JSONObject())
            }
            else -> err(id, "unsupported_operation")
        }
    }

    // ---------------- client ----------------
    /** Sends one request to a paired phone, pinned to its cert fingerprint. */
    fun request(peer: PairedPhone, op: String, params: JSONObject): JSONObject = request(peer.address, op, params, peer.fingerprint)

    /**
     * Sends one request to [address] (host:port). Runs on the caller's thread (use IO).
     * [pinnedFp] is required for every op except an initial relay.pair.hello; a mismatching cert fails the
     * TLS handshake and is re-checked after it.
     */
    fun request(address: String, op: String, params: JSONObject, pinnedFp: String? = null): JSONObject {
        if (op != "relay.pair.hello" && pinnedFp == null) return err("", "not_paired")
        val host = address.substringBeforeLast(":"); val port = address.substringAfterLast(":").toIntOrNull() ?: DEFAULT_PORT
        val f = RelayCrypto.sslContext { fp -> PeerPin.acceptOutbound(op, pinnedFp, fp) }.socketFactory
        (f.createSocket() as SSLSocket).use { s ->
            s.enabledProtocols = arrayOf("TLSv1.3")
            s.connect(InetSocketAddress(host, port), 10_000)
            s.soTimeout = 15_000
            s.startHandshake()
            val actualFp = RelayCrypto.fingerprint(s.session.peerCertificates.first() as X509Certificate)
            if (!PeerPin.acceptOutbound(op, pinnedFp, actualFp)) return err("", "peer_cert_mismatch")
            val frame = JSONObject().put("protocol_major", 1).put("id", UUID.randomUUID().toString()).put("op", op).put("params", params)
            OutputStreamWriter(s.outputStream, Charsets.UTF_8).apply { write(frame.toString() + "\n"); flush() }
            val line = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8)).readLine() ?: return err("", "no_reply")
            val reply = JSONObject(line)
            if (op == "relay.pair.hello" && reply.optBoolean("ok")) {
                val peerFp = RelayCrypto.fingerprint(s.session.peerCertificates.first() as X509Certificate)
                _candidate.value = PairingCandidate(peerFp, reply.optJSONObject("result")?.optString("name").orEmpty().ifBlank { "Phone" },
                    address, RelayCrypto.pairingCode(RelayCrypto.myFingerprint(), peerFp))
            }
            return reply
        }
    }

    /** Envelope params with fresh 128-bit nonce, timestamp and default ttl. */
    fun envelope(): JSONObject {
        val n = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return JSONObject().put("requestId", UUID.randomUUID().toString()).put("nonce", n)
            .put("timestampMs", System.currentTimeMillis()).put("ttlMs", 120_000L)
    }

    /** Called from SmsDeliverReceiver: pushes to peers that Don opted in for mirroring. */
    fun mirrorIncoming(from: String, body: String) {
        val targets = phones.all().filter { it.mirrorInboxToPeer }
        if (targets.isEmpty()) return
        Thread {
            targets.forEach { t -> runCatching { request(t, "relay.sms.inbox", envelope().put("from", from).put("body", body)) } }
        }.start()
    }

    private fun notifyMirror(peer: String, from: String, body: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("relay_mirror", "Mirrored texts", NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(context).notify((peer + from + body).hashCode(),
            NotificationCompat.Builder(context, "relay_mirror").setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("$from (via $peer)").setContentText(body).build())
    }

    private fun ok(id: String, r: JSONObject) = JSONObject().put("id", id).put("ok", true).put("result", r)
    private fun err(id: String, c: String) = JSONObject().put("id", id).put("ok", false).put("error", c)

    companion object {
        const val DEFAULT_PORT = 47320
        @Volatile private var inst: RelayNode? = null
        fun get(context: Context) = inst ?: synchronized(this) { inst ?: RelayNode(context.applicationContext).also { inst = it } }
    }
}
