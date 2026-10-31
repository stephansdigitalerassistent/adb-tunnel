package ch.heuscher.adbtunnel

import org.conscrypt.Conscrypt
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** The code was not the one on the phone's screen, or the dialog showing it has closed. */
internal class WrongPairingCode : IOException("wrong pairing code")

internal object AdbPairing {
    // The size of adb's C string, closing NUL included.
    private const val EXPORT_LABEL = "adb-label\u0000"
    private const val EXPORT_SIZE = 64
    private const val TIMEOUT_MS = 10_000

    /** Hands [publicKey] to this phone's adbd, which offers pairing on [port] while its dialog is open. */
    fun pair(port: Int, code: String, tls: SSLContext, publicKey: ByteArray) {
        (tls.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.connect(InetSocketAddress(AdbPort.LOOPBACK, port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.startHandshake()
            // Ties the code to this very TLS session, so nobody can sit in the middle of it.
            val exported = Conscrypt.exportKeyingMaterial(socket, EXPORT_LABEL, null, EXPORT_SIZE)
            PairingExchange(socket.inputStream, socket.outputStream, code.toByteArray(Charsets.UTF_8) + exported)
                .run(publicKey)
        }
    }
}

/**
 * What Wireless debugging pairing does inside its TLS connection (pairing_connection.cpp): SPAKE2
 * over the code and the TLS session, then each side hands the other its identity, encrypted with the
 * resulting key. Our identity is the adb public key, which adbd then adds to the keys it trusts.
 *
 * Both sides of the protocol write first and read second, so the same code is either side.
 */
internal class PairingExchange(
    private val input: InputStream,
    private val output: OutputStream,
    /** The pairing code, then the 64 bytes exported from the TLS session. */
    password: ByteArray,
    isClient: Boolean = true
) {
    private val spake = Spake2(isClient, if (isClient) CLIENT else SERVER, if (isClient) SERVER else CLIENT, password)

    /** Sends [publicKey] as our identity. Returns the 8192-byte identity the other side sent. */
    fun run(publicKey: ByteArray): ByteArray {
        write(TYPE_SPAKE2, spake.message)
        val key = spake.finish(read(TYPE_SPAKE2)) ?: throw IOException("bad SPAKE2 message")
        val cipher = PairingCipher(key)

        require(publicKey.size < PEER_INFO_SIZE) { "key too long: ${publicKey.size}" }
        val ours = ByteArray(PEER_INFO_SIZE)
        ours[0] = PEER_RSA_PUBLIC_KEY
        publicKey.copyInto(ours, 1)
        write(TYPE_PEER_INFO, cipher.encrypt(ours))
        val theirs = cipher.decrypt(read(TYPE_PEER_INFO))
        if (theirs.size != PEER_INFO_SIZE) throw IOException("peer info of ${theirs.size} bytes")
        return theirs
    }

    private fun write(type: Byte, payload: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(HEADER_VERSION).put(type).putInt(payload.size)
        output.write(header.array())
        output.write(payload)
        output.flush()
    }

    private fun read(expected: Byte): ByteArray {
        val data = DataInputStream(input)
        val header = ByteArray(HEADER_SIZE).also(data::readFully)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get()
        val type = buffer.get()
        val size = buffer.getInt()
        if (version != HEADER_VERSION || type != expected || size !in 1..MAX_PAYLOAD) {
            throw IOException("bad pairing packet: version $version, type $type, $size bytes")
        }
        return ByteArray(size).also(data::readFully)
    }

    companion object {
        private const val HEADER_VERSION: Byte = 1
        private const val HEADER_SIZE = 6
        private const val TYPE_SPAKE2: Byte = 0
        private const val TYPE_PEER_INFO: Byte = 1
        private const val PEER_RSA_PUBLIC_KEY: Byte = 0
        const val PEER_INFO_SIZE = 8192
        private const val MAX_PAYLOAD = PEER_INFO_SIZE * 2

        // Both names with their C string's closing NUL: adb passes sizeof().
        private val CLIENT = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        private val SERVER = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)
    }
}

/** AES-128-GCM as in aes_128_gcm.cpp: a counter for a nonce, one per direction, no associated data. */
internal class PairingCipher(keyMaterial: ByteArray) {
    private val key = SecretKeySpec(hkdfSha256(keyMaterial, INFO, KEY_SIZE), "AES")
    private var encrypted = 0L
    private var decrypted = 0L

    fun encrypt(plain: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE, encrypted++).doFinal(plain)

    fun decrypt(sealed: ByteArray): ByteArray = try {
        cipher(Cipher.DECRYPT_MODE, decrypted++).doFinal(sealed)
    } catch (e: AEADBadTagException) {
        // The two sides computed different keys, which is what a different code comes to.
        throw WrongPairingCode()
    }

    private fun cipher(mode: Int, sequence: Long): Cipher {
        val nonce = ByteBuffer.allocate(NONCE_SIZE).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
        }
    }

    companion object {
        private const val KEY_SIZE = 16
        private const val NONCE_SIZE = 12
        private const val TAG_BITS = 128
        private val INFO = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)

        /** RFC 5869 with an empty salt. */
        fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
            val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32), "HmacSHA256")) }
            val expand = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(extract.doFinal(ikm), "HmacSHA256"))
            }
            val out = ByteArray(length)
            var block = ByteArray(0)
            var written = 0
            var counter = 1
            while (written < length) {
                expand.update(block)
                expand.update(info)
                block = expand.doFinal(byteArrayOf(counter++.toByte()))
                val chunk = minOf(block.size, length - written)
                block.copyInto(out, written, 0, chunk)
                written += chunk
            }
            return out
        }
    }
}
