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
 * - **`LOCKED_BOOT_COMPLETED`** arrives as soon as the phone has started, before anyone unlocks
 *   it; the login key and settings are kept where they can be read then, see [Storage]. So a phone
 *   that restarts by itself is reachable again without its owner. Android 15 and later refuse
 *   most foreground service types started from a boot broadcast, but not `specialUse`.
 * - **`BOOT_COMPLETED`** arrives once the phone has been unlocked. Usually the tunnel is up by
 *   then and this changes nothing; it is what starts it on the first restart after the update
 *   from a version that kept its key where only an unlocked phone can read it.
 * - **`MY_PACKAGE_REPLACED`** arrives in the new version straight after an update.
 *
 * Only when the tunnel is [wanted][TunnelService.wanted]: once Stop was pressed it stays off until
 * the icon is pressed again.
 */
class RestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                if (!TunnelService.wanted(context)) {
                    Log.i(TAG, "${intent.action}: not restarting, Stop was pressed or no login key readable yet")
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
