package ch.heuscher.adbtunnel

import android.content.Context
import android.os.UserManager
import android.util.Log
import java.io.File

/**
 * Where the app keeps everything: device-protected storage, which is readable from the moment the
 * phone has started, before anyone has typed its PIN. The ordinary app-private storage only opens
 * with the first unlock, and a phone that restarts by itself — a system update at night — would
 * stay unreachable until its owner next picks it up.
 *
 * The price: the login key is no longer protected by the phone's PIN, only by the phone itself.
 *
 * Versions before this kept it all in the ordinary storage. It is moved over the first time the
 * app runs unlocked; until then a locked phone has nothing here and the tunnel waits for the unlock
 * as it always did.
 */
object Storage {
    private val PREFS = listOf(Config.PREFS, AllowedWifi.PREFS)
    private val FILES = listOf(Keys.PRIVATE, Keys.PUBLIC)
    private var moved = false

    /** The context to open preferences and files with. */
    @Synchronized
    fun of(context: Context): Context {
        val app = context.applicationContext
        val protectedContext = app.createDeviceProtectedStorageContext()
        if (!moved && unlocked(context)) {
            moveOver(app, protectedContext)
            moved = true
        }
        return protectedContext
    }

    fun unlocked(context: Context): Boolean =
        context.getSystemService(UserManager::class.java)?.isUserUnlocked == true

    private fun moveOver(from: Context, to: Context) {
        for (name in PREFS) {
            if (!to.moveSharedPreferencesFrom(from, name)) Log.w(TAG, "could not move the $name settings")
        }
        // Both key files arrive before either original goes: half a pair would read as "no key"
        // and a new one would be made, which the server does not know.
        val pairs = FILES.map { File(from.filesDir, it) to File(to.filesDir, it) }.filter { it.first.exists() }
        if (pairs.isEmpty()) return
        for ((old, new) in pairs) {
            if (new.exists()) continue
            val tmp = File(new.path + ".tmp")
            old.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(new)) throw IllegalStateException("could not move ${old.name}")
        }
        pairs.forEach { it.first.delete() }
        Log.i(TAG, "moved the login key and settings to storage that is readable before the first unlock")
    }
}
