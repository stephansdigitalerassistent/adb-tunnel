package ch.heuscher.adbtunnel

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * The icon. Starts the tunnel, or — when it is already running — only says how it is doing.
 * Pressing it any number of times gives one tunnel. It shows no screen of its own.
 *
 * To give a phone a different server port (the Fold uses 5556):
 * `adb shell am start -n ch.heuscher.adbtunnel/.TunnelActivity --ei remotePort 5556`
 */
class TunnelActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedPort = intent.getIntExtra(Config.EXTRA_REMOTE_PORT, 0)
        if (requestedPort in 1..65535) {
            getSharedPreferences(Config.PREFS, MODE_PRIVATE).edit()
                .putInt(Config.KEY_REMOTE_PORT, requestedPort).apply()
        }
        // Public, so logging it is harmless — and it is how the key reaches the server's list.
        Log.i(TAG, "public key: " + Keys.publicKey(this))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Without it the Stop button and the status line are invisible. Start either way.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        startTunnel()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        startTunnel()
    }

    private fun startTunnel() {
        val message = if (TunnelService.running) "adb tunnel: ${TunnelService.status}" else "adb tunnel starting"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        // From now on it comes back after restarts and updates, until Stop.
        TunnelService.setWanted(this, true)
        startForegroundService(Intent(this, TunnelService::class.java))
        finish()
    }
}
