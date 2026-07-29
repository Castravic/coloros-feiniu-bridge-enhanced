package io.github.colorosfeiniu.bridge.resolver

enum class ResolutionSource { KNOWN, CACHE, SEMANTIC }

data class MethodRef(
    val descriptor: String,
    val className: String,
    val name: String,
    val returnTypeName: String,
    val parameterTypeNames: List<String>,
    val isStatic: Boolean,
)

data class FieldRef(
    val descriptor: String,
    val className: String,
    val name: String,
    val typeName: String,
    val isStatic: Boolean,
)

data class TokenHookRefs(
    val prefix: MethodRef,
    val decrypt: MethodRef,
    val source: ResolutionSource,
) {
    init {
        require(prefix.className == decrypt.className)
    }
}

data class GalleryHookRefs(
    val stat: MethodRef,
    val albums: MethodRef,
    val connection: MethodRef,
    val realAlbums: MethodRef,
    val cache: MethodRef,
    val photoCount: FieldRef,
    val videoCount: FieldRef,
    val source: ResolutionSource,
)

data class ResolvedConnectionHooks(
    val token: TokenHookRefs?,
    val gallery: GalleryHookRefs?,
) {
    init {
        require(token != null || gallery != null)
    }
}
