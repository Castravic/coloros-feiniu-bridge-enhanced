package io.github.colorosfeiniu.bridge

internal data class ColdStartSyncTargets(
    val lifecycle: String,
    val foreground: String,
    val registration: String,
    val listener: String,
) {
    companion object {
        fun forVersion(version: String?): ColdStartSyncTargets? = when (version) {
            "16.50.8" -> ColdStartSyncTargets(
                "com.oplus.aiunit.vision.k11", "b",
                "com.oplus.aiunit.vision.k11\$b", "com.oplus.aiunit.vision.loo\$b",
            )
            "17.10.7" -> ColdStartSyncTargets(
                "com.oplus.gallery.foundation.uikit.lifecycle.ActivityLifecycle", "c",
                "com.oplus.gallery.foundation.uikit.lifecycle.ActivityLifecycle\$addListener\$1",
                "com.oplus.aiunit.vision.fgq\$b",
            )
            else -> null
        }
    }
}

/** One native media-sync listener's lifetime; never replays a start already delivered by Gallery. */
internal class ColdStartSyncReplay {
    private var startObserved = false
    private var replayClaimed = false

    @Synchronized
    fun onNativeStart() {
        startObserved = true
    }

    @Synchronized
    fun claimReplay(foreground: Boolean): Boolean {
        if (!foreground || startObserved || replayClaimed) return false
        replayClaimed = true
        return true
    }
}
