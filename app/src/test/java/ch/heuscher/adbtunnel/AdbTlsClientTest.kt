package ch.heuscher.adbtunnel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/** On the JDK's TLS, as Conscrypt's native library is no use on a build machine. */
class AdbTlsClientTest {
    private val jdk = SSLContext.getDefault().provider
    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val running = mutableListOf<Closeable>()

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private class Link(private val input: InputStream, private val output: OutputStream) {
        fun read(): Message {
            val stream = DataInputStream(input)
            val header = ByteArray(24).also(stream::readFully)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.getInt()
            val arg0 = buffer.getInt()
            val arg1 = buffer.getInt()
            return Message(command, arg0, arg1, ByteArray(buffer.getInt()).also(stream::readFully))
        }

        fun write(command: Int, arg0: Int, arg1: Int, data: String = "") {
            val bytes = data.toByteArray()
            val packet = ByteBuffer.allocate(24 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(bytes.size).putInt(0).putInt(command xor -1)
                .put(bytes)
            output.write(packet.array())
            output.flush()
        }
    }

    private object AnyClient : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    }

    private object NoClient : X509TrustManager by AnyClient {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("unknown key")
    }

    private fun adbdContext(trust: TrustManager): SSLContext {
        val password = "test".toCharArray()
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("adbd", key.private, password, arrayOf(AdbTls.certificate(key)))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        return SSLContext.getInstance("TLSv1.3", jdk).apply { init(keys.keyManagers, arrayOf(trust), null) }
    }

    /** adbd up to the end of the handshake, then [script]. Returns the port. */
    private fun fakeAdbd(trust: TrustManager = AnyClient, afterStls: (Socket) -> Boolean = { true }, script: (Link) -> Unit = {}): Int {
        val context = adbdContext(trust)
        val server = ServerSocket(0, 1, InetAddress.getByName(AdbPort.LOOPBACK)).also { running += it }
        thread(isDaemon = true) {
            try {
                val plain = server.accept().also { running += it }
                val clear = Link(plain.getInputStream(), plain.getOutputStream())
                assertEquals(AdbProtocol.A_CNXN, clear.read().command)
                clear.write(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0)
                assertEquals(AdbProtocol.A_STLS, clear.read().command)
                if (afterStls(plain)) {
                    val secure = context.socketFactory.createSocket(plain, AdbPort.LOOPBACK, plain.port, false) as SSLSocket
                    secure.useClientMode = false
                    secure.needClientAuth = true
                    secure.startHandshake()
                    val link = Link(secure.inputStream, secure.outputStream)
                    link.write(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_PAYLOAD, "device::")
                    script(link)
                }
                plain.close()
            } catch (e: IOException) {
                // The socket stays open for tearDown: the client has an alert to read.
            }
        }
        return server.localPort
    }

    private fun connect(port: Int) = AdbTlsClient.connect(port, AdbTls.context(key, jdk))

    @After
    fun tearDown() = running.forEach { it.close() }

    @Test
    fun runsOneServiceAfterTheOther() {
        val port = fakeAdbd { adbd ->
            val first = adbd.read()
            assertEquals(AdbProtocol.A_OPEN, first.command)
            assertEquals("shell:one\u0000", String(first.data))
            adbd.write(AdbProtocol.A_OKAY, 100, first.arg0)
            adbd.write(AdbProtocol.A_WRTE, 100, first.arg0, "first")
            assertEquals(AdbProtocol.A_OKAY, adbd.read().command)
            adbd.write(AdbProtocol.A_CLSE, 100, first.arg0)
            assertEquals(AdbProtocol.A_CLSE, adbd.read().command)

            val second = adbd.read()
            // The real adbd sends more than one goodbye: the Fold's did, on 2026-10-31.
            adbd.write(AdbProtocol.A_CLSE, 0, first.arg0)
            adbd.write(AdbProtocol.A_OKAY, 101, second.arg0)
            adbd.write(AdbProtocol.A_WRTE, 101, second.arg0, "second")
            adbd.read()
            adbd.write(AdbProtocol.A_CLSE, 101, second.arg0)
            adbd.read()
        }
        connect(port).use { client ->
            assertEquals("first", client.run("shell:one"))
            assertEquals("second", client.run("shell:two"))
        }
    }

    @Test
    fun keepsWhatAServiceSaidBeforeTheConnectionEnded() {
        val port = fakeAdbd { adbd ->
            val open = adbd.read()
            adbd.write(AdbProtocol.A_OKAY, 100, open.arg0)
            adbd.write(AdbProtocol.A_WRTE, 100, open.arg0, "restarting in TCP mode port: 5555\n")
        }
        connect(port).use { assertEquals("restarting in TCP mode port: 5555\n", it.run("tcpip:5555")) }
    }

    @Test
    fun aRefusedServiceFails() {
        val port = fakeAdbd { adbd ->
            adbd.write(AdbProtocol.A_CLSE, 0, adbd.read().arg0)
            adbd.read()
        }
        connect(port).use { client -> assertThrows(IOException::class.java) { client.run("shell:nope") } }
    }

    @Test
    fun aRejectedCertificateIsARefusal() {
        val port = fakeAdbd(NoClient)
        assertThrows(AdbKeyRefused::class.java) { connect(port) }
    }

    /** What the Fold's adbd did to good and bad keys alike: no verdict, so no reason to pair again. */
    @Test
    fun aConnectionEndingInTheHandshakeIsNoRefusal() {
        val port = fakeAdbd(afterStls = { false })
        val failure = assertThrows(IOException::class.java) { connect(port) }
        assertFalse(failure is AdbKeyRefused)
    }

    @Test
    fun certificateCarriesTheKey() {
        val certificate = AdbTls.certificate(key)
        certificate.verify(key.public)
        certificate.checkValidity()
        assertEquals(key.public, certificate.publicKey)
    }
}
