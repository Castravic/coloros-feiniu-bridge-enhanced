package io.github.colorosfeiniu.bridge.resolver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the enhancement locator result per Gallery APK fingerprint, so the (comparatively slow)
 * DEX walk runs once per Gallery build instead of on every cold start.
 *
 * Entries are re-validated by the caller before use; a stale entry is dropped and re-resolved.
 */
internal class EnhancementResolutionCache(
    context: Context,
    private val fingerprint: GalleryFingerprint,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    private val key = "enhancement.${fingerprint.cacheNamespace()}"

    fun read(validator: (EnhancementTargets) -> Boolean): EnhancementTargets? {
        val encoded = preferences.getString(key, null) ?: return null
        val decoded = runCatching {
            val root = JSONObject(encoded)
            require(root.getInt("schema") == CACHE_SCHEMA)
            EnhancementTargets(
                temperatureProvider = decodeMethod(root.optJSONObject("temperature")),
                pauseConditionCheckers = decodeMethods(root.optJSONArray("checkers")),
                pauseStateInfoClass = root.optString("stateInfo").takeIf { it.isNotEmpty() },
                pauseReasonText = decodeMethod(root.optJSONObject("pauseText")),
                diagnostics = emptyList(),
            )
        }.getOrNull()

        if (decoded == null || !validator(decoded)) {
            preferences.edit().remove(key).apply()
            return null
        }
        return decoded
    }

    fun write(targets: EnhancementTargets) {
        val root = JSONObject()
            .put("schema", CACHE_SCHEMA)
            .put("checkers", JSONArray().apply {
                targets.pauseConditionCheckers.forEach { checker -> put(encodeMethod(checker)) }
            })
        targets.temperatureProvider?.let { root.put("temperature", encodeMethod(it)) }
        targets.pauseReasonText?.let { root.put("pauseText", encodeMethod(it)) }
        targets.pauseStateInfoClass?.let { root.put("stateInfo", it) }
        preferences.edit().putString(key, root.toString()).apply()
    }

    private fun encodeMethod(target: LocatedMethod): JSONObject = JSONObject()
        .put("className", target.className)
        .put("methodName", target.methodName)
        .put("descriptor", target.descriptor)

    private fun decodeMethod(value: JSONObject?): LocatedMethod? {
        value ?: return null
        val className = value.optString("className").takeIf { it.isNotEmpty() } ?: return null
        val methodName = value.optString("methodName").takeIf { it.isNotEmpty() } ?: return null
        val descriptor = value.optString("descriptor").takeIf { it.isNotEmpty() } ?: return null
        return LocatedMethod(className, methodName, descriptor)
    }

    private fun decodeMethods(values: JSONArray?): List<LocatedMethod> {
        values ?: return emptyList()
        return List(values.length()) { index ->
            decodeMethod(values.optJSONObject(index))
        }.filterNotNull()
    }

    private companion object {
        const val PREFERENCES_FILE = "coloros_feiniu_bridge"
        const val CACHE_SCHEMA = 1
    }
}
