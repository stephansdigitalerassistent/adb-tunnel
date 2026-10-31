package ch.heuscher.adbtunnel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

class AdbPairingTest {
    /** RFC 5869, test case 3: no salt and no info. */
    @Test
    fun hkdfMatchesTheRfc() {
        val okm = PairingCipher.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            okm.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun cipherKeepsItsCounters() {
        val sender = PairingCipher(ByteArray(64) { 1 })
        val receiver = PairingCipher(ByteArray(64) { 1 })
        val first = sender.encrypt("one".toByteArray())
        val second = sender.encrypt("one".toByteArray())
        assertArrayEquals("one".toByteArray(), receiver.decrypt(first))
        assertArrayEquals("one".toByteArray(), receiver.decrypt(second))
        assertTrue(!first.contentEquals(second))
    }

    @Test(expected = WrongPairingCode::class)
    fun cipherWithAnotherKeySaysWrongCode() {
        PairingCipher(ByteArray(64) { 2 }).decrypt(PairingCipher(ByteArray(64) { 1 }).encrypt(ByteArray(8)))
    }

    private class Side(val exchange: PairingExchange, val identity: String) {
        var got: ByteArray? = null
        var failure: Throwable? = null
        val runner = thread {
            try {
                got = exchange.run(identity.toByteArray())
            } catch (e: Throwable) {
                failure = e
            }
        }
    }

    private fun exchange(clientCode: String, serverCode: String): Pair<Side, Side> {
        // Both sides write 8 KB before they read.
        val toServer = PipedOutputStream()
        val toClient = PipedOutputStream()
        val fromClient = PipedInputStream(toServer, 1 shl 16)
        val fromServer = PipedInputStream(toClient, 1 shl 16)
        val exported = ByteArray(64) { 7 }
        val client = Side(PairingExchange(fromServer, toServer, clientCode.toByteArray() + exported), "client key")
        val server = Side(PairingExchange(fromClient, toClient, serverCode.toByteArray() + exported, isClient = false), "server key")
        client.runner.join(30_000)
        server.runner.join(30_000)
        return client to server
    }

    @Test
    fun eachSideGetsTheOthersIdentity() {
        val (client, server) = exchange("123456", "123456")
        assertEquals(null, client.failure)
        assertEquals(null, server.failure)
        assertEquals(PairingExchange.PEER_INFO_SIZE, server.got!!.size)
        assertEquals(0, server.got!![0].toInt()) // ADB_RSA_PUB_KEY
        assertEquals("client key", String(server.got!!, 1, 10))
        assertEquals("server key", String(client.got!!, 1, 10))
    }

    @Test
    fun wrongCodeFailsOnBothSides() {
        val (client, server) = exchange("123456", "654321")
        assertTrue(client.failure is WrongPairingCode)
        assertTrue(server.failure is WrongPairingCode)
    }
}
