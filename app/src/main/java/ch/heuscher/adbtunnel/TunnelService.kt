package ch.heuscher.adbtunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Holds the reverse tunnel: server 127.0.0.1:<remotePort> → this phone's adbd.
 *
 * Runs from pressing the icon until pressing Stop, and in between it keeps coming back: START_STICKY
 * has Android restart it after killing it or after a crash, and [RestartReceiver] starts it again
 * after a phone restart or an app update. Only Stop ends that ([wanted]). While it runs it keeps
 * the tunnel up: a dropped connection is retried, a move between Wi-Fi and mobile data reconnects
 * at once, and when adbd comes back on a different port the forward is moved to it.
 *
 * The tunnel itself needs no Wi-Fi. adbd does, unless it is in TCP mode (`adb tcpip 5555`), which
 * the server switches on through the first tunnel after each reboot — see server/ in this repo —
 * and unless USB debugging is on: with USB and Wireless debugging both off, Android stops adbd.
 */
class TunnelService : Service() {

    private var worker: Thread? = null
    @Volatile private var session: Session? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val napLock = ReentrantLock()
    private val networkChanged = napLock.newCondition()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setWanted(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        // No intent: Android restarting it after killing the process. Stop wins over that.
        if (intent == null && !wanted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            goForeground()
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: a restart Android allowed to run but not
            // in the foreground. Stay wanted, so the next restart, update or icon press retries.
            Log.w(TAG, "not allowed into the foreground, waiting for the next start: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive != true) {
            running = true
            acquireLocks()
            watchNetwork()
            worker = Thread({ keepTunnelUp() }, "adb-tunnel").also { it.start() }
        }
        if (intent == null) Log.i(TAG, "restarted by Android")
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        status = "Stopped"
        up = false
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        networkCallback = null
        worker?.interrupt()
        worker = null
        // Disconnecting writes to the socket, which the main thread may not do.
        session?.let { Thread { it.disconnect() }.start() }
        releaseLocks()
        Log.i(TAG, "stopped")
        super.onDestroy()
    }

    private fun keepTunnelUp() {
        val remotePort = getSharedPreferences(Config.PREFS, MODE_PRIVATE)
            .getInt(Config.KEY_REMOTE_PORT, Config.DEFAULT_REMOTE_PORT)
        try {
            while (!Thread.currentThread().isInterrupted) {
                keepAdbdOn()
                val port = AdbPort.find()
                if (port == null) {
                    waitForAdb()
                    continue
                }
                update("Connecting to the server…")
                val connected = try {
                    connect()
                } catch (e: Exception) {
                    update("Server not reachable, retrying: ${e.message}")
                    nap(10_000)
                    continue
                }
                session = connected
                try {
                    connected.setPortForwardingR(AdbPort.LOOPBACK, remotePort, AdbPort.LOOPBACK, port)
                    var current: Int = port
                    update(upText(remotePort, current), isUp = true)
                    while (connected.isConnected) {
                        nap(15_000)
                        if (!connected.isConnected) continue
                        // A Wireless debugging port dies with the Wi-Fi, TCP mode does not: move to
                        // it as soon as it is there, even while the current port still answers.
                        val next = when {
                            current != TCP_MODE_PORT && AdbPort.isAdb(TCP_MODE_PORT) -> TCP_MODE_PORT
                            AdbPort.isAdb(current) -> continue
                            else -> {
                                keepAdbdOn()
                                AdbPort.find()
                            }
                        }
                        if (next == null) {
                            update(adbMissingText())
                            continue
                        }
                        if (next != current) {
                            connected.delPortForwardingR(AdbPort.LOOPBACK, remotePort)
                            connected.setPortForwardingR(AdbPort.LOOPBACK, remotePort, AdbPort.LOOPBACK, next)
                            current = next
                        }
                        update(upText(remotePort, current), isUp = true)
                    }
                    update("Connection lost, reconnecting")
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    // Most often "remote port forwarding failed": the server still holds the port
                    // for a session lost on the previous network, until its keep-alive gives up.
                    update("Retrying in 10 s: ${e.message}")
                } finally {
                    connected.disconnect()
                    session = null
                }
                nap(10_000)
            }
        } catch (e: InterruptedException) {
            // Stop was pressed.
        }
    }

    private fun upText(remotePort: Int, phonePort: Int) = "Up: server :$remotePort → phone :$phonePort"

    /** Probing every loopback port is not free, so without Wi-Fi it is retried only now and then. */
    private fun waitForAdb() {
        update(adbMissingText())
        nap(if (onWifi()) 5_000 else 30_000)
    }

    private fun adbMissingText() =
        if (onWifi()) "Waiting for Wireless debugging"
        else "adb is off since the phone restarted: join any Wi-Fi once"

    private fun connect(): Session {
        val jsch = JSch()
        jsch.setKnownHosts(ByteArrayInputStream("${Config.SERVER_HOST} ${Config.SERVER_HOST_KEY}\n".toByteArray()))
        jsch.addIdentity("adbtunnel", Keys.privateKey(this), Keys.publicKey(this).toByteArray(), null)
        val s = jsch.getSession(Config.SERVER_USER, Config.SERVER_HOST, Config.SERVER_PORT)
        s.setConfig("StrictHostKeyChecking", "yes")
        s.setConfig("PreferredAuthentications", "publickey")
        // Only algorithms Android's own providers implement, so nothing depends on JSch probing.
        s.setConfig("server_host_key", "ecdsa-sha2-nistp256")
        s.setConfig("PubkeyAcceptedAlgorithms", "ecdsa-sha2-nistp256")
        s.setConfig("kex", "ecdh-sha2-nistp256")
        s.setConfig("cipher.c2s", "aes128-gcm@openssh.com,aes128-ctr")
        s.setConfig("cipher.s2c", "aes128-gcm@openssh.com,aes128-ctr")
        s.setConfig("mac.c2s", "hmac-sha2-256-etm@openssh.com,hmac-sha2-256")
        s.setConfig("mac.s2c", "hmac-sha2-256-etm@openssh.com,hmac-sha2-256")
        s.setServerAliveInterval(30_000)
        s.setServerAliveCountMax(3)
        s.connect(15_000)
        return s
    }

    /**
     * A move between Wi-Fi and mobile data leaves the SSH socket dead without an error, and the
     * keep-alives would only notice after 90 s. So on a new default network the session is dropped
     * straight away and the loop woken to build a new one.
     */
    private fun watchNetwork() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null

            override fun onAvailable(network: Network) {
                val previous = current
                current = network
                if (previous != null && previous != network) {
                    Log.i(TAG, "default network changed, reconnecting")
                    session?.let { Thread { it.disconnect() }.start() }
                }
                napLock.withLock { networkChanged.signalAll() }
            }
        }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    /** Sleeps up to [ms], or less when the network changes. Stop still ends it by interrupting. */
    private fun nap(ms: Long) {
        napLock.withLock { networkChanged.await(ms, TimeUnit.MILLISECONDS) }
    }

    @Suppress("DEPRECATION") // allNetworks: a Wi-Fi without internet is not the default network, but counts here.
    private fun onWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /**
     * Needs WRITE_SECURE_SETTINGS (granted over adb); without it both switches are set by hand.
     *
     * USB debugging stays on so that adbd is not stopped when the Wi-Fi goes: Android stops it once
     * USB and Wireless debugging are both off, and TCP mode dies with it. Wireless debugging is only
     * switched on while on Wi-Fi, because off Wi-Fi Android turns it straight back off.
     */
    private fun keepAdbdOn() {
        try {
            if (Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) != 1) {
                Settings.Global.putInt(contentResolver, Settings.Global.ADB_ENABLED, 1)
                Log.i(TAG, "switched USB debugging on")
            }
            if (onWifi() && Settings.Global.getInt(contentResolver, ADB_WIFI_ENABLED, 0) != 1) {
                Settings.Global.putInt(contentResolver, ADB_WIFI_ENABLED, 1)
                Log.i(TAG, "switched Wireless debugging on")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot switch debugging on: WRITE_SECURE_SETTINGS not granted")
        }
    }

    /** Anything but an "Up" line counts as down: waiting, connecting, retrying. */
    private fun update(text: String, isUp: Boolean = false) {
        if (text == status && isUp == up) return
        status = text
        up = isUp
        Log.i(TAG, text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun goForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "adb tunnel", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, TunnelService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            // Full-colour icons rather than the usual white mask: One UI shows them in colour in the
            // status bar — green with arrows while up, red with a cross otherwise.
            .setSmallIcon(if (up) R.mipmap.ic_tunnel_up else R.mipmap.ic_tunnel_down)
            .setContentTitle("adb tunnel")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_tunnel), "Stop", stop).build()
            )
            .build()
    }

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF: the low-latency mode only holds while in the foreground UI.
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adbtunnel:tunnel")
            .apply { setReferenceCounted(false); acquire() }
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "adbtunnel:tunnel")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    companion object {
        @Volatile var running = false
        @Volatile var status = "Starting…"
        @Volatile var up = false

        /**
         * Whether the tunnel should be running: set by pressing the icon, cleared only by Stop.
         *
         * Versions before this did not record it. For them, a phone whose icon was ever pressed —
         * it has a login key, made on the first press — counts as wanted, so the update that
         * brings this in also brings the running tunnel back.
         */
        fun wanted(context: Context): Boolean {
            val prefs = context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE)
            return if (prefs.contains(Config.KEY_WANTED)) prefs.getBoolean(Config.KEY_WANTED, false)
            else Keys.exists(context)
        }

        fun setWanted(context: Context, wanted: Boolean) {
            // commit, not apply: after Stop the process may be gone before an apply is written.
            context.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(Config.KEY_WANTED, wanted).commit()
        }

        private const val ACTION_STOP = "ch.heuscher.adbtunnel.STOP"
        private const val CHANNEL = "tunnel"
        private const val NOTIFICATION_ID = 1
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        /** The port the server's `adb tcpip` puts adbd on. */
        private const val TCP_MODE_PORT = 5555
    }
}
