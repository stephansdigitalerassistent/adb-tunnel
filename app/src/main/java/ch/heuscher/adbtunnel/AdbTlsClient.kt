package ch.heuscher.adbtunnel

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** adbd turned the certificate down: it does not, or no longer, trust the key. */
internal class AdbKeyRefused(cause: Throwable) : IOException("adbd refused the key", cause)

/**
 * An adb host speaking to a Wireless debugging port: CNXN in the clear, adbd's STLS asking for TLS,
 * then the rest over TLS on the same socket, the client certificate standing in for adb's RSA
 * challenge. The host's own adb (33.0.3) cannot do this against Android 11, this can.
 */
internal class AdbTlsClient private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream
) : Closeable {
    private var nextLocalId = 1

    /** Runs one adb service, `shell:…` or `tcpip:5555`, and returns all it wrote. */
    fun run(service: String): String {
        val localId = nextLocalId++
        write(output, AdbProtocol.A_OPEN, localId, 0, (service + "\u0000").toByteArray(Charsets.UTF_8))
        val text = ByteArrayOutputStream()
        var remoteId = 0
        try {
            while (true) {
                val packet = read(input)
                // The service before this one may still be saying goodbye.
                if (packet.arg1 != localId) continue
                when (packet.command) {
                    AdbProtocol.A_OKAY -> remoteId = packet.arg0
                    AdbProtocol.A_WRTE -> {
                        text.write(packet.data)
                        write(output, AdbProtocol.A_OKAY, localId, remoteId, ByteArray(0))
                    }
                    AdbProtocol.A_CLSE -> {
                        if (remoteId == 0) throw IOException("$service refused")
                        write(output, AdbProtocol.A_CLSE, localId, remoteId, ByteArray(0))
                        return text.toString("UTF-8")
                    }
                }
            }
        } catch (e: IOException) {
            // tcpip restarts adbd, which may end the connection before it closes the stream.
            if (text.size() > 0) return text.toString("UTF-8")
            throw e
        }
    }

    override fun close() = socket.close()

    companion object {
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 10_000
        private const val HEADER_SIZE = 24
        private const val CERTIFICATE_UNKNOWN = "certificate_unknown"

        fun connect(port: Int, tls: SSLContext): AdbTlsClient {
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress(AdbPort.LOOPBACK, port), CONNECT_TIMEOUT_MS)
                plain.soTimeout = READ_TIMEOUT_MS
                val banner = "host::adb-tunnel\u0000".toByteArray(Charsets.US_ASCII)
                write(plain.getOutputStream(), AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.MAX_PAYLOAD, banner)
                val reply = read(plain.getInputStream())
                if (reply.command != AdbProtocol.A_STLS) throw IOException("adbd on :$port does not ask for TLS")
                write(plain.getOutputStream(), AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0, ByteArray(0))

                val secure = tls.socketFactory.createSocket(plain, AdbPort.LOOPBACK, port, true) as SSLSocket
                secure.useClientMode = true
                val input: InputStream
                try {
                    secure.startHandshake()
                    input = secure.inputStream
                    // TLS 1.3 finishes on the client's side before adbd has seen the certificate, so
                    // its verdict comes here: a CNXN, or an alert.
                    if (read(input).command != AdbProtocol.A_CNXN) throw IOException("no CNXN after TLS")
                } catch (e: SSLException) {
                    // Only this alert is a verdict. A connection can also just end mid-handshake.
                    throw if (e.message?.contains(CERTIFICATE_UNKNOWN, ignoreCase = true) == true) AdbKeyRefused(e) else e
                }
                return AdbTlsClient(secure, input, secure.outputStream)
            } catch (e: Exception) {
                plain.close()
                throw e
            }
        }

        private class Packet(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

        private fun write(output: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
            val packet = ByteBuffer.allocate(HEADER_SIZE + data.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
                .putInt(data.fold(0) { sum, b -> sum + (b.toInt() and 0xff) })
                .putInt(command xor -1)
                .put(data)
            output.write(packet.array())
            output.flush()
        }

        private fun read(input: InputStream): Packet {
            val stream = DataInputStream(input)
            val header = ByteArray(HEADER_SIZE).also(stream::readFully)
            val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = buffer.getInt()
            val arg0 = buffer.getInt()
            val arg1 = buffer.getInt()
            val size = buffer.getInt()
            buffer.getInt()
            if (buffer.getInt() != command xor -1 || size !in 0..AdbProtocol.MAX_PAYLOAD) {
                throw IOException("bad adb packet")
            }
            return Packet(command, arg0, arg1, ByteArray(size).also(stream::readFully))
        }
    }
}
