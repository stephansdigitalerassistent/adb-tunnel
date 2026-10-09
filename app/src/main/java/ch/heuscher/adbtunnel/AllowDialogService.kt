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
 *
 * The tunnel says when it switches Wireless debugging on ([expectQuestion]) and the service then
 * looks for the question for a while: right after a restart the phone is slow, and waiting for
 * Android to report the window was not enough there (S10+, 2026-10-09: reported nothing at all).
 */
class AllowDialogService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val allowedWifi by lazy { AllowedWifi(this) }
    private var looksLeft = 0
    private var lastSeen = "nothing looked at"

    override fun onServiceConnected() {
        Log.i(TAG, "allow-dialog service connected")
        instance = this
        // The question may be up already: after a restart the tunnel can be quicker than this.
        look(if (expecting() != null) EXPECTED_LOOKS else EVENT_LOOKS)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != SYSTEM_UI) return
        look(EVENT_LOOKS)
    }

    override fun onInterrupt() = Unit

    /** Looks now and then every [LOOK_MS], at least [times] times, until the question is dealt with. */
    private fun look(times: Int) {
        val idle = looksLeft <= 0
        if (times > looksLeft) looksLeft = times
        if (idle) lookOnce()
    }

    private fun lookOnce() {
        val done = try {
            answer()
        } catch (e: RuntimeException) {
            // A window that goes away while it is read; the next look sees what is there then.
            lastSeen = "failed: $e"
            false
        }
        looksLeft = if (done) 0 else looksLeft - 1
        if (looksLeft > 0) {
            handler.postDelayed(::lookOnce, LOOK_MS)
        } else if (!done && expecting() != null) {
            Log.w(TAG, "Wireless debugging was switched on, but Android's question was not found: $lastSeen")
            expectedUntil = 0
        }
    }

    /** True when there is nothing more to do: the question was answered, or is not ours to answer. */
    private fun answer(): Boolean {
        val roots = (windows.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow))
            .filter { it.packageName?.toString() == SYSTEM_UI }
        val seen = ArrayList<String>()
        for (root in roots) {
            val nodes = ArrayList<AccessibilityNodeInfo>()
            collect(root, nodes)
            val texts = nodes.mapNotNull { it.text?.toString()?.takeIf { text -> text.isNotBlank() } }
            val named = texts.flatMap { text -> MAC.findAll(text.lowercase()).map { it.value } }.toSet()
            val always = nodes.firstOrNull { it.viewIdResourceName == ALWAYS_ID }
                ?: nodes.singleOrNull { it.isCheckable }
            val buttons = nodes.filter { it.isClickable && it.className?.toString()?.endsWith("Button") == true }
            // Android's question, and nothing else in System UI: one access point, the "always" box,
            // and two buttons. Allow is the dialog's positive button, the last of the two.
            val allow = nodes.firstOrNull { it.viewIdResourceName == ALLOW_ID }
                ?: buttons.takeIf { it.size == 2 }?.last()
            if (named.size != 1 || always == null || allow == null) {
                seen += "window(${nodes.size} nodes, Wi-Fi $named, ${buttons.size} buttons, " +
                    "box=${always != null}, ${texts.take(8)})"
                continue
            }
            val bssid = named.single()
            if (!allowedWifi.contains(bssid) && bssid != expecting()) {
                Log.i(TAG, "Android asks about Wi-Fi $bssid, not allowed before: left for a person")
                return true
            }
            if (!always.isChecked) always.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val pressed = allow.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(
                TAG,
                "Android asked about Wi-Fi $bssid, allowed before: pressed \"${allow.text}\" " +
                    "(${allow.viewIdResourceName}, $pressed)"
            )
            expectedUntil = 0
            return true
        }
        lastSeen = "active window ${rootInActiveWindow?.packageName}, ${windows.size} windows, System UI: $seen"
        return false
    }

    private fun collect(node: AccessibilityNodeInfo, into: MutableList<AccessibilityNodeInfo>) {
        if (into.size >= MAX_NODES) return
        into.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, into) }
        }
    }

    companion object {
        private const val TAG = "AdbTunnel"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val ALWAYS_ID = "com.android.systemui:id/alwaysUse"
        private const val ALLOW_ID = "android:id/button1"
        private const val LOOK_MS = 500L
        private const val EVENT_LOOKS = 6
        private const val EXPECTED_LOOKS = 60
        private const val EXPECT_MS = 60_000L
        private const val MAX_NODES = 400
        private val MAC = Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")
        private const val ENABLED_SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

        @Volatile
        private var instance: AllowDialogService? = null

        @Volatile
        private var expected: String? = null

        @Volatile
        private var expectedUntil = 0L

        private fun expecting(): String? = expected.takeIf { System.currentTimeMillis() < expectedUntil }

        /**
         * The tunnel is about to switch Wireless debugging on at [bssid], an allowed access point.
         * Remembered for a minute, because the tunnel drops the access point from its list when
         * Android switches Wireless debugging back off to ask, which is before the answer.
         */
        fun expectQuestion(bssid: String) {
            expected = AllowedWifi.clean(bssid) ?: return
            expectedUntil = System.currentTimeMillis() + EXPECT_MS
            instance?.let { service -> service.handler.post { service.look(EXPECTED_LOOKS) } }
        }

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
