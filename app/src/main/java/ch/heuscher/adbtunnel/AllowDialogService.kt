package ch.heuscher.adbtunnel

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Presses Allow on Android's "Allow wireless debugging on this network?" question, for access
 * points that were allowed on this phone before.
 *
 * A Galaxy S10+ (Android 12) cannot read its own list of allowed access points after a restart
 * ("Expected keyStore, but got tag=adbKey"), so it asks again every time and the tunnel stays down
 * until someone stands next to the phone. The question names the access point (BSSID); it is
 * answered only when [AllowedWifi] holds that BSSID, which gets there by a person allowing it or by
 * the server sending Android's own list. A new access point is still a person's decision.
 */
class AllowDialogService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val allowedWifi by lazy { AllowedWifi(this) }

    override fun onServiceConnected() {
        Log.i(TAG, "allow-dialog service connected")
        // The question may be up already: after a restart the tunnel can be quicker than this.
        look(ATTEMPTS, announced = false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != SYSTEM_UI) return
        if (event.className?.toString()?.contains(DIALOG_CLASS) != true) return
        look(ATTEMPTS, announced = true)
    }

    override fun onInterrupt() = Unit

    /** The window's content arrives a moment after the window, so this looks a few times. */
    private fun look(attemptsLeft: Int, announced: Boolean) {
        handler.removeCallbacksAndMessages(null)
        if (answer(announced) || attemptsLeft <= 1) return
        handler.postDelayed({ look(attemptsLeft - 1, announced) }, RETRY_MS)
    }

    /**
     * True when there is nothing more to do: the question was answered, or is not ours to answer.
     * [announced] says Android reported the question's window by name; without that, only a window
     * holding Android's own "Always allow on this network" box counts as the question.
     */
    private fun answer(announced: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != SYSTEM_UI) return false
        val always = root.findAccessibilityNodeInfosByViewId(ALWAYS_ID).firstOrNull()
        if (always == null && !announced) return false
        val texts = ArrayList<String>()
        collectTexts(root, texts)
        val named = texts.flatMap { text -> MAC.findAll(text.lowercase()).map { it.value } }.toSet()
        val bssid = named.singleOrNull()
        // Not drawn yet, or another System UI window is in front: look again.
        if (named.isEmpty() && always == null) return false
        if (bssid == null || !allowedWifi.contains(bssid)) {
            Log.i(TAG, "Android asks about Wi-Fi $named, not allowed before: left for a person")
            return true
        }
        val allow = root.findAccessibilityNodeInfosByViewId(ALLOW_ID).firstOrNull()
        if (allow == null) {
            Log.w(TAG, "Android asks about Wi-Fi $bssid, but its Allow button was not found: $texts")
            return true
        }
        if (always != null && !always.isChecked) always.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val pressed = allow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.i(TAG, "Android asked about Wi-Fi $bssid, allowed before: pressed Allow ($pressed)")
        return true
    }

    private fun collectTexts(node: AccessibilityNodeInfo, into: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { into.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, into) }
        }
    }

    companion object {
        private const val TAG = "AdbTunnel"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val DIALOG_CLASS = "WifiDebugging"
        private const val ALWAYS_ID = "com.android.systemui:id/alwaysUse"
        private const val ALLOW_ID = "android:id/button1"
        private const val ATTEMPTS = 6
        private const val RETRY_MS = 300L
        private val MAC = Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")
        private const val ENABLED_SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

        /**
         * Switches this service on. Android offers that only in its settings, to a person; with
         * WRITE_SECURE_SETTINGS, which the tunnel has anyway, the app can do it itself. Called
         * again and again, because some phones switch such a service off when its app is updated.
         */
        fun ensureEnabled(context: Context) {
            val me = ComponentName(context, AllowDialogService::class.java)
            val resolver = context.contentResolver
            try {
                val enabled = Settings.Secure.getString(resolver, ENABLED_SERVICES).orEmpty()
                val mine = enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
                if (!mine) {
                    val joined = listOf(enabled, me.flattenToString()).filter { it.isNotEmpty() }
                    Settings.Secure.putString(resolver, ENABLED_SERVICES, joined.joinToString(":"))
                    Log.i(TAG, "switched the allow-dialog service on")
                }
                if (Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                    Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "cannot switch the allow-dialog service on: WRITE_SECURE_SETTINGS not granted")
            }
        }
    }
}
