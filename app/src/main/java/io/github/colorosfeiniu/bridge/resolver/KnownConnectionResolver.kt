package io.github.colorosfeiniu.bridge.resolver

import java.lang.reflect.Modifier

internal data class KnownGroups(
    val token: TokenHookRefs?,
    val gallery: GalleryHookRefs?,
)

internal object KnownConnectionResolver {
    fun resolve(classLoader: ClassLoader): KnownGroups = KnownGroups(
        token = resolveToken(classLoader),
        gallery = resolveGallery(classLoader),
    )

    private fun resolveToken(classLoader: ClassLoader): TokenHookRefs? =
        TOKEN_CLASSES.mapNotNull { className ->
            runCatching {
                val type = Class.forName(className, false, classLoader)
                val prefix = type.declaredMethods.single { method ->
                    method.name == PREFIX_METHOD &&
                        !Modifier.isStatic(method.modifiers) &&
                        method.returnType == String::class.java &&
                        method.parameterTypes.isEmpty()
                }
                val decrypt = type.declaredMethods.single { method ->
                    method.name == DECRYPT_METHOD &&
                        !Modifier.isStatic(method.modifiers) &&
                        method.returnType == String::class.java &&
                        method.parameterTypes.contentEquals(
                            arrayOf(String::class.java, String::class.java),
                        )
                }
                TokenHookRefs(
                    prefix = prefix.toMethodRef(),
                    decrypt = decrypt.toMethodRef(),
                    source = ResolutionSource.KNOWN,
                )
            }.getOrNull()
        }.filter {
            ConnectionHookValidator.validateToken(it, classLoader) != null
        }.singleOrNull()

    private fun resolveGallery(classLoader: ClassLoader): GalleryHookRefs? =
        GALLERY_VARIANTS.mapNotNull { names ->
            runCatching {
                val provider = Class.forName(names.providerClass, false, classLoader)
                val cacheClass = Class.forName(names.cacheClass, false, classLoader)
                val statClass = Class.forName(names.statClass, false, classLoader)
                val stat = provider.declaredMethods.single { it.name == names.statMethod }
                val albums = provider.declaredMethods.single { it.name == names.albumsMethod }
                val connection = provider.declaredMethods.single { it.name == names.connectionMethod }
                val realAlbums = provider.declaredMethods.single { it.name == names.realAlbumsMethod }
                val cache = cacheClass.declaredMethods.single { it.name == names.cacheMethod }
                val photoCount = statClass.getDeclaredField(names.photoField)
                val videoCount = statClass.getDeclaredField(names.videoField)
                GalleryHookRefs(
                    stat = stat.toMethodRef(),
                    albums = albums.toMethodRef(),
                    connection = connection.toMethodRef(),
                    realAlbums = realAlbums.toMethodRef(),
                    cache = cache.toMethodRef(),
                    photoCount = photoCount.toFieldRef(),
                    videoCount = videoCount.toFieldRef(),
                    source = ResolutionSource.KNOWN,
                )
            }.getOrNull()
        }.filter {
            ConnectionHookValidator.validateGallery(it, classLoader) != null
        }.singleOrNull()

    private data class GalleryNames(
        val providerClass: String,
        val cacheClass: String,
        val statClass: String,
        val statMethod: String,
        val albumsMethod: String,
        val connectionMethod: String,
        val realAlbumsMethod: String,
        val cacheMethod: String,
        val photoField: String,
        val videoField: String,
    )

    private const val PREFIX_METHOD = "e"
    private const val DECRYPT_METHOD = "b"
    private val TOKEN_CLASSES = listOf(
        "com.oplus.aiunit.vision.erq",
        "com.oplus.aiunit.vision.in80",
        "com.oplus.aiunit.vision.op80",
        "com.oplus.aiunit.vision.qp80",
    )
    private val GALLERY_VARIANTS = listOf(
        GalleryNames(
            "com.oplus.aiunit.vision.z0g",
            "com.oplus.aiunit.vision.b6q",
            "com.oplus.aiunit.vision.y8q",
            "J",
            "l",
            "H",
            "F",
            "f",
            "a",
            "b",
        ),
        GalleryNames(
            "com.oplus.aiunit.vision.n1g",
            "com.oplus.aiunit.vision.q6q",
            "com.oplus.aiunit.vision.n9q",
            "J",
            "l",
            "H",
            "F",
            "f",
            "a",
            "b",
        ),
    )
}
