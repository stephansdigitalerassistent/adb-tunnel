package ch.heuscher.adbtunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream

/**
 * Holds the reverse tunnel: server 127.0.0.1:<remotePort> → this phone's adbd.
 *
 * Runs only because somebody pressed the icon, and only until Stop. There is no boot receiver and
 * no network callback, and START_NOT_STICKY means Android does not bring it back after killing it.
 * While it runs it does keep itself up: a dropped connection is retried, and when Wireless
 * debugging comes back on a different port the forward is moved to it.
 */
class TunnelService : Service() {

    private var worker: Thread? = null
    @Volatile private var session: Session? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (worker?.isAlive != true) {
            running = true
            acquireLocks()
            worker = Thread({ keepTunnelUp() }, "adb-tunnel").also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        status = "Stopped"
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
                switchOnWirelessDebugging()
                val port = AdbPort.find()
                if (port == null) {
                    update("Waiting for Wireless debugging (needs Wi-Fi)")
                    Thread.sleep(5_000)
                    continue
                }
                update("Connecting to the server…")
                val connected = try {
                    connect()
                } catch (e: Exception) {
                    update("Server not reachable, retrying: ${e.message}")
                    Thread.sleep(10_000)
                    continue
                }
                session = connected
                try {
                    connected.setPortForwardingR(AdbPort.LOOPBACK, remotePort, AdbPort.LOOPBACK, port)
                    var current: Int = port
                    update(upText(remotePort, current))
                    while (connected.isConnected) {
                        Thread.sleep(15_000)
                        if (AdbPort.isAdb(current)) continue
                        switchOnWirelessDebugging()
                        val next = AdbPort.find()
                        if (next == null) {
                            update("Wireless debugging is off, waiting")
                            continue
                        }
                        if (next != current) {
                            connected.delPortForwardingR(AdbPort.LOOPBACK, remotePort)
                            connected.setPortForwardingR(AdbPort.LOOPBACK, remotePort, AdbPort.LOOPBACK, next)
                            current = next
                        }
                        update(upText(remotePort, current))
                    }
                    update("Connection lost, reconnecting")
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    // Most often "remote port forwarding failed": another tunnel still holds the port.
                    update("Retrying in 10 s: ${e.message}")
                } finally {
                    connected.disconnect()
                    session = null
                }
                Thread.sleep(10_000)
            }
        } catch (e: InterruptedException) {
            // Stop was pressed.
        }
    }

    private fun upText(remotePort: Int, phonePort: Int) = "Up: server :$remotePort → phone :$phonePort"

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

    /** Needs WRITE_SECURE_SETTINGS (granted over adb). Without it, Wireless debugging is switched on by hand. */
    private fun switchOnWirelessDebugging() {
        try {
            if (Settings.Global.getInt(contentResolver, ADB_WIFI_ENABLED, 0) != 1) {
                Settings.Global.putInt(contentResolver, ADB_WIFI_ENABLED, 1)
                Log.i(TAG, "switched Wireless debugging on")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot switch Wireless debugging on: WRITE_SECURE_SETTINGS not granted")
        }
    }

    private fun update(text: String) {
        if (text == status) return
        status = text
        Log.i(TAG, text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun goForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "adb tunnel", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = notification(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, TunnelService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .setContentTitle("adb tunnel")
            .setContentText(text)
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

        private const val ACTION_STOP = "ch.heuscher.adbtunnel.STOP"
        private const val CHANNEL = "tunnel"
        private const val NOTIFICATION_ID = 1
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }
}
