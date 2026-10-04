package io.github.colorosfeiniu.bridge.resolver

/**
 * The parts of the enhancement cache that do not need Android: the preference key layout, the
 * "only cache a non-empty result" rule and the structural re-validation applied before the live
 * class-loader check.
 *
 * Keeping them here lets the cache contract stay covered by plain JVM unit tests even though
 * [EnhancementResolutionCache] itself needs a `Context`.
 */
internal object EnhancementCachePolicy {

    const val PREFERENCES_FILE = "coloros_feiniu_bridge"
    const val CACHE_SCHEMA = 1

    private const val KEY_PREFIX = "enhancement."

    fun keyFor(namespace: String): String = KEY_PREFIX + namespace

    /** A cache entry is only written when the locator actually found something. */
    fun shouldWrite(targets: EnhancementTargets): Boolean = !targets.isEmpty

    /**
     * Structural re-validation applied to a decoded entry before the (live) class-loader check.
     *
     * The state info class and the pause reason text are located from one and the same trace, so an
     * entry carrying one without the other — or pointing the two at different classes — is corrupt
     * and must be discarded instead of trusted.
     */
    fun isConsistent(targets: EnhancementTargets): Boolean {
        val text = targets.pauseReasonText
        val stateInfo = targets.pauseStateInfoClass
        if ((text == null) != (stateInfo == null)) return false
        return text == null || text.className == stateInfo
    }
}
