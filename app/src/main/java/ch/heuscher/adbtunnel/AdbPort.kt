package ch.heuscher.adbtunnel

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Finds the port this phone's adbd is listening on.
 *
 * Wireless debugging picks a new random port every time it is switched on, and a reboot or system
 * update also drops the fixed 5555 that `adb tcpip` sets — which is exactly what left the old
 * tunnel pointing at nothing. So nothing here is remembered: 5555 is tried first, then every
 * ephemeral port on loopback, and a listener only counts when it answers an adb handshake.
 */
object AdbPort {
    private const val FIXED = 5555
    private val EPHEMERAL = 32768..60999

    fun find(): Int? {
        if (isAdb(FIXED)) return FIXED
        val pool = Executors.newFixedThreadPool(64)
        try {
            val open = EPHEMERAL.chunked(512)
                .map { chunk -> pool.submit(Callable { chunk.filter(::isOpen) }) }
                .flatMap { it.get() }
            return open.firstOrNull(::isAdb)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun isOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 200) }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Sends an adb CNXN and reads the first reply. adbd answers CNXN or AUTH (legacy tcpip mode)
     * or STLS (Wireless debugging). Nothing past that is sent, so no authorization prompt appears.
     */
    fun isAdb(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, port), 500)
            socket.soTimeout = 1500
            val payload = "host::\u0000".toByteArray(Charsets.US_ASCII)
            val checksum = payload.fold(0) { sum, b -> sum + (b.toInt() and 0xff) }
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(A_CNXN).putInt(A_VERSION).putInt(MAX_PAYLOAD)
                .putInt(payload.size).putInt(checksum).putInt(A_CNXN xor -1)
            socket.getOutputStream().apply {
                write(header.array())
                write(payload)
                flush()
            }
            val reply = ByteArray(4)
            DataInputStream(socket.getInputStream()).readFully(reply)
            String(reply, Charsets.US_ASCII) in ADBD_REPLIES
        }
    } catch (e: Exception) {
        false
    }

    const val LOOPBACK = "127.0.0.1"
    private const val A_CNXN = 0x4e584e43
    private const val A_VERSION = 0x01000001
    private const val MAX_PAYLOAD = 1024 * 1024
    private val ADBD_REPLIES = setOf("CNXN", "AUTH", "STLS")
}
