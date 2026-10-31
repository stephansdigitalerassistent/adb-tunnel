package ch.heuscher.adbtunnel

import org.conscrypt.Conscrypt
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.security.KeyPair
import java.security.Principal
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The TLS side of adb: adbd only looks at the public key in the client's certificate, so any
 * self-signed certificate around the paired key will do. Neither does adb check adbd's certificate,
 * and so nor does this: pairing is protected by the code, and everything else stays on loopback.
 */
internal object AdbTls {
    private const val ALIAS = "adb"
    private val SHA256_WITH_RSA = byteArrayOf(0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)
    private val COMMON_NAME = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)

    /**
     * Bundled rather than Android's own, which offers TLS 1.3 but not the keying material pairing
     * needs. Loaded on first use, so a phone that never pairs never loads its native library.
     */
    val provider: Provider by lazy { Conscrypt.newProvider() }

    fun context(key: KeyPair, provider: Provider = this.provider): SSLContext =
        SSLContext.getInstance("TLSv1.3", provider).apply {
            init(arrayOf(OneKey(key.private, certificate(key))), arrayOf(TrustAnyone), SecureRandom())
        }

    /** A hand-built DER certificate: Android has no public API for making one. */
    fun certificate(key: KeyPair): X509Certificate {
        val algorithm = der(0x30, SHA256_WITH_RSA + byteArrayOf(0x05, 0x00))
        val name = der(0x30, der(0x31, der(0x30, COMMON_NAME + der(0x0c, "adbtunnel".toByteArray()))))
        val validity = der(0x30, der(0x17, "200101000000Z".toByteArray()) + der(0x18, "20991231235959Z".toByteArray()))
        val body = der(
            0x30,
            der(0xa0, der(0x02, byteArrayOf(2))) + // version 3
                der(0x02, byteArrayOf(1)) + // serial number
                algorithm + name + validity + name + key.public.encoded
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key.private)
            update(body)
            sign()
        }
        val encoded = der(0x30, body + algorithm + der(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        when {
            content.size < 0x80 -> out.write(content.size)
            content.size < 0x100 -> { out.write(0x81); out.write(content.size) }
            else -> { out.write(0x82); out.write(content.size shr 8); out.write(content.size and 0xff) }
        }
        out.write(content)
        return out.toByteArray()
    }

    private class OneKey(private val key: PrivateKey, private val certificate: X509Certificate) : X509ExtendedKeyManager() {
        // adbd's certificate request names the keys it knows. Ours may be the one, so it always answers.
        override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(type: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = key
    }

    private object TrustAnyone : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    }
}
