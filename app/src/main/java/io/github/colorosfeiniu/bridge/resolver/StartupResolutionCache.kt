package io.github.colorosfeiniu.bridge.resolver

import android.content.Context

internal data class StartupCacheReadOutcome(
    val snapshot: StartupCacheSnapshot?,
    val status: String,
)

internal data class StartupCacheWriteOutcome(
    val snapshot: StartupCacheSnapshot,
    val persisted: Boolean,
)

/**
 * Persists cold-start resolution outcomes (including prefix and negative-cache flags) keyed by
 * [GalleryFingerprint] so subsequent launches bypass full-APK DEX scans completely.
 */
internal class StartupResolutionCache(
    context: Context,
    fingerprint: GalleryFingerprint,
) {
    private val preferences = context.getSharedPreferences(
        StartupCachePolicy.PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )
    private val key = StartupCachePolicy.keyFor(fingerprint.cacheNamespace())

    fun read(): StartupCacheSnapshot? = readWithStatus().snapshot

    fun readWithStatus(): StartupCacheReadOutcome = synchronized(lock) {
        val raw = preferences.getString(key, null)
            ?: return StartupCacheReadOutcome(null, "miss")
        val decoded = StartupCachePolicy.decodeWithReason(raw)
        val snapshot = decoded.snapshot
        if (snapshot == null) {
            preferences.edit().remove(key).apply()
            return StartupCacheReadOutcome(null, "corrupt:${decoded.reason} len=${raw.length}")
        }
        StartupCacheReadOutcome(snapshot, "hit")
    }

    fun update(
        commit: Boolean = false,
        transform: (StartupCacheSnapshot) -> StartupCacheSnapshot,
    ): StartupCacheWriteOutcome = synchronized(lock) {
        val current = readLocked() ?: StartupCacheSnapshot()
        val updated = transform(current)
        if (updated == current && preferences.contains(key)) {
            return StartupCacheWriteOutcome(updated, true)
        }
        val editor = preferences.edit().putString(key, StartupCachePolicy.encode(updated))
        val persisted = if (commit) {
            editor.commit()
        } else {
            editor.apply()
            true
        }
        StartupCacheWriteOutcome(updated, persisted)
    }

    private fun readLocked(): StartupCacheSnapshot? {
        val raw = preferences.getString(key, null) ?: return null
        return StartupCachePolicy.decode(raw)
    }

    private companion object {
        val lock = Any()
    }
}
