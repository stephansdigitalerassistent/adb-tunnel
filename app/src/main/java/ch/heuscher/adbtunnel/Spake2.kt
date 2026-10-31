package ch.heuscher.adbtunnel

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 over edwards25519, as BoringSSL's SPAKE2_generate_msg and SPAKE2_process_msg do it: Android
 * uses those for Wireless debugging pairing, and no Java library has this variant. Both sides turn
 * the same password into the same 64-byte key, and neither learns the password from the other's
 * message.
 *
 * Plain BigInteger arithmetic, so not constant time. A pairing code lives for one dialog and only
 * travels over the phone's own loopback.
 *
 * [random] is only there so a test can fix the private key.
 */
internal class Spake2(
    private val isAlice: Boolean,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    password: ByteArray,
    random: (ByteArray) -> Unit = SecureRandom()::nextBytes
) {
    private val passwordHash = MessageDigest.getInstance("SHA-512").digest(password)
    private val passwordScalar = scalarFor(passwordHash)
    private val privateKey: BigInteger

    /** What goes to the other side, 32 bytes. */
    val message: ByteArray

    init {
        val seed = ByteArray(64).also(random)
        // Times eight, the cofactor, so that small-order parts of the peer's point cancel out.
        privateKey = littleEndian(seed).mod(ORDER).shiftLeft(3)
        message = (BASE * privateKey + (if (isAlice) M else N) * passwordScalar).encode()
    }

    /** The shared 64-byte key. Null when [theirMessage] is no point on the curve. */
    fun finish(theirMessage: ByteArray): ByteArray? {
        if (theirMessage.size != 32) return null
        val theirs = Point.decode(theirMessage) ?: return null
        val shared = (theirs + -((if (isAlice) N else M) * passwordScalar)) * privateKey
        val sha = MessageDigest.getInstance("SHA-512")
        fun add(data: ByteArray) {
            for (i in 0 until 8) sha.update(((data.size.toLong() shr (8 * i)) and 0xff).toByte())
            sha.update(data)
        }
        add(if (isAlice) myName else theirName)
        add(if (isAlice) theirName else myName)
        add(if (isAlice) message else theirMessage)
        add(if (isAlice) theirMessage else message)
        add(shared.encode())
        add(passwordHash)
        return sha.digest()
    }

    /**
     * A point in extended coordinates (X:Y:Z:T). The addition below is complete for this curve, so
     * it also doubles.
     */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger) {

        operator fun plus(o: Point): Point {
            val a = (y - x).mod(P) * (o.y - o.x).mod(P) % P
            val b = (y + x) * (o.y + o.x) % P
            val c = t * TWO_D % P * o.t % P
            val d = z * o.z % P * TWO % P
            val e = (b - a).mod(P)
            val f = (d - c).mod(P)
            val g = (d + c) % P
            val h = (b + a) % P
            return Point(e * f % P, g * h % P, f * g % P, e * h % P)
        }

        operator fun unaryMinus() = Point(P - x, y, z, P - t)

        operator fun times(scalar: BigInteger): Point {
            var result = IDENTITY
            for (bit in scalar.bitLength() - 1 downTo 0) {
                result += result
                if (scalar.testBit(bit)) result += this
            }
            return result
        }

        fun encode(): ByteArray {
            val inverse = z.modInverse(P)
            val affineX = x * inverse % P
            val out = toLittleEndian(y * inverse % P)
            if (affineX.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
            return out
        }

        companion object {
            fun decode(encoded: ByteArray): Point? {
                val negative = encoded[31].toInt() and 0x80 != 0
                val y = littleEndian(encoded.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }).mod(P)
                return of(recoverX(y, negative) ?: return null, y)
            }

            fun of(x: BigInteger, y: BigInteger) = Point(x, y, BigInteger.ONE, x * y % P)

            /** x² = (y² - 1) / (dy² + 1). Null when that has no root: then y is not on the curve. */
            private fun recoverX(y: BigInteger, negative: Boolean): BigInteger? {
                val yy = y * y % P
                val xx = (yy - BigInteger.ONE) * (D * yy + BigInteger.ONE).modInverse(P) % P
                var x = xx.modPow((P + THREE).shiftRight(3), P)
                if ((x * x - xx).mod(P).signum() != 0) x = x * SQRT_M1 % P
                if ((x * x - xx).mod(P).signum() != 0) return null
                if (x.testBit(0) != negative) x = (P - x).mod(P)
                return x
            }
        }
    }

    internal companion object {
        private val TWO = BigInteger.valueOf(2) // BigInteger.TWO needs Android 13
        private val THREE = BigInteger.valueOf(3)
        private val P = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
        private val ORDER = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
        private val D = BigInteger.valueOf(-121665).mod(P) * BigInteger.valueOf(121666).modInverse(P) % P
        private val TWO_D = D * TWO % P
        private val SQRT_M1 = TWO.modPow((P - BigInteger.ONE).shiftRight(2), P)
        private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        private val BASE = Point.decode(hex("5866666666666666666666666666666666666666666666666666666666666666"))!!

        /** BoringSSL's two fixed points. [pointFrom] with their seeds gives the same ones. */
        private val M = Point.decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))!!
        private val N = Point.decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))!!

        /**
         * The password hash as a scalar, a multiple of eight. BoringSSL's rounding up is part of the
         * protocol (a late fix for a missing multiplication), so it is copied bit by bit: adding
         * multiples of the group order changes the point only by its small-order part.
         */
        private fun scalarFor(hash: ByteArray): BigInteger {
            var scalar = littleEndian(hash).mod(ORDER)
            for (i in 0..2) {
                if (scalar.testBit(i)) scalar += ORDER.shiftLeft(i)
            }
            return scalar
        }

        /** The first point SHA-256 chains from [seed] to. How BoringSSL's authors chose M and N. */
        fun pointFrom(seed: String): ByteArray {
            var v = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.US_ASCII))
            while (Point.decode(v) == null) v = MessageDigest.getInstance("SHA-256").digest(v)
            return v
        }

        fun encodedM() = M.encode()
        fun encodedN() = N.encode()

        private fun littleEndian(bytes: ByteArray) = BigInteger(1, bytes.reversedArray())

        private fun toLittleEndian(value: BigInteger): ByteArray {
            val out = ByteArrayOutputStream(32)
            for (i in 0 until 32) out.write(value.shiftRight(8 * i).toInt() and 0xff)
            return out.toByteArray()
        }

        private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
