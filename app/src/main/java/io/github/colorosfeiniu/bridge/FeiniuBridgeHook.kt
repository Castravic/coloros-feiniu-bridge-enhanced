package io.github.colorosfeiniu.bridge

import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.lang.reflect.Method
import java.util.zip.ZipFile

class FeiniuBridgeHook : XposedModule() {

    override fun onPackageReady(param: PackageReadyParam) {
        val profile = TokenDecryptorTargets.forPackage(param.packageName) ?: return

        runCatching {
            val appInfo = param.applicationInfo
            val classLoader = param.classLoader
            val target = TargetResolver.resolve(classLoader, appInfo, profile, ::logInfo)
            target.methods.forEach { method ->
                method.isAccessible = true
                hook(method).intercept { chain ->
                    interceptPrefixCall(chain, appInfo)
                }
            }

            if (target.methods.isEmpty()) {
                logWarn("prefix fallback unavailable for ${param.packageName}")
            } else {
                logInfo("installed for ${param.packageName} class=${target.className} via=${target.source}")
            }
        }.onFailure { error ->
            logError("install failed: ${error.javaClass.simpleName}: ${error.message}", error)
        }
    }

    private fun interceptPrefixCall(chain: Chain, appInfo: ApplicationInfo): Any? {
        val result = chain.proceed()
        if (!result.isNullOrBlankString()) return result

        val resolved = PrefixResolver.resolve(appInfo, ::logInfo)
        if (resolved == null) {
            logWarn("prefix fallback unavailable")
            return result
        }

        if (shouldLogFallback()) {
            logInfo("prefix fallback supplied source=${resolved.source} len=${resolved.value.length}")
        }
        return resolved.value
    }

    private fun logInfo(message: String) {
        log(Log.INFO, TAG, message)
    }

    private fun logWarn(message: String) {
        log(Log.WARN, TAG, message)
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            log(Log.ERROR, TAG, message, throwable)
        } else {
            log(Log.ERROR, TAG, message)
        }
    }

    private fun shouldLogFallback(): Boolean {
        return !fallbackLogged && synchronized(fallbackLock) {
            if (fallbackLogged) {
                false
            } else {
                fallbackLogged = true
                true
            }
        }
    }

    /**
     * Locates the prefix loader to hook for one target profile.
     *
     * A known class is accepted immediately only when its full method contract is confirmed. If
     * none qualify, structural DEX discovery runs before the name-only fallback kept for legacy
     * Gallery builds that expose the prefix loader but not the newer decrypt entry point.
     */
    private object TargetResolver {

        fun resolve(
            classLoader: ClassLoader,
            appInfo: ApplicationInfo,
            profile: TokenDecryptorProfile,
            logger: (String) -> Unit,
        ): Target {
            val knownCandidates = profile.classNames
                .mapNotNull { className -> findClass(className, classLoader) }
            val resolved = TokenDecryptorTargetResolver.resolve(
                knownCandidates,
                hasPrefixLoader = { prefixMethodsOf(it, profile).isNotEmpty() },
                hasDecryptEntryPoint = { declaresDecryptEntryPoint(it, profile) },
                locateByShape = { resolveClassByShape(classLoader, appInfo, profile, logger) },
            ) ?: return Target(emptyList(), null, "none")

            return Target(
                prefixMethodsOf(resolved.target, profile),
                resolved.target.name,
                resolved.source.logValue,
            )
        }

        private fun resolveClassByShape(
            classLoader: ClassLoader,
            appInfo: ApplicationInfo,
            profile: TokenDecryptorProfile,
            logger: (String) -> Unit,
        ): Class<*>? {
            val className = ApkDex.scan(appInfo, logger) { bytes ->
                TokenDecryptorLocator.locate(bytes, profile)
            }
            if (className == null) {
                logger("dex scan did not find a token decryptor class for ${profile.packageName}")
                return null
            }

            val clazz = findClass(className, classLoader)
            if (clazz == null) {
                logger("dex scan matched $className but it is not loadable")
                return null
            }

            val methods = prefixMethodsOf(clazz, profile)
            if (methods.isEmpty()) return null
            return clazz
        }

        private fun findClass(className: String, classLoader: ClassLoader): Class<*>? =
            runCatching { Class.forName(className, false, classLoader) }.getOrNull()

        private fun prefixMethodsOf(
            clazz: Class<*>,
            profile: TokenDecryptorProfile,
        ): List<Method> =
            runCatching {
                clazz.declaredMethods.filter { method ->
                    profile.matchesPrefixLoader(method.name) &&
                        method.returnType == String::class.java &&
                        method.parameterTypes.isEmpty()
                }
            }.getOrDefault(emptyList())

        private fun declaresDecryptEntryPoint(
            clazz: Class<*>,
            profile: TokenDecryptorProfile,
        ): Boolean =
            runCatching {
                clazz.declaredMethods.any { method ->
                    profile.matchesDecryptEntryPoint(method.name) &&
                        method.returnType == String::class.java &&
                        method.parameterTypes.size == 2 &&
                        method.parameterTypes.all { it == String::class.java }
                }
            }.getOrDefault(false)
    }

    private class Target(
        val methods: List<Method>,
        val className: String?,
        val source: String,
    )

    private object PrefixResolver {
        @Volatile
        private var cachedPrefix: ResolvedPrefix? = null

        fun resolve(appInfo: ApplicationInfo, logger: (String) -> Unit): ResolvedPrefix? {
            cachedPrefix?.let { return it }

            val fromApk = ApkDex.scan(appInfo, logger) { bytes ->
                DexFile.parse(bytes)?.firstString { it.isFeiniuPrefix() }
            }
            val resolved = fromApk?.let { ResolvedPrefix(it, "apk-dex") }
                ?: ResolvedPrefix(KNOWN_PREFIX, "builtin")

            cachedPrefix = resolved
            return resolved
        }

        private fun String.isFeiniuPrefix(): Boolean =
            length in 16..80 && PREFIX_REGEX.matches(this)
    }

    /** Walks the DEX images of the installed target APKs. */
    private object ApkDex {

        fun <T : Any> scan(
            appInfo: ApplicationInfo?,
            logger: (String) -> Unit,
            transform: (ByteArray) -> T?,
        ): T? {
            val sourcePaths = buildList {
                add(appInfo?.sourceDir)
                appInfo?.splitSourceDirs?.let(::addAll)
            }.filterNotNull()

            if (sourcePaths.isEmpty()) {
                logger("apk scan skipped: no source paths")
                return null
            }

            for (sourcePath in sourcePaths) {
                scanApk(File(sourcePath), logger, transform)?.let { return it }
            }
            return null
        }

        private fun <T : Any> scanApk(
            apk: File,
            logger: (String) -> Unit,
            transform: (ByteArray) -> T?,
        ): T? {
            if (!apk.isFile) {
                logger("apk scan skipped: missing ${apk.path}")
                return null
            }

            return try {
                ZipFile(apk).use { zipFile ->
                    val dexEntries = zipFile.entries().asSequence()
                        .filter { it.name.endsWith(".dex") }
                        .toList()

                    if (dexEntries.isEmpty()) {
                        logger("apk scan skipped: no dex entries in ${apk.name}")
                        return null
                    }

                    for (entry in dexEntries) {
                        val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                        transform(bytes)?.let { return it }
                    }
                }
                null
            } catch (error: Throwable) {
                logger("apk scan failed for ${apk.name}: ${error.javaClass.simpleName}: ${error.message}")
                null
            }
        }
    }

    private data class ResolvedPrefix(
        val value: String,
        val source: String,
    )

    companion object {
        private const val TAG = "ColorOSFeiniuBridge"
        private const val KNOWN_PREFIX = "tRiM@2025#GwToken!sEcReT*kEy&vALu"
        private val PREFIX_REGEX = Regex("""[A-Za-z][A-Za-z0-9@#_!*&$%+?.-]{7,79}GwToken[A-Za-z0-9@#_!*&$%+?.-]{4,80}""")

        @Volatile
        private var fallbackLogged = false
        private val fallbackLock = Any()

        private fun Any?.isNullOrBlankString(): Boolean {
            return (this as? String).isNullOrBlank()
        }
    }
}
