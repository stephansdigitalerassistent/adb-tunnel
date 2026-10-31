package ch.heuscher.adbtunnel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class Spake2Test {
    private val client = "adb pair client\u0000".toByteArray()
    private val server = "adb pair server\u0000".toByteArray()

    private fun alice(password: String, random: ((ByteArray) -> Unit)? = null) =
        if (random == null) Spake2(true, client, server, password.toByteArray())
        else Spake2(true, client, server, password.toByteArray(), random)

    private fun bob(password: String, random: ((ByteArray) -> Unit)? = null) =
        if (random == null) Spake2(false, server, client, password.toByteArray())
        else Spake2(false, server, client, password.toByteArray(), random)

    @Test
    fun samePasswordGivesSameKey() {
        val a = alice("123456")
        val b = bob("123456")
        val key = a.finish(b.message)!!
        assertEquals(64, key.size)
        assertArrayEquals(key, b.finish(a.message))
    }

    @Test
    fun differentPasswordGivesDifferentKeys() {
        val a = alice("123456")
        val b = bob("654321")
        assertFalse(a.finish(b.message).contentEquals(b.finish(a.message)))
    }

    @Test
    fun messageOfTheWrongSizeIsRefused() {
        assertNull(alice("123456").finish(ByteArray(31)))
    }

    /** The two points are nothing up BoringSSL's sleeve, and nothing mistyped here. */
    @Test
    fun fixedPointsComeFromTheirSeeds() {
        assertArrayEquals(Spake2.pointFrom("edwards25519 point generation seed (M)"), Spake2.encodedM())
        assertArrayEquals(Spake2.pointFrom("edwards25519 point generation seed (N)"), Spake2.encodedN())
    }

    /** Taken from the code that paired with the real `adb pair` (33.0.3): any change breaks that. */
    @Test
    fun staysCompatible() {
        val a = alice("123456") { it.fill(0x11) }
        val b = bob("123456") { it.fill(0x22) }
        assertEquals("0217169d8949c1dc9fb329389e4ac43642ffe40e30c40937c80baf364c0bc837", hex(a.message))
        assertEquals("711caacf0a733bad2eb43ee53459aee57f773c5cd93d83c2cbfe7ff056b4a28e", hex(b.message))
        assertEquals("755837a97b274f676d8347985013fb25452f642b041af4113ac990308910dc9ac3df62a775385f717e3cb29709017d59bdc61fa97e48c0845f367f508ff394cf", hex(a.finish(b.message)!!))
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
