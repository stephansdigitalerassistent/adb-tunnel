package ch.heuscher.adbtunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the tunnel back after the two things that end it without anybody pressing Stop: the
 * phone restarting, and the app being updated — an update over the tunnel itself used to cut off
 * the very adb that installed it.
 *
 * - **`BOOT_COMPLETED`** arrives once the phone has been unlocked after a restart; before that the
 *   SSH key, in app-private storage, cannot be read anyway. Android 15 and later refuse most
 *   foreground service types started from it, but not `specialUse`.
 * - **`MY_PACKAGE_REPLACED`** arrives in the new version straight after an update.
 *
 * Only when the tunnel is [wanted][TunnelService.wanted]: once Stop was pressed it stays off until
 * the icon is pressed again.
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!TunnelService.wanted(context)) {
                    Log.i(TAG, "${intent.action}: not restarting, Stop was pressed")
                    return
                }
                Log.i(TAG, "${intent.action}: restarting the tunnel")
                try {
                    context.startForegroundService(Intent(context, TunnelService::class.java))
                } catch (e: IllegalStateException) {
                    // ForegroundServiceStartNotAllowedException. The icon still works.
                    Log.w(TAG, "Android refused to restart the tunnel: ${e.message}")
                }
            }
        }
    }
}
