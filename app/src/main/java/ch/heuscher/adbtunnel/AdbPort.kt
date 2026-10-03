package ch.heuscher.adbtunnel

import android.util.Log
import java.io.DataInputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds the port this phone's adbd is listening on.
 *
 * Wireless debugging picks a new random port every time it is switched on, and a reboot or system
 * update also drops the fixed 5555 that `adb tcpip` sets — which is exactly what left the old
 * tunnel pointing at nothing. So nothing here is trusted from memory: 5555 is tried first, then
 * the port of the last scan, then every ephemeral port on loopback, and a listener only counts
 * when it answers an adb handshake.
 */
object AdbPort {
    private const val FIXED = 5555
    private val EPHEMERAL = 32768..60999

    // Few threads: with 64 a Galaxy S10+ spent over a minute per pass at full load and still
    // missed the port adbd was listening on. Even with 8 it leaves hundreds of connects without an
    // answer, so the first pass does not wait long for one and the second look does.
    private const val SCAN_THREADS = 8
    private const val SCAN_TIMEOUT_MS = 300
    private const val RETRY_TIMEOUT_MS = 2_000
    private const val RETRY_BUDGET_MS = 30_000
    private const val NONE = -1

    /** The port of the last successful scan: adbd keeps it until Wireless debugging restarts. */
    @Volatile
    private var lastFound = NONE

    fun find(): Int? {
        if (isAdb(FIXED)) return FIXED
        lastFound.takeIf { it != NONE && isAdb(it) }?.let { return it }
        val started = System.currentTimeMillis()
        val found = AtomicInteger(NONE)
        val unsure = ConcurrentLinkedQueue<Int>()
        val next = AtomicInteger(EPHEMERAL.first)
        val pool = Executors.newFixedThreadPool(SCAN_THREADS)
        try {
            (1..SCAN_THREADS).map {
                pool.submit(Callable {
                    while (found.get() == NONE && !Thread.currentThread().isInterrupted) {
                        val port = next.getAndIncrement()
                        if (port > EPHEMERAL.last) break
                        when (probe(port, SCAN_TIMEOUT_MS)) {
                            Probe.OPEN -> if (isAdb(port)) found.compareAndSet(NONE, port)
                            Probe.UNSURE -> unsure.add(port)
                            Probe.CLOSED -> {}
                        }
                    }
                })
            }.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        // A port that neither accepted nor refused in time gets a second look, one at a time: when
        // the scan's own load was the reason, it answers at once now. Bounded, in case it was not.
        val scanMs = System.currentTimeMillis() - started
        if (found.get() == NONE) {
            val deadline = System.currentTimeMillis() + RETRY_BUDGET_MS
            unsure.asSequence()
                .takeWhile { System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted }
                .firstOrNull { probe(it, RETRY_TIMEOUT_MS) == Probe.OPEN && isAdb(it) }
                ?.let { found.set(it) }
        }
        val port = found.get().takeIf { it != NONE }
        Log.i(
            TAG,
            "port scan: ${port ?: "nothing"} after $scanMs ms, ${unsure.size} without an answer, " +
                "${System.currentTimeMillis() - started - scanMs} ms on a second look"
        )
        lastFound = port ?: NONE
        return port
    }

    private enum class Probe { OPEN, CLOSED, UNSURE }

    private fun probe(port: Int, timeoutMs: Int): Probe = try {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), timeoutMs) }
        Probe.OPEN
    } catch (e: ConnectException) {
        Probe.CLOSED
    } catch (e: Exception) {
        Probe.UNSURE
    }

    /** Sends an adb CNXN and reads the first reply. adbd answers CNXN or AUTH (legacy tcpip mode)
     * or STLS (Wireless debugging). Nothing past that is sent, so no authorization prompt appears. */
    fun isAdb(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(LOOPBACK, port), 1_000)
            socket.soTimeout = 3_000
            val payload = "host::\u0000".toByteArray(Charsets.US_ASCII)
            val checksum = payload.fold(0) { sum, b -> sum + (b.toInt() and 0xff) }
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(A_CNXN).putInt(A_VERSION).putInt(MAX_PAYLOAD)
                .putInt(payload.size).putInt(checksum).putInt(A_CNXN xor -1)
            socket.getOutputStream().apply { write(header.array()); write(payload); flush() }
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
