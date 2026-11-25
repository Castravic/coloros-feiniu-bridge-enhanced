package io.github.colorosfeiniu.bridge.resolver

import android.app.Application
import android.content.Context
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicBoolean

internal data class ConnectionResolutionStatus(
    val tokenResolved: Boolean,
    val galleryResolved: Boolean,
    val tokenCacheStale: Boolean = false,
    val galleryCacheStale: Boolean = false,
)

/**
 * Resolves the gallery connection hooks the Enhanced line needs, in order: known obfuscated names,
 * the per-APK fingerprint cache, then DexKit semantic discovery.
 */
internal object ConnectionResolutionBootstrap {
    fun tryInstallKnown(
        classLoader: ClassLoader,
        needToken: Boolean,
        needGallery: Boolean,
        logger: (String) -> Unit,
        tokenInstaller: (ValidatedTokenHooks, ResolutionSource) -> Unit,
        galleryInstaller: (ValidatedGalleryHooks, ResolutionSource) -> Unit,
    ): ConnectionResolutionStatus {
        this.logger = logger
        val known = KnownConnectionResolver.resolve(classLoader)
        val tokenResolved = !needToken || installToken(
            refs = known.token,
            classLoader = classLoader,
            installer = tokenInstaller,
        )
        val galleryResolved = !needGallery || installGallery(
            refs = known.gallery,
            classLoader = classLoader,
            installer = galleryInstaller,
        )
        return ConnectionResolutionStatus(
            tokenResolved = tokenResolved,
            galleryResolved = galleryResolved,
        )
    }

    fun tryInstallCached(
        cache: ConnectionResolutionCache,
        classLoader: ClassLoader,
        needToken: Boolean,
        needGallery: Boolean,
        logger: (String) -> Unit,
        tokenInstaller: (ValidatedTokenHooks, ResolutionSource) -> Unit,
        galleryInstaller: (ValidatedGalleryHooks, ResolutionSource) -> Unit,
    ): ConnectionResolutionStatus {
        this.logger = logger
        var tokenResolved = !needToken
        var tokenCacheStale = false
        if (needToken) {
            val hadEntry = cache.hasToken()
            val cached = cache.readToken { refs ->
                ConnectionHookValidator.validateToken(refs, classLoader) != null
            }
            tokenResolved = installToken(
                refs = cached,
                classLoader = classLoader,
                installer = tokenInstaller,
            )
            tokenCacheStale = hadEntry && !tokenResolved
        }

        var galleryResolved = !needGallery
        var galleryCacheStale = false
        if (needGallery) {
            val hadEntry = cache.hasGallery()
            val cached = cache.readGallery { refs ->
                ConnectionHookValidator.validateGallery(refs, classLoader) != null
            }
            galleryResolved = installGallery(
                refs = cached,
                classLoader = classLoader,
                installer = galleryInstaller,
            )
            galleryCacheStale = hadEntry && !galleryResolved
        }

        return ConnectionResolutionStatus(
            tokenResolved = tokenResolved,
            galleryResolved = galleryResolved,
            tokenCacheStale = tokenCacheStale,
            galleryCacheStale = galleryCacheStale,
        )
    }

    fun resolveSemanticallyAndInstall(
        cache: ConnectionResolutionCache?,
        classLoader: ClassLoader,
        needToken: Boolean,
        needGallery: Boolean,
        logger: (String) -> Unit,
        tokenInstaller: (ValidatedTokenHooks, ResolutionSource) -> Unit,
        galleryInstaller: (ValidatedGalleryHooks, ResolutionSource) -> Unit,
    ): ConnectionResolutionStatus {
        this.logger = logger
        var tokenResolved = !needToken
        var galleryResolved = !needGallery
        if (tokenResolved && galleryResolved) {
            return ConnectionResolutionStatus(tokenResolved = true, galleryResolved = true)
        }

        runCatching {
            val semantic = SemanticDexResolver.resolve(
                classLoader = classLoader,
                needToken = !tokenResolved,
                needGallery = !galleryResolved,
            )
            log(
                "semantic-scan tokenCandidates=${semantic.tokenCandidateCount} " +
                    "galleryCandidates=${semantic.galleryCandidateCount} " +
                    "elapsedMs=${semantic.elapsedMs}",
            )
            if (!tokenResolved && semantic.token != null) {
                tokenResolved = installToken(
                    refs = semantic.token,
                    classLoader = classLoader,
                    installer = tokenInstaller,
                )
                if (tokenResolved) cache?.writeToken(semantic.token)
            }
            if (!galleryResolved && semantic.gallery != null) {
                galleryResolved = installGallery(
                    refs = semantic.gallery,
                    classLoader = classLoader,
                    installer = galleryInstaller,
                )
                if (galleryResolved) cache?.writeGallery(semantic.gallery)
            }
            if (!tokenResolved) {
                log(
                    "token resolver unavailable candidates=" +
                        semantic.tokenCandidateCount,
                )
            }
            if (!galleryResolved) {
                log(
                    "gallery resolver unavailable candidates=" +
                        semantic.galleryCandidateCount,
                )
            }
        }.onFailure { error ->
            val stage = if (error is UnsatisfiedLinkError) {
                "native-load"
            } else {
                "semantic-scan"
            }
            log(
                "resolver stage=$stage result=unavailable " +
                    "type=${error.javaClass.simpleName}",
            )
        }

        return ConnectionResolutionStatus(
            tokenResolved = tokenResolved,
            galleryResolved = galleryResolved,
        )
    }

    fun install(
        module: XposedInterface,
        classLoader: ClassLoader,
        needToken: Boolean,
        needGallery: Boolean,
        logger: (String) -> Unit,
        tokenInstaller: (ValidatedTokenHooks, ResolutionSource) -> Unit,
        galleryInstaller: (ValidatedGalleryHooks, ResolutionSource) -> Unit,
    ) {
        val known = tryInstallKnown(
            classLoader = classLoader,
            needToken = needToken,
            needGallery = needGallery,
            logger = logger,
            tokenInstaller = tokenInstaller,
            galleryInstaller = galleryInstaller,
        )
        var tokenResolved = known.tokenResolved
        var galleryResolved = known.galleryResolved
        if (tokenResolved && galleryResolved) return

        if (!attachHookInstalled.compareAndSet(false, true)) return
        runCatching {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            attach.isAccessible = true
            module.hook(attach).intercept { chain ->
                val result = chain.proceed()
                val context = chain.getArg(0) as? Context
                if (context != null && resolutionStarted.compareAndSet(false, true)) {
                    runCatching {
                        val storageContext = context.createDeviceProtectedStorageContext()
                        val fingerprint = GalleryFingerprint.from(context)
                        val cache = ConnectionResolutionCache(storageContext, fingerprint)
                        val cached = tryInstallCached(
                            cache = cache,
                            classLoader = classLoader,
                            needToken = !tokenResolved,
                            needGallery = !galleryResolved,
                            logger = logger,
                            tokenInstaller = tokenInstaller,
                            galleryInstaller = galleryInstaller,
                        )
                        tokenResolved = tokenResolved || cached.tokenResolved
                        galleryResolved = galleryResolved || cached.galleryResolved
                        if (tokenResolved && galleryResolved) return@runCatching

                        val semantic = resolveSemanticallyAndInstall(
                            cache = cache,
                            classLoader = classLoader,
                            needToken = !tokenResolved,
                            needGallery = !galleryResolved,
                            logger = logger,
                            tokenInstaller = tokenInstaller,
                            galleryInstaller = galleryInstaller,
                        )
                        tokenResolved = tokenResolved || semantic.tokenResolved
                        galleryResolved = galleryResolved || semantic.galleryResolved
                    }.onFailure { error ->
                        log(
                            "resolver stage=cache-init result=unavailable " +
                                "type=${error.javaClass.simpleName}",
                        )
                    }
                }
                result
            }
        }.onFailure { error ->
            log(
                "resolver stage=attach-hook result=unavailable " +
                    "type=${error.javaClass.simpleName}",
            )
        }
    }

    private fun installToken(
        refs: TokenHookRefs?,
        classLoader: ClassLoader,
        installer: (ValidatedTokenHooks, ResolutionSource) -> Unit,
    ): Boolean {
        refs ?: return false
        val validated = ConnectionHookValidator.validateToken(refs, classLoader) ?: return false
        return runCatching {
            installer(validated, refs.source)
            log(
                "token resolver source=${refs.source.name.lowercase()} " +
                    "class=${refs.prefix.className}",
            )
        }.onFailure { error ->
            log("resolver stage=install-token result=failed type=${error.javaClass.simpleName}")
        }.isSuccess
    }

    private fun installGallery(
        refs: GalleryHookRefs?,
        classLoader: ClassLoader,
        installer: (ValidatedGalleryHooks, ResolutionSource) -> Unit,
    ): Boolean {
        refs ?: return false
        val validated = ConnectionHookValidator.validateGallery(refs, classLoader) ?: return false
        return runCatching {
            installer(validated, refs.source)
            log(
                "gallery resolver source=${refs.source.name.lowercase()} " +
                    "class=${refs.stat.className}",
            )
        }.onFailure { error ->
            log("resolver stage=install-gallery result=failed type=${error.javaClass.simpleName}")
        }.isSuccess
    }

    private fun log(message: String) {
        val shouldLog = synchronized(logLock) {
            if (loggedEvents >= MAX_LOG_EVENTS) {
                false
            } else {
                loggedEvents += 1
                true
            }
        }
        if (shouldLog) logger.invoke(message)
    }

    private const val MAX_LOG_EVENTS = 40
    private val attachHookInstalled = AtomicBoolean(false)
    private val resolutionStarted = AtomicBoolean(false)
    private val logLock = Any()
    private var loggedEvents = 0

    @Volatile
    private var logger: (String) -> Unit = {}
}
