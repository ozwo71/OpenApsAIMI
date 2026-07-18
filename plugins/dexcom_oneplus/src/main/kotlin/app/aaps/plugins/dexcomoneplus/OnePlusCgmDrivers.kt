package app.aaps.plugins.dexcomoneplus

/**
 * Driver selection. **Stub remains default** until A3 device GO and Real protocol is filled.
 *
 * Use [select] (not a bare flag flip) so watchers stay on the active instance when the
 * engineering pref changes.
 */
object OnePlusCgmDrivers {

    @Volatile
    var useRealSkeleton: Boolean = false
        private set

    private val realInstance: OnePlusCgmDriverReal by lazy { OnePlusCgmDriverReal() }

    private val lock = Any()

    /** Production / Phase-A path — Stub unless engineering Real skeleton is enabled. */
    fun default(): OnePlusCgmDriver =
        if (useRealSkeleton) realInstance else OnePlusCgmDriverStub.instance

    /** Explicit Real skeleton (same singleton as when [useRealSkeleton] is true). */
    fun realSkeleton(): OnePlusCgmDriver = realInstance

    /**
     * Atomically switch Stub ↔ Real, migrating an optional [watcher] and shutting down the
     * previous driver so sessions / scanners do not linger on the unused instance.
     *
     * ⚠️ ASYNC IMPACT: may call [OnePlusCgmDriver.shutdown] which disconnects GATT.
     */
    fun select(useReal: Boolean, watcher: OnePlusGlucoseWatcher? = null): OnePlusCgmDriver {
        synchronized(lock) {
            if (useRealSkeleton == useReal) {
                val current = default()
                if (watcher != null) {
                    current.addWatcher(watcher)
                }
                return current
            }
            val previous = default()
            if (watcher != null) {
                try {
                    previous.removeWatcher(watcher)
                } catch (_: Throwable) {
                }
            }
            try {
                previous.shutdown()
            } catch (_: Throwable) {
            }
            useRealSkeleton = useReal
            val next = default()
            if (watcher != null) {
                next.addWatcher(watcher)
            }
            return next
        }
    }
}
