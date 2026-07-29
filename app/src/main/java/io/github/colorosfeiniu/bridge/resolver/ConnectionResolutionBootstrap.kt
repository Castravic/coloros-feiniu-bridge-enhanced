package io.github.colorosfeiniu.bridge.resolver

import android.app.Application
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean

internal object ConnectionResolutionBootstrap {
    fun install(
        lpparam: XC_LoadPackage.LoadPackageParam,
        tokenInstaller: (ValidatedTokenHooks, ResolutionSource) -> Unit,
        galleryInstaller: (ValidatedGalleryHooks, ResolutionSource) -> Unit,
    ) {
        val known = KnownConnectionResolver.resolve(lpparam.classLoader)
        var tokenResolved = installToken(
            refs = known.token,
            classLoader = lpparam.classLoader,
            installer = tokenInstaller,
        )
        var galleryResolved = installGallery(
            refs = known.gallery,
            classLoader = lpparam.classLoader,
            installer = galleryInstaller,
        )
        if (tokenResolved && galleryResolved) return

        if (!attachHookInstalled.compareAndSet(false, true)) return
        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args.firstOrNull() as? Context ?: return
                        if (!resolutionStarted.compareAndSet(false, true)) return
                        runCatching {
                            val storageContext = context.createDeviceProtectedStorageContext()
                            val fingerprint = GalleryFingerprint.from(context)
                            val cache = ConnectionResolutionCache(storageContext, fingerprint)

                            if (!tokenResolved) {
                                val cached = cache.readToken { refs ->
                                    ConnectionHookValidator.validateToken(
                                        refs,
                                        lpparam.classLoader,
                                    ) != null
                                }
                                tokenResolved = installToken(
                                    refs = cached,
                                    classLoader = lpparam.classLoader,
                                    installer = tokenInstaller,
                                )
                            }
                            if (!galleryResolved) {
                                val cached = cache.readGallery { refs ->
                                    ConnectionHookValidator.validateGallery(
                                        refs,
                                        lpparam.classLoader,
                                    ) != null
                                }
                                galleryResolved = installGallery(
                                    refs = cached,
                                    classLoader = lpparam.classLoader,
                                    installer = galleryInstaller,
                                )
                            }
                            if (tokenResolved && galleryResolved) return@runCatching

                            val semantic = SemanticDexResolver.resolve(
                                classLoader = lpparam.classLoader,
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
                                    classLoader = lpparam.classLoader,
                                    installer = tokenInstaller,
                                )
                                if (tokenResolved) cache.writeToken(semantic.token)
                            }
                            if (!galleryResolved && semantic.gallery != null) {
                                galleryResolved = installGallery(
                                    refs = semantic.gallery,
                                    classLoader = lpparam.classLoader,
                                    installer = galleryInstaller,
                                )
                                if (galleryResolved) cache.writeGallery(semantic.gallery)
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
                    }
                },
            )
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
        if (shouldLog) XposedBridge.log("ColorOSFeiniuBridge: $message")
    }

    private const val MAX_LOG_EVENTS = 40
    private val attachHookInstalled = AtomicBoolean(false)
    private val resolutionStarted = AtomicBoolean(false)
    private val logLock = Any()
    private var loggedEvents = 0
}
