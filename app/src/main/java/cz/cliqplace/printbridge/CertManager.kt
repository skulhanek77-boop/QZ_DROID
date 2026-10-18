package cz.cliqplace.printbridge

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.GeneralSubtree
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.NameConstraints
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Vlastní certifikační autorita pro wss://localhost (stejně to dělá QZ Tray na počítači).
 *
 * - CA se vygeneruje jednou na každém zařízení (soukromý klíč zařízení neopustí).
 * - CA je omezená (Name Constraints) jen na "localhost" a 127.0.0.1, takže ji nelze zneužít
 *   k podvržení jiných webů.
 * - Certifikát serveru se obnovuje automaticky.
 */
object CertManager {
    private val PASS = "entradio-tisk".toCharArray()
    private const val DAY = 86_400_000L
    private val rnd = SecureRandom()

    private fun caFile(ctx: Context) = File(ctx.filesDir, "ca.p12")
    private fun serverFile(ctx: Context) = File(ctx.filesDir, "server.p12")

    @Synchronized
    fun sslContext(ctx: Context): SSLContext {
        val (caKey, caCert) = loadOrCreateCa(ctx)
        val server = loadOrCreateServer(ctx, caKey, caCert)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(server, PASS)
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(kmf.keyManagers, null, null)
        return ssl
    }

    @Synchronized
    fun caCertificate(ctx: Context): X509Certificate = loadOrCreateCa(ctx).second

    fun caPem(ctx: Context): String {
        val b64 = Base64.encodeToString(caCertificate(ctx).encoded, Base64.NO_WRAP)
        return "-----BEGIN CERTIFICATE-----\n" + b64.chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"
    }

    /** Uloží CA certifikát do složky Stažené soubory. Vrací popis umístění. */
    fun exportCa(ctx: Context): String {
        val name = "entradio-tisk-ca.crt"
        val bytes = caPem(ctx).toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-x509-ca-cert")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Soubor nelze vytvořit")
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            return "Stažené soubory / $name"
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val f = File(dir, name)
            f.writeBytes(bytes)
            return f.absolutePath
        }
    }

    // ---------- interní ----------

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, rnd) }.generateKeyPair()

    private fun loadOrCreateCa(ctx: Context): Pair<PrivateKey, X509Certificate> {
        val f = caFile(ctx)
        if (f.exists()) {
            try {
                val ks = KeyStore.getInstance("PKCS12")
                f.inputStream().use { ks.load(it, PASS) }
                val key = ks.getKey("ca", PASS) as PrivateKey
                val cert = ks.getCertificate("ca") as X509Certificate
                return key to cert
            } catch (e: Exception) {
                BridgeLog.e("Načtení CA selhalo, vytvářím novou: ${e.message}")
            }
        }
        BridgeLog.i("Vytvářím novou lokální certifikační autoritu…")
        val kp = keyPair()
        val now = Date(System.currentTimeMillis() - DAY)
        val suffix = BigInteger(32, rnd).toString(16)
        val name = X500Name("CN=Entradio Tisk localhost CA $suffix, O=Cliqplace")
        val ext = JcaX509ExtensionUtils()
        val b = JcaX509v3CertificateBuilder(
            name, BigInteger(64, rnd), now, Date(now.time + 3650 * DAY), name, kp.public
        )
        b.addExtension(Extension.basicConstraints, true, BasicConstraints(0))
        b.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(kp.public))
        val permitted = arrayOf(
            GeneralSubtree(GeneralName(GeneralName.dNSName, "localhost")),
            GeneralSubtree(
                GeneralName(
                    GeneralName.iPAddress,
                    DEROctetString(byteArrayOf(127, 0, 0, 1, -1, -1, -1, -1))
                )
            )
        )
        b.addExtension(Extension.nameConstraints, true, NameConstraints(permitted, null))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert = JcaX509CertificateConverter().getCertificate(b.build(signer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("ca", kp.private, PASS, arrayOf<java.security.cert.Certificate>(cert))
        f.outputStream().use { ks.store(it, PASS) }
        serverFile(ctx).delete()
        return kp.private to cert
    }

    private fun loadOrCreateServer(ctx: Context, caKey: PrivateKey, caCert: X509Certificate): KeyStore {
        val f = serverFile(ctx)
        if (f.exists()) {
            try {
                val ks = KeyStore.getInstance("PKCS12")
                f.inputStream().use { ks.load(it, PASS) }
                val cert = ks.getCertificate("server") as X509Certificate
                val valid = cert.notAfter.time > System.currentTimeMillis() + 30 * DAY &&
                        cert.issuerX500Principal == caCert.subjectX500Principal
                if (valid) return ks
            } catch (e: Exception) {
                BridgeLog.w("Certifikát serveru nelze načíst: ${e.message}")
            }
        }
        BridgeLog.i("Vytvářím certifikát serveru pro localhost…")
        val kp = keyPair()
        val now = Date(System.currentTimeMillis() - DAY)
        val ext = JcaX509ExtensionUtils()
        val b = JcaX509v3CertificateBuilder(
            caCert, BigInteger(64, rnd), now, Date(now.time + 397 * DAY), X500Name("CN=localhost"), kp.public
        )
        b.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        b.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        b.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        b.addExtension(
            Extension.subjectAlternativeName, false, GeneralNames(
                arrayOf(
                    GeneralName(GeneralName.dNSName, "localhost"),
                    GeneralName(GeneralName.iPAddress, "127.0.0.1")
                )
            )
        )
        b.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(caCert))
        b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(kp.public))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(caKey)
        val cert = JcaX509CertificateConverter().getCertificate(b.build(signer))

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("server", kp.private, PASS, arrayOf<java.security.cert.Certificate>(cert, caCert))
        f.outputStream().use { ks.store(it, PASS) }
        return ks
    }
}
