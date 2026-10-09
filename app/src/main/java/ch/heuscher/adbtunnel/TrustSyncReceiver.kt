package ch.heuscher.adbtunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Takes Android's own list of access points allowed for Wireless debugging from the server, which
 * reads it over the tunnel (`dumpsys adb`) — the app itself cannot.
 *
 * Without it [AllowedWifi] only fills when Wireless debugging is seen on, and the tunnel normally
 * runs on TCP mode with Wireless debugging off: after a restart the app then left it off on an
 * access point Android had long allowed, and waited for "Ask for this Wi-Fi" to be pressed.
 *
 * The list replaces the app's own, so an access point Android has forgotten is forgotten here too.
 * Only adb can send it: the receiver asks for `DUMP`, which the shell holds and no ordinary app.
 *
 *     adb shell am broadcast -n ch.heuscher.adbtunnel/.TrustSyncReceiver \
 *         -a ch.heuscher.adbtunnel.SYNC_ALLOWED_WIFI --es bssids aa:bb:cc:dd:ee:ff,11:22:33:44:55:66
 *
 * Answers result 1 so the sender can tell an app that took the list from one too old to have this.
 */
class TrustSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SYNC) return
        val raw = intent.getStringExtra(EXTRA_BSSIDS) ?: return
        val bssids = raw.split(',').filter { it.isNotBlank() }
        val allowed = AllowedWifi(context)
        if (allowed.replaceAll(bssids)) {
            Log.i(TAG, "allowed Wi-Fi synced from Android's list: ${allowed.all().sorted().joinToString(",")}")
        }
        if (isOrderedBroadcast) resultCode = RESULT_TAKEN
    }

    companion object {
        const val ACTION_SYNC = "ch.heuscher.adbtunnel.SYNC_ALLOWED_WIFI"
        const val EXTRA_BSSIDS = "bssids"
        private const val RESULT_TAKEN = 1
    }
}
