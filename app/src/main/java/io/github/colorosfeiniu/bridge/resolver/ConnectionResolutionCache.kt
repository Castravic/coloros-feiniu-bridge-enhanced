package io.github.colorosfeiniu.bridge.resolver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal class ConnectionResolutionCache(
    context: Context,
    fingerprint: GalleryFingerprint,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
    private val tokenKey = "resolver.${fingerprint.cacheNamespace()}.token"
    private val galleryKey = "resolver.${fingerprint.cacheNamespace()}.gallery"

    fun readToken(
        validator: (TokenHookRefs) -> Boolean,
    ): TokenHookRefs? = read(tokenKey, ::decodeToken, validator)

    fun readGallery(
        validator: (GalleryHookRefs) -> Boolean,
    ): GalleryHookRefs? = read(galleryKey, ::decodeGallery, validator)

    fun writeToken(refs: TokenHookRefs) {
        preferences.edit().putString(tokenKey, encodeToken(refs).toString()).apply()
    }

    fun writeGallery(refs: GalleryHookRefs) {
        preferences.edit().putString(galleryKey, encodeGallery(refs).toString()).apply()
    }

    fun invalidateToken() {
        preferences.edit().remove(tokenKey).apply()
    }

    fun invalidateGallery() {
        preferences.edit().remove(galleryKey).apply()
    }

    private fun <T> read(
        key: String,
        decoder: (JSONObject) -> T,
        validator: (T) -> Boolean,
    ): T? {
        val encoded = preferences.getString(key, null) ?: return null
        return runCatching {
            val root = JSONObject(encoded)
            require(root.getInt("schema") == CACHE_SCHEMA)
            decoder(root)
        }.getOrNull()?.takeIf(validator) ?: run {
            preferences.edit().remove(key).apply()
            null
        }
    }

    private fun encodeToken(refs: TokenHookRefs): JSONObject = JSONObject()
        .put("schema", CACHE_SCHEMA)
        .put("prefix", encodeMethod(refs.prefix))
        .put("decrypt", encodeMethod(refs.decrypt))

    private fun decodeToken(root: JSONObject): TokenHookRefs = TokenHookRefs(
        prefix = decodeMethod(root.getJSONObject("prefix")),
        decrypt = decodeMethod(root.getJSONObject("decrypt")),
        source = ResolutionSource.CACHE,
    )

    private fun encodeGallery(refs: GalleryHookRefs): JSONObject = JSONObject()
        .put("schema", CACHE_SCHEMA)
        .put("stat", encodeMethod(refs.stat))
        .put("albums", encodeMethod(refs.albums))
        .put("connection", encodeMethod(refs.connection))
        .put("realAlbums", encodeMethod(refs.realAlbums))
        .put("cache", encodeMethod(refs.cache))
        .put("photoCount", encodeField(refs.photoCount))
        .put("videoCount", encodeField(refs.videoCount))

    private fun decodeGallery(root: JSONObject): GalleryHookRefs = GalleryHookRefs(
        stat = decodeMethod(root.getJSONObject("stat")),
        albums = decodeMethod(root.getJSONObject("albums")),
        connection = decodeMethod(root.getJSONObject("connection")),
        realAlbums = decodeMethod(root.getJSONObject("realAlbums")),
        cache = decodeMethod(root.getJSONObject("cache")),
        photoCount = decodeField(root.getJSONObject("photoCount")),
        videoCount = decodeField(root.getJSONObject("videoCount")),
        source = ResolutionSource.CACHE,
    )

    private fun encodeMethod(ref: MethodRef): JSONObject = JSONObject()
        .put("descriptor", ref.descriptor)
        .put("className", ref.className)
        .put("name", ref.name)
        .put("returnTypeName", ref.returnTypeName)
        .put("parameterTypeNames", JSONArray(ref.parameterTypeNames))
        .put("isStatic", ref.isStatic)

    private fun decodeMethod(value: JSONObject): MethodRef {
        val parameters = value.getJSONArray("parameterTypeNames")
        return MethodRef(
            descriptor = value.getString("descriptor"),
            className = value.getString("className"),
            name = value.getString("name"),
            returnTypeName = value.getString("returnTypeName"),
            parameterTypeNames = List(parameters.length()) { index ->
                parameters.getString(index)
            },
            isStatic = value.getBoolean("isStatic"),
        )
    }

    private fun encodeField(ref: FieldRef): JSONObject = JSONObject()
        .put("descriptor", ref.descriptor)
        .put("className", ref.className)
        .put("name", ref.name)
        .put("typeName", ref.typeName)
        .put("isStatic", ref.isStatic)

    private fun decodeField(value: JSONObject): FieldRef = FieldRef(
        descriptor = value.getString("descriptor"),
        className = value.getString("className"),
        name = value.getString("name"),
        typeName = value.getString("typeName"),
        isStatic = value.getBoolean("isStatic"),
    )

    private companion object {
        const val PREFERENCES_FILE = "coloros_feiniu_bridge"
        const val CACHE_SCHEMA = 1
    }
}
