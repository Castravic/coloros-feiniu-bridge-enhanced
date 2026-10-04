package io.github.colorosfeiniu.bridge

import io.github.colorosfeiniu.bridge.resolver.ValidatedGalleryHooks
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Restores the private cloud album list from the real album data when the Feiniu gallery stat
 * endpoint fails. Ported from the legacy Xposed after/before hooks to libxposed intercept chains.
 */
internal object GalleryStatFallback {

    fun install(
        module: XposedInterface,
        validated: ValidatedGalleryHooks,
        logger: (String) -> Unit,
    ) {
        this.logger = logger
        val installed = InstalledVariant(
            cacheMethod = validated.cache,
            connectionMethod = validated.connection,
            realAlbumsMethod = validated.realAlbums,
            photoCount = { value -> validated.photoCount.getInt(value) },
            videoCount = { value -> validated.videoCount.getInt(value) },
        )
        module.hook(validated.stat).intercept(StatHook(installed))
        module.hook(validated.albums).intercept(AlbumsHook(installed))
        log("gallery stat fallback installed source=validated")
    }

    private class StatHook(
        private val variant: InstalledVariant,
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val deviceId = chain.getArg(1) as? String ?: return chain.proceed()

            val result = try {
                chain.proceed()
            } catch (error: Throwable) {
                if (!isEligibleStatFailure(error)) throw error

                val cached = readCachedStat(variant, deviceId)
                if (cached == null || cached.photos.toLong() + cached.videos.toLong() <= 0L) {
                    variant.statlessDevices.add(deviceId)
                    logBounded(
                        "gallery stat fallback cache unavailable; enabling real-albums mode",
                    )
                    throw error
                }

                variant.statlessDevices.remove(deviceId)
                logBounded(
                    "gallery stat fallback used source=local-cache photos=${cached.photos} " +
                        "videos=${cached.videos}",
                )
                return cached.value
            }

            variant.statlessDevices.remove(deviceId)
            return result
        }
    }

    private fun readCachedStat(variant: InstalledVariant, deviceId: String): CachedStat? =
        runCatching {
            val value = variant.cacheMethod.invoke(null, deviceId) ?: return@runCatching null
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

    private class AlbumsHook(
        private val variant: InstalledVariant,
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val deviceId = chain.getArg(2) as? String
            val provider = chain.getThisObject() ?: return chain.proceed()

            if (deviceId != null && variant.statlessDevices.contains(deviceId)) {
                val offset = chain.getArg(0) as Int
                val limit = chain.getArg(1) as Int
                return runCatching {
                    invokeRealAlbums(provider, deviceId, limit, offset)
                }.onSuccess { result ->
                    logRealAlbums(offset, limit, result)
                }.getOrElse { error ->
                    val failure = unwrapStageFailure(error)
                    logFallbackFailure(failure)
                    throw failure.cause
                }
            }

            try {
                return chain.proceed()
            } catch (original: Throwable) {
                if (deviceId == null || !isEligibleStatFailure(original)) throw original

                val offset = chain.getArg(0) as Int
                val limit = chain.getArg(1) as Int
                variant.statlessDevices.add(deviceId)

                return runCatching {
                    invokeRealAlbums(provider, deviceId, limit, offset)
                }.onSuccess { result ->
                    logRealAlbums(offset, limit, result)
                }.getOrElse { error ->
                    logFallbackFailure(unwrapStageFailure(error))
                    throw original
                }
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
        logger.invoke(message)
    }

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

    private const val STAT_FAILURE_PREFIX = "getGalleryStat failed for device:"
    private const val STAT_TIMEOUT_PREFIX = "getGalleryStat timeout for device:"
    private const val STAGE_CONNECTION = "connection"
    private const val STAGE_ALBUMS = "albums"
    private const val MAX_CAUSE_DEPTH = 8
    private const val MAX_DIAGNOSTIC_EVENTS = 40

    @Volatile
    private var logger: (String) -> Unit = {}

    private val logLock = Any()
    private var loggedEvents = 0
}
