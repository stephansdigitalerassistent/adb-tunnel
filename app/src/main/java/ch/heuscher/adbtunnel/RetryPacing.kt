package ch.heuscher.adbtunnel

/**
 * How a tunnel that cannot be built waits for its next try. The first [AWAKE_WINDOW_MS] of a failing
 * stretch stay awake with quick retries, which covers a failover or a server restart. After that
 * the phone sleeps between tries, longer each time: a server that stays away, a refused key or an
 * adbd that needs someone at the phone would otherwise keep it awake until the battery is empty.
 *
 * Synchronized because the network callback restarts it while the tunnel loop asks it.
 */
internal class RetryPacing {
    private var failingSince = NOT_FAILING
    private var sleepMs = 0L

    /** A new chance: the network changed, someone is at the phone, or the tunnel held. */
    @Synchronized
    fun restart() {
        failingSince = NOT_FAILING
        sleepMs = 0
    }

    /** After a failed try at [now]: how long to sleep, or 0 to stay awake and retry soon. */
    @Synchronized
    fun nextSleepMs(now: Long): Long {
        if (failingSince == NOT_FAILING) failingSince = now
        if (now - failingSince < AWAKE_WINDOW_MS) return 0
        sleepMs = (sleepMs * 2).coerceIn(FIRST_SLEEP_MS, LONGEST_SLEEP_MS)
        return sleepMs
    }

    companion object {
        const val AWAKE_WINDOW_MS = 2 * 60_000L
        const val FIRST_SLEEP_MS = 60_000L
        /** Doze lets an idle alarm through at most every 9 minutes, so less would gain nothing. */
        const val LONGEST_SLEEP_MS = 10 * 60_000L
        private const val NOT_FAILING = -1L
    }
}
