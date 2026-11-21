package com.victorious.sms.mail

import java.io.File
import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Generates a throw-away server certificate (CN/SAN localhost + 127.0.0.1) and a client policy that trusts only it. */
object TestTls {
    val dir: File = File.createTempFile("tls", "").let { it.delete(); it.mkdirs(); it }
    val keystore = File(dir, "server.p12")
    const val PASSWORD = "changeit"
    val policy: TlsPolicy

    init {
        val kt = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(kt, "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
            "-keystore", keystore.path, "-storepass", PASSWORD, "-keypass", PASSWORD).redirectErrorStream(true).start()
        check(p.waitFor() == 0) { p.inputStream.readBytes().decodeToString() }
        System.setProperty("greenmail.tls.keystore.file", keystore.path)
        System.setProperty("greenmail.tls.keystore.password", PASSWORD)
        val ks = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, PASSWORD.toCharArray()) } }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        policy = TlsPolicy(SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory)
    }
}
