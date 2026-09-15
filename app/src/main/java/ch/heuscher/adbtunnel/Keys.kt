package ch.heuscher.adbtunnel

import android.content.Context
import android.os.Build
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * This phone's login key: made on first use, kept in app-private storage, never exported.
 *
 * ECDSA P-256 rather than Ed25519 because Android's own crypto provider does P-256 on every
 * version this app supports, so JSch needs no bundled crypto library to use it.
 */
object Keys {
    private const val PRIVATE = "id_ecdsa"
    private const val PUBLIC = "id_ecdsa.pub"

    fun privateKey(context: Context): ByteArray {
        ensure(context)
        return File(context.filesDir, PRIVATE).readBytes()
    }

    fun publicKey(context: Context): String {
        ensure(context)
        return File(context.filesDir, PUBLIC).readText().trim()
    }

    /** Whether a key was ever made, without making one. */
    fun exists(context: Context): Boolean = File(context.filesDir, PRIVATE).exists()

    @Synchronized
    private fun ensure(context: Context) {
        val privateFile = File(context.filesDir, PRIVATE)
        val publicFile = File(context.filesDir, PUBLIC)
        if (privateFile.exists() && publicFile.exists()) return
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        try {
            val privateBytes = ByteArrayOutputStream().also { pair.writePrivateKey(it) }.toByteArray()
            val comment = "adbtunnel-" + Build.MODEL.replace(' ', '-')
            val publicBytes = ByteArrayOutputStream().also { pair.writePublicKey(it, comment) }.toByteArray()
            privateFile.writeBytes(privateBytes)
            publicFile.writeBytes(publicBytes)
        } finally {
            pair.dispose()
        }
    }
}
