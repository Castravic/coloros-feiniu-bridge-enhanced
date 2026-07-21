package io.github.colorosfeiniu.bridge

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

internal object GalleryStatFallback {
    fun install(classLoader: ClassLoader) {
        val installed = VARIANTS.mapNotNull { variant ->
            installVariant(classLoader, variant)
        }
        if (installed.isEmpty()) {
            log("gallery stat fallback unavailable")
        } else {
            log("gallery stat fallback installed variants=${installed.size}")
        }
    }

    private fun installVariant(
        classLoader: ClassLoader,
        variant: GalleryVariant,
    ): InstalledVariant? = runCatching {
        val providerClass = Class.forName(variant.providerClass, false, classLoader)
        val cacheClass = Class.forName(variant.cacheClass, false, classLoader)
        val statClass = Class.forName(variant.statClass, false, classLoader)

        val statMethod = providerClass.declaredMethods.single { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == STAT_METHOD &&
                method.returnType == statClass &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[1] == String::class.java
        }.accessible()
        val albumsMethod = providerClass.declaredMethods.single { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == ALBUMS_METHOD &&
                List::class.java.isAssignableFrom(method.returnType) &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        String::class.java,
                    ),
                )
        }.accessible()
        val connectionMethod = providerClass.declaredMethods.single { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == CONNECTION_METHOD &&
                method.returnType != Void.TYPE &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        String::class.java,
                        Boolean::class.javaPrimitiveType,
                    ),
                )
        }.accessible()
        val realAlbumsMethod = providerClass.declaredMethods.single { method ->
            !Modifier.isStatic(method.modifiers) &&
                method.name == REAL_ALBUMS_METHOD &&
                List::class.java.isAssignableFrom(method.returnType) &&
                method.parameterTypes.contentEquals(
                    arrayOf(
                        connectionMethod.returnType,
                        String::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    ),
                )
        }.accessible()
        val cacheMethod = cacheClass.declaredMethods.single { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == CACHE_METHOD &&
                method.returnType == statClass &&
                method.parameterTypes.contentEquals(arrayOf(String::class.java))
        }.accessible()
        val photoCountField = statClass.getDeclaredField(PHOTO_COUNT_FIELD).apply {
            isAccessible = true
        }
        val videoCountField = statClass.getDeclaredField(VIDEO_COUNT_FIELD).apply {
            isAccessible = true
        }
        require(photoCountField.type == Int::class.javaPrimitiveType)
        require(videoCountField.type == Int::class.javaPrimitiveType)

        InstalledVariant(
            cacheMethod = cacheMethod,
            connectionMethod = connectionMethod,
            realAlbumsMethod = realAlbumsMethod,
            photoCount = { value -> photoCountField.getInt(value) },
            videoCount = { value -> videoCountField.getInt(value) },
        ).also { installedVariant ->
            XposedBridge.hookMethod(statMethod, StatHook(installedVariant))
            XposedBridge.hookMethod(albumsMethod, AlbumsHook(installedVariant))
        }
    }.onFailure { error ->
        if (error !is ClassNotFoundException) {
            logBounded(
                "gallery stat fallback failed stage=install type=${error.javaClass.simpleName}",
            )
        }
    }.getOrNull()

    private fun Method.accessible(): Method = apply {
        isAccessible = true
    }

    private class StatHook(
        private val variant: InstalledVariant,
    ) : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val deviceId = param.args.getOrNull(1) as? String ?: return
            val original = param.throwable
            if (original == null) {
                variant.statlessDevices.remove(deviceId)
                return
            }
            if (!isEligibleStatFailure(original)) return

            val cached = runCatching {
                val value = variant.cacheMethod.invoke(null, deviceId)
                    ?: return@runCatching null
                CachedStat(
                    value = value,
                    photos = variant.photoCount(value),
                    videos = variant.videoCount(value),
                )
            }.onFailure { error ->
                logBounded(
                    "gallery stat fallback failed stage=cache type=${unwrap(error).javaClass.simpleName}",
                )
            }.getOrNull()

            if (cached == null || cached.photos.toLong() + cached.videos.toLong() <= 0L) {
                variant.statlessDevices.add(deviceId)
                logBounded(
                    "gallery stat fallback cache unavailable; enabling real-albums mode",
                )
                return
            }

            variant.statlessDevices.remove(deviceId)
            param.result = cached.value
            logBounded(
                "gallery stat fallback used source=local-cache photos=${cached.photos} videos=${cached.videos}",
            )
        }
    }

    private class AlbumsHook(
        private val variant: InstalledVariant,
    ) : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val deviceId = param.args.getOrNull(2) as? String ?: return
            if (!variant.statlessDevices.contains(deviceId)) return

            val offset = param.args[0] as Int
            val limit = param.args[1] as Int
            runCatching {
                invokeRealAlbums(param.thisObject, deviceId, limit, offset)
            }.onSuccess { result ->
                param.result = result
                logRealAlbums(offset, limit, result)
            }.onFailure { error ->
                val failure = unwrapStageFailure(error)
                param.throwable = failure.cause
                logFallbackFailure(failure)
            }
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val original = param.throwable?.takeIf(::isEligibleStatFailure) ?: return
            val deviceId = param.args.getOrNull(2) as? String ?: return
            val offset = param.args[0] as Int
            val limit = param.args[1] as Int
            variant.statlessDevices.add(deviceId)

            runCatching {
                invokeRealAlbums(param.thisObject, deviceId, limit, offset)
            }.onSuccess { result ->
                param.result = result
                logRealAlbums(offset, limit, result)
            }.onFailure { error ->
                param.throwable = original
                logFallbackFailure(unwrapStageFailure(error))
            }
        }

        private fun invokeRealAlbums(
            provider: Any,
            deviceId: String,
            limit: Int,
            offset: Int,
        ): List<*> {
            val connection = try {
                variant.connectionMethod.invoke(provider, deviceId, false)
            } catch (error: Throwable) {
                throw FallbackStageException(STAGE_CONNECTION, unwrap(error))
            } ?: throw FallbackStageException(
                STAGE_CONNECTION,
                IllegalStateException("connection unavailable"),
            )

            val result = try {
                variant.realAlbumsMethod.invoke(
                    provider,
                    connection,
                    deviceId,
                    limit,
                    offset,
                )
            } catch (error: Throwable) {
                throw FallbackStageException(STAGE_ALBUMS, unwrap(error))
            }
            return result as? List<*>
                ?: throw FallbackStageException(
                    STAGE_ALBUMS,
                    IllegalStateException("real albums result is not a list"),
                )
        }
    }

    private fun isEligibleStatFailure(error: Throwable): Boolean {
        var cursor: Throwable? = error
        repeat(MAX_CAUSE_DEPTH) {
            val message = cursor?.message.orEmpty()
            if (message.startsWith(STAT_TIMEOUT_PREFIX)) return false
            if (message.startsWith(STAT_FAILURE_PREFIX)) return true
            cursor = cursor?.cause
        }
        return false
    }

    private fun logRealAlbums(
        offset: Int,
        limit: Int,
        result: List<*>,
    ) {
        logBounded(
            "gallery stat fallback real-albums result offset=$offset limit=$limit count=${result.size}",
        )
    }

    private fun logFallbackFailure(failure: FallbackStageException) {
        logBounded(
            "gallery stat fallback failed stage=${failure.stage} " +
                "type=${failure.cause.javaClass.simpleName}",
        )
    }

    private fun unwrapStageFailure(error: Throwable): FallbackStageException =
        error as? FallbackStageException
            ?: FallbackStageException(STAGE_ALBUMS, unwrap(error))

    private fun unwrap(error: Throwable): Throwable =
        (error as? InvocationTargetException)?.targetException ?: error

    private fun logBounded(message: String) {
        val shouldLog = synchronized(logLock) {
            if (loggedEvents >= MAX_DIAGNOSTIC_EVENTS) {
                false
            } else {
                loggedEvents += 1
                true
            }
        }
        if (shouldLog) log(message)
    }

    private fun log(message: String) {
        XposedBridge.log("ColorOSFeiniuBridge: $message")
    }

    private data class GalleryVariant(
        val providerClass: String,
        val cacheClass: String,
        val statClass: String,
    )

    private data class InstalledVariant(
        val cacheMethod: Method,
        val connectionMethod: Method,
        val realAlbumsMethod: Method,
        val photoCount: (Any) -> Int,
        val videoCount: (Any) -> Int,
        val statlessDevices: MutableSet<String> = ConcurrentHashMap.newKeySet(),
    )

    private data class CachedStat(
        val value: Any,
        val photos: Int,
        val videos: Int,
    )

    private class FallbackStageException(
        val stage: String,
        override val cause: Throwable,
    ) : RuntimeException(cause)

    private const val STAT_METHOD = "J"
    private const val ALBUMS_METHOD = "l"
    private const val CONNECTION_METHOD = "H"
    private const val REAL_ALBUMS_METHOD = "F"
    private const val CACHE_METHOD = "f"
    private const val PHOTO_COUNT_FIELD = "a"
    private const val VIDEO_COUNT_FIELD = "b"
    private const val STAT_FAILURE_PREFIX = "getGalleryStat failed for device:"
    private const val STAT_TIMEOUT_PREFIX = "getGalleryStat timeout for device:"
    private const val STAGE_CONNECTION = "connection"
    private const val STAGE_ALBUMS = "albums"
    private const val MAX_CAUSE_DEPTH = 8
    private const val MAX_DIAGNOSTIC_EVENTS = 40

    private val VARIANTS = listOf(
        GalleryVariant("com.oplus.aiunit.vision.z0g", "com.oplus.aiunit.vision.b6q", "com.oplus.aiunit.vision.y8q"),
        GalleryVariant("com.oplus.aiunit.vision.n1g", "com.oplus.aiunit.vision.q6q", "com.oplus.aiunit.vision.n9q"),
    )
    private val logLock = Any()
    private var loggedEvents = 0
}
