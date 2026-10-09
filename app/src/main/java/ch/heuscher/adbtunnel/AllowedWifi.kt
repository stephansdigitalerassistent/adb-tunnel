package ch.heuscher.adbtunnel

import android.content.Context

/**
 * Access points (BSSIDs) where Wireless debugging has been confirmed allowed by the user.
 *
 * Android asks "Debugging über WLAN in diesem Netzwerk zulassen?" once per access point (BSSID).
 * Once allowed, Android remembers it; we mirror that set so [TunnelService] only switches
 * Wireless debugging on when it knows Android will not prompt. The mirror is filled two ways: by
 * watching Wireless debugging stay on, and by the server sending Android's own list, see
 * [TrustSyncReceiver].
 */
class AllowedWifi(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Whether [bssid] is in the allowed set. Unknown BSSIDs are never allowed. */
    fun contains(bssid: String?): Boolean {
        val clean = clean(bssid) ?: return false
        val set = prefs.getStringSet(KEY_BSSIDS, null) ?: return false
        return clean in set
    }

    /** Adds [bssid] to the persistent set. Ignored if unknown. */
    fun add(bssid: String?) {
        val clean = clean(bssid) ?: return
        val current = prefs.getStringSet(KEY_BSSIDS, null) ?: emptySet()
        if (clean in current) return
        val updated = current.toMutableSet()
        updated.add(clean)
        prefs.edit().putStringSet(KEY_BSSIDS, updated).apply()
    }

    /** Removes [bssid] from the persistent set. Ignored if unknown. */
    fun remove(bssid: String?) {
        val clean = clean(bssid) ?: return
        val current = prefs.getStringSet(KEY_BSSIDS, null) ?: return
        if (clean !in current) return
        val updated = current.toMutableSet()
        updated.remove(clean)
        prefs.edit().putStringSet(KEY_BSSIDS, updated).apply()
    }

    /** The whole allowed set. */
    fun all(): Set<String> = prefs.getStringSet(KEY_BSSIDS, null)?.toSet() ?: emptySet()

    /** Makes the set exactly [bssids], dropping anything that is not a MAC address. True if it changed. */
    fun replaceAll(bssids: Collection<String>): Boolean {
        val updated = bssids.mapNotNull { clean(it) }.filter { MAC.matches(it) }.toSet()
        if (updated == all()) return false
        prefs.edit().putStringSet(KEY_BSSIDS, updated).apply()
        return true
    }

    companion object {
        private val MAC = Regex("([0-9a-f]{2}:){5}[0-9a-f]{2}")
        private const val PREFS = "allowed_wifi"
        private const val KEY_BSSIDS = "bssids"
        private const val UNKNOWN_MAC = "02:00:00:00:00:00"

        /** Returns true if the BSSID is known (not null, not empty, and not the dummy MAC). */
        fun isKnown(bssid: String?): Boolean {
            if (bssid == null) return false
            val trimmed = bssid.trim()
            return trimmed.isNotEmpty() && !trimmed.equals(UNKNOWN_MAC, ignoreCase = true)
        }

        /** Normalizes a BSSID or returns null if it is unknown. */
        fun clean(bssid: String?): String? =
            if (isKnown(bssid)) bssid!!.trim().lowercase() else null
    }
}
