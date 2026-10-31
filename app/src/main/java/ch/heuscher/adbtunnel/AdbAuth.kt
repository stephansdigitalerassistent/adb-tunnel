package ch.heuscher.adbtunnel

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import javax.crypto.Cipher

/**
 * Manages the RSA keypair used for authenticating to USB-connected ADB devices (rescue mode).
 * Encodes the public key into Android's native mincrypt binary format (524 bytes).
 */
object AdbAuth {
    private const val PRIVATE_KEY_FILE = "adbkey"
    private const val PUBLIC_KEY_FILE = "adbkey.pub"

    private val ASN1_SHA1 = byteArrayOf(
        0x30.toByte(), 0x21.toByte(), 0x30.toByte(), 0x09.toByte(), 0x06.toByte(),
        0x05.toByte(), 0x2b.toByte(), 0x0e.toByte(), 0x03.toByte(), 0x02.toByte(),
        0x1a.toByte(), 0x05.toByte(), 0x00.toByte(), 0x04.toByte(), 0x14.toByte()
    )

    @Synchronized
    fun getKeyPair(context: Context): KeyPair {
        val filesDir = Storage.of(context).filesDir
        val privFile = File(filesDir, PRIVATE_KEY_FILE)
        val pubFile = File(filesDir, PUBLIC_KEY_FILE)

        if (privFile.exists() && pubFile.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                return KeyPair(pub, priv)
            } catch (e: Exception) {
                Log.w(TAG, "Error loading adb keys, generating fresh pair: ${e.message}")
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        try {
            privFile.writeBytes(kp.private.encoded)
            pubFile.writeBytes(kp.public.encoded)
        } catch (e: Exception) {
            Log.w(TAG, "Could not save adb keys: ${e.message}")
        }
        return kp
    }

    fun signToken(context: Context, token: ByteArray): ByteArray {
        val kp = getKeyPair(context)
        val toSign = ByteArray(ASN1_SHA1.size + token.size)
        System.arraycopy(ASN1_SHA1, 0, toSign, 0, ASN1_SHA1.size)
        System.arraycopy(token, 0, toSign, ASN1_SHA1.size, token.size)

        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, kp.private)
        return cipher.doFinal(toSign)
    }

    fun getPublicKeyString(context: Context): String {
        val kp = getKeyPair(context)
        val pubKey = kp.public as RSAPublicKey
        val encoded = encodeAdbPublicKey(pubKey)
        val b64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
        val comment = "adbtunnel@" + Build.MODEL.replace(' ', '-')
        return "$b64 $comment\u0000"
    }

    fun encodeAdbPublicKey(publicKey: RSAPublicKey): ByteArray {
        val modulus = publicKey.modulus
        val pubExponent = publicKey.publicExponent
        val bitLength = modulus.bitLength()
        require(bitLength == 2048) { "Expected 2048-bit RSA, got $bitLength" }

        val modulusSize = bitLength / 8 // 256 bytes
        val modulusSizeWords = modulusSize / 4 // 64 words

        // Montgomery parameter: n0inv = -1 / n[0] mod 2^32
        val r32 = BigInteger.ZERO.setBit(32)
        val n0 = modulus.remainder(r32)
        val n0invBig = r32.subtract(n0.modInverse(r32)).remainder(r32)
        val n0invVal = n0invBig.toLong() and 0xFFFFFFFFL

        val modulusBytes = padOrTrimLittleEndian(modulus.toByteArray(), modulusSize)

        // Montgomery parameter: R^2 = (2^2048)^2 mod N
        val r = BigInteger.ZERO.setBit(bitLength)
        val rrBig = r.modPow(BigInteger.valueOf(2), modulus)
        val rrBytes = padOrTrimLittleEndian(rrBig.toByteArray(), modulusSize)

        // Structure size: len (4) + n0inv (4) + n (256) + rr (256) + exponent (4) = 524 bytes
        val structSize = 4 + 4 + modulusSize + modulusSize + 4
        val buffer = ByteBuffer.allocate(structSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(modulusSizeWords)
        buffer.putInt(n0invVal.toInt())
        buffer.put(modulusBytes)
        buffer.put(rrBytes)
        buffer.putInt(pubExponent.toInt())

        return buffer.array()
    }

    private fun padOrTrimLittleEndian(src: ByteArray, targetLength: Int): ByteArray {
        var cleaned = src
        if (cleaned.size > targetLength && cleaned[0] == 0.toByte()) {
            cleaned = Arrays.copyOfRange(cleaned, 1, cleaned.size)
        }
        val le = ByteArray(targetLength)
        for (i in 0 until cleaned.size.coerceAtMost(targetLength)) {
            le[i] = cleaned[cleaned.size - 1 - i]
        }
        return le
    }
}
