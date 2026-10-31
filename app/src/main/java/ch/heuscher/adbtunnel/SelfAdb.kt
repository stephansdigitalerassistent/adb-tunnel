package ch.heuscher.adbtunnel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import javax.net.ssl.SSLContext

/**
 * The app as an adb client of its own phone, the way Shizuku does it. One pairing with the code
 * Android shows makes this app's key ([AdbAuth]) known to adbd, and from then on Wireless debugging
 * lets it do what otherwise takes a USB cable or the server: TCP mode, and the permissions the tunnel
 * needs, which is what makes a first install on Android 11 work without a computer.
 */
internal object SelfAdb {
    private const val TCP_MODE_REPLY = "restarting in TCP mode"
    private const val TLS_ATTEMPTS = 3
    private const val TLS_RETRY_MS = 2_000L

    private fun prefs(context: Context) =
        Storage.of(context).getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)

    fun paired(context: Context): Boolean = prefs(context).getBoolean(Config.KEY_PAIRED, false)

    /** Pairs with the open dialog, on [port], or on the one this phone announces when that is 0. */
    fun pair(context: Context, code: String, port: Int) {
        val pairingPort = port.takeIf { it > 0 } ?: AdbPort.pairingPort(context)
            ?: throw IOException("the pairing dialog is not open")
        val publicKey = AdbAuth.getPublicKeyString(context).toByteArray(Charsets.UTF_8)
        AdbPairing.pair(pairingPort, code, tls(context), publicKey)
        // commit: a pairing is not repeated lightly, it takes someone at the phone.
        prefs(context).edit().putBoolean(Config.KEY_PAIRED, true).commit()
    }

    /** Over the Wireless debugging port [adbPort]. Restarts adbd, so its connections all end here. */
    fun switchOnTcpMode(context: Context, adbPort: Int, tcpPort: Int) {
        overTls(context, adbPort) { adb ->
            missingPermissions(context).forEach { adb.run("shell:pm grant ${context.packageName} $it") }
            val reply = adb.run("tcpip:$tcpPort")
            if (TCP_MODE_REPLY !in reply) throw IOException("tcpip said: ${reply.trim()}")
        }
    }

    private fun missingPermissions(context: Context): List<String> = buildList {
        add(Manifest.permission.WRITE_SECURE_SETTINGS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun <T> overTls(context: Context, port: Int, work: (AdbTlsClient) -> T): T {
        val tls = tls(context)
        var attempt = 1
        while (true) {
            try {
                return AdbTlsClient.connect(port, tls).use(work)
            } catch (e: IOException) {
                // Connections do end mid-handshake now and then, and a key paired a moment ago may
                // not be in adbd's list yet.
                if (attempt++ == TLS_ATTEMPTS) throw e
                Thread.sleep(TLS_RETRY_MS)
            }
        }
    }

    /** A phone that cannot load the bundled TLS library loses this, not its tunnel. */
    private fun tls(context: Context): SSLContext = try {
        AdbTls.context(AdbAuth.getKeyPair(context))
    } catch (e: LinkageError) {
        throw IOException("no TLS library for this phone: ${e.message}", e)
    }
}
