package io.github.colorosfeiniu.bridge

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import io.github.colorosfeiniu.bridge.resolver.ConnectionResolutionBootstrap
import io.github.colorosfeiniu.bridge.resolver.EnhancementDexView
import io.github.colorosfeiniu.bridge.resolver.EnhancementLocator
import io.github.colorosfeiniu.bridge.resolver.EnhancementResolutionCache
import io.github.colorosfeiniu.bridge.resolver.EnhancementTargets
import io.github.colorosfeiniu.bridge.resolver.GalleryFingerprint
import io.github.colorosfeiniu.bridge.resolver.LocatedMethod
import io.github.colorosfeiniu.bridge.resolver.ResolutionSource
import io.github.colorosfeiniu.bridge.resolver.ValidatedTokenHooks
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Modern libxposed API 102 module entry point.
 *
 * The upstream ColorOS 17 architecture is the skeleton: [onPackageReady] resolves the token
 * decryptor per package (Gallery + Device Space) and, for Gallery, installs the Enhanced feature
 * set on top of the same interceptor chain.
 */
class FeiniuBridgeHook : XposedModule() {

    override fun onPackageReady(param: PackageReadyParam) {
        installLogSink { priority, tag, message, throwable ->
            if (throwable != null) {
                log(priority, tag, message, throwable)
            } else {
                log(priority, tag, message)
            }
        }
        BridgeReflect.missLogger = { message -> logReflectionMiss(message) }

        val packageName = param.packageName
        val profile = TokenDecryptorTargets.forPackage(packageName) ?: return
        val classLoader = param.classLoader
        val appInfo = param.applicationInfo

        val tokenTarget = runCatching {
            TargetResolver.resolve(classLoader, appInfo, profile) { message -> logInfo(message) }
        }.getOrElse { error ->
            logError(
                "token target resolution failed: ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
            Target(emptyList(), null, "none")
        }.takeIf { it.methods.isNotEmpty() }

        installTokenFallback(appInfo, tokenTarget, packageName)

        if (packageName == GALLERY_PACKAGE) {
            installGalleryFeatures(param, needTokenFallback = tokenTarget == null)
        }
    }

    private fun installTokenFallback(
        appInfo: ApplicationInfo,
        target: Target?,
        packageName: String,
    ) {
        if (target == null) {
            logWarn("prefix fallback unavailable for $packageName")
            return
        }
        runCatching {
            target.methods.forEach { method ->
                method.isAccessible = true
                hook(method).intercept { chain -> interceptPrefixCall(chain, appInfo) }
            }
            logInfo("installed for $packageName class=${target.className} via=${target.source}")
        }.onFailure { error ->
            logError("install failed: ${error.javaClass.simpleName}: ${error.message}", error)
        }
    }

    private fun installGalleryFeatures(param: PackageReadyParam, needTokenFallback: Boolean) {
        val classLoader = param.classLoader
        val appInfo = param.applicationInfo

        runCatching {
            ConnectionResolutionBootstrap.install(
                module = this,
                classLoader = classLoader,
                needToken = needTokenFallback,
                needGallery = true,
                logger = { message -> logInfo(message) },
                tokenInstaller = { hooks, source ->
                    installEnhancedTokenHooks(appInfo, hooks, source)
                },
                galleryInstaller = { hooks, _ ->
                    GalleryStatFallback.install(this, hooks) { message -> logInfo(message) }
                },
            )
        }.onFailure { error ->
            logError(
                "gallery resolver install failed: ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
        }

        installPrivateLanTlsCompatibility(classLoader)

        val enhancements = resolveGalleryEnhancements(appInfo, classLoader)
        installBackupPauseDiagnostics(classLoader, enhancements)
        installBackupTemperatureCompatibility(classLoader, enhancements)
        installBackupPauseReasonText(classLoader, enhancements)
        installMobileDataBackup(classLoader, enhancements)
        installMobileDataPreference(classLoader)
    }

    /**
     * Resolves the Gallery enhancement targets through the fingerprint cache, then a DEX scan.
     *
     * Every located target is re-validated against the live class loader before it is used or
     * cached, so a stale entry or a partially matching build degrades to a no-op.
     */
    private fun resolveGalleryEnhancements(
        appInfo: ApplicationInfo,
        classLoader: ClassLoader,
    ): EnhancementTargets {
        val context = currentApplication()
        val fingerprint = context?.let { value ->
            runCatching { GalleryFingerprint.from(value) }.getOrNull()
        }
        val cache = if (context != null && fingerprint != null) {
            EnhancementResolutionCache(context, fingerprint)
        } else {
            null
        }

        cache?.read { true }
            ?.let { cached -> validateEnhancementTargets(cached, classLoader) }
            ?.let { validated ->
                logInfo("enhancement resolver source=cache")
                return validated
            }

        val views = mutableListOf<io.github.colorosfeiniu.bridge.resolver.ClassView>()
        ApkDex.forEachDex(
            appInfo = appInfo,
            logger = { message -> logInfo(message) },
            action = { bytes -> views += EnhancementDexView.from(bytes) },
        )
        val located = EnhancementLocator.locate(views)
        located.diagnostics.forEach { message -> logInfo("enhancement locator: $message") }
        // Keep only the groups the live class loader confirms; never cache an unvalidated guess.
        val validated = validateEnhancementTargets(located, classLoader) ?: EnhancementTargets(
            temperatureProvider = null,
            pauseConditionCheckers = emptyList(),
            pauseStateInfoClass = null,
            pauseReasonText = null,
            diagnostics = located.diagnostics,
        )
        if (!validated.isEmpty) cache?.write(validated)
        logInfo(
            "enhancement resolver source=dex-scan " +
                "temperature=${validated.temperatureProvider?.className ?: "none"} " +
                "checkers=${validated.pauseConditionCheckers.size} " +
                "stateInfo=${validated.pauseStateInfoClass ?: "none"} " +
                "text=${validated.pauseReasonText?.className ?: "none"}",
        )
        return validated
    }

    /**
     * Drops any located target the running Gallery build cannot actually provide.
     *
     * Returns null when *every* group is unusable, so the caller can keep the raw scan result and its
     * diagnostics for the log.
     */
    private fun validateEnhancementTargets(
        targets: EnhancementTargets,
        classLoader: ClassLoader,
    ): EnhancementTargets? {
        val temperature = targets.temperatureProvider?.takeIf { target ->
            declaresMethod(classLoader, target, requireStatic = true)
        }
        val checkers = targets.pauseConditionCheckers.filter { target ->
            declaresMethod(classLoader, target, requireStatic = false)
        }
        val text = targets.pauseReasonText?.takeIf { target ->
            declaresMethod(classLoader, target, requireStatic = false)
        }
        val stateInfo = targets.pauseStateInfoClass?.takeIf { className ->
            BridgeReflect.findClassOrNull(className, classLoader) != null
        }
        if (
            temperature == null && checkers.isEmpty() && text == null && stateInfo == null
        ) {
            return null
        }
        return EnhancementTargets(
            temperatureProvider = temperature,
            pauseConditionCheckers = checkers,
            pauseStateInfoClass = stateInfo,
            pauseReasonText = text,
            diagnostics = targets.diagnostics,
        )
    }

    private fun declaresMethod(
        classLoader: ClassLoader,
        target: LocatedMethod,
        requireStatic: Boolean,
    ): Boolean {
        val type = BridgeReflect.findClassOrNull(target.className, classLoader) ?: return false
        return type.declaredMethods.any { method ->
            method.name == target.methodName &&
                methodDescriptor(method) == target.descriptor &&
                (!requireStatic || Modifier.isStatic(method.modifiers))
        }
    }

    private fun methodDescriptor(method: Method): String {
        val parameters = method.parameterTypes.joinToString("") { type -> classDescriptor(type) }
        return "($parameters)${classDescriptor(method.returnType)}"
    }

    private fun classDescriptor(type: Class<*>): String = when {
        type == Void.TYPE -> "V"
        type == Boolean::class.javaPrimitiveType -> "Z"
        type == Byte::class.javaPrimitiveType -> "B"
        type == Char::class.javaPrimitiveType -> "C"
        type == Short::class.javaPrimitiveType -> "S"
        type == Int::class.javaPrimitiveType -> "I"
        type == Long::class.javaPrimitiveType -> "J"
        type == Float::class.javaPrimitiveType -> "F"
        type == Double::class.javaPrimitiveType -> "D"
        type.isArray -> "[" + classDescriptor(type.componentType)
        else -> "L" + type.name.replace('.', '/') + ";"
    }

    /**
     * Enhanced resolver fallback for the token decryptor: only reached when the upstream
     * known-name/shape resolver did not confirm a target.
     */
    private fun installEnhancedTokenHooks(
        appInfo: ApplicationInfo,
        hooks: ValidatedTokenHooks,
        source: ResolutionSource,
    ) {
        hooks.prefix.isAccessible = true
        hook(hooks.prefix).intercept { chain -> interceptPrefixCall(chain, appInfo) }
        hooks.decrypt.isAccessible = true
        hook(hooks.decrypt).intercept(TokenDecryptionDiagnosticHook)
        logInfo("prefix fallback installed source=${source.name.lowercase()}")
        logInfo("token decryption diagnostics installed methods=1")
    }

    private fun interceptPrefixCall(chain: Chain, appInfo: ApplicationInfo): Any? {
        val result = runCatching { chain.proceed() }
        val error = result.exceptionOrNull()
        // An Error says the process itself is in trouble; only the loader's own failures fall back.
        if (error is Error) throw error

        val decision = PrefixFallbackDecision.decide(
            returned = result.getOrNull(),
            error = error,
            fallback = { PrefixResolver.resolve(appInfo) { message -> logInfo(message) } },
        )

        return when (decision) {
            is PrefixFallbackDecision.Outcome.Passthrough -> decision.value
            is PrefixFallbackDecision.Outcome.Supply -> {
                logLoaderThrow(error)
                if (shouldLogFallback()) {
                    val prefix = decision.prefix
                    logInfo(
                        "prefix fallback supplied source=${prefix.source} len=${prefix.value.length}",
                    )
                }
                decision.prefix.value
            }
            is PrefixFallbackDecision.Outcome.Rethrow -> {
                logLoaderThrow(decision.error)
                logWarn("prefix fallback unavailable")
                throw decision.error
            }
        }
    }

    private fun logLoaderThrow(error: Throwable?) {
        if (error == null || !shouldLogThrow()) return
        logWarn("prefix loader threw ${error.javaClass.simpleName}: ${error.message}", error)
    }

    private fun installPrivateLanTlsCompatibility(classLoader: ClassLoader) {
        // Kept as a static attempt only: the 16.40.x anchor (`ktc0.k(String)`) no longer carries the
        // trust decision in 17.9.24, and no obfuscation-resistant dynamic anchor could be confirmed.
        // The shape check below keeps this a hard no-op rather than a wrong hook.
        runCatching {
            val tlsSelectorClass = BridgeReflect.findClassOrNull(PRIVATE_LAN_TLS_CLASS, classLoader)
            if (tlsSelectorClass == null) {
                logInfo("private LAN TLS compatibility unavailable")
                return@runCatching
            }
            val methods = tlsSelectorClass.declaredMethods.filter { method ->
                method.name == PRIVATE_LAN_TLS_METHOD &&
                    Modifier.isStatic(method.modifiers) &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.contentEquals(arrayOf(String::class.java))
            }
            methods.forEach { method ->
                method.isAccessible = true
                hook(method).intercept(PrivateLanTlsCompatibilityHook)
            }

            if (methods.isEmpty()) {
                logInfo("private LAN TLS compatibility unavailable")
            } else {
                logInfo("private LAN TLS compatibility installed")
            }
        }.onFailure { error ->
            logInfo(
                "private LAN TLS compatibility install failed: " +
                    "${error.javaClass.simpleName}: ${error.message}",
            )
        }
    }

    private fun installBackupPauseDiagnostics(
        classLoader: ClassLoader,
        targets: EnhancementTargets,
    ) {
        val hooked = hookLocatedTargets(
            classLoader = classLoader,
            targets = targets.pauseConditionCheckers,
            requireStatic = false,
            hooker = BackupPauseReasonHook,
        )
        if (hooked == 0) {
            logInfo("backup pause diagnostics unavailable")
        } else {
            logInfo("backup pause diagnostics installed methods=$hooked")
        }
    }

    private fun installBackupTemperatureCompatibility(
        classLoader: ClassLoader,
        targets: EnhancementTargets,
    ) {
        runCatching {
            CloudBackupTemperaturePolicy.activityLifecycleMethod =
                resolveActivityForegroundMethod(classLoader)

            val conditionHooked = hookLocatedTargets(
                classLoader = classLoader,
                targets = targets.pauseConditionCheckers,
                requireStatic = false,
                hooker = BackupConditionEvaluationScopeHook,
            )
            val temperatureHooked = hookLocatedTargets(
                classLoader = classLoader,
                targets = listOfNotNull(targets.temperatureProvider),
                requireStatic = true,
                hooker = RelaxBackupTemperatureHook,
            )

            if (conditionHooked == 0 || temperatureHooked == 0) {
                logInfo(
                    "backup temperature compatibility unavailable " +
                        "conditionHooks=$conditionHooked temperatureHooks=$temperatureHooked",
                )
            } else {
                logInfo(
                    "backup temperature compatibility installed " +
                        "foregroundMax=${CLOUD_FOREGROUND_MAX_TEMPERATURE_C}C " +
                        "backgroundMax=${CLOUD_BACKGROUND_MAX_TEMPERATURE_C}C " +
                        "retry=${CLOUD_RETRY_TEMPERATURE_C}C",
                )
            }
        }.onFailure { error ->
            logInfo("backup temperature compatibility install failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun installBackupPauseReasonText(
        classLoader: ClassLoader,
        targets: EnhancementTargets,
    ) {
        runCatching {
            val located = hookLocatedTargets(
                classLoader = classLoader,
                targets = listOfNotNull(targets.pauseReasonText),
                requireStatic = false,
                hooker = BackupPauseReasonTextHook,
            )
            val legacy = if (located == 0) hookLegacyPauseReasonText(classLoader) else 0
            val hooked = located + legacy

            if (hooked == 0) {
                logInfo("backup pause reason text unavailable")
            } else {
                logInfo(
                    "backup pause reason text installed methods=$hooked " +
                        "source=${if (located > 0) "dynamic" else "legacy"}",
                )
            }
        }.onFailure { error ->
            logInfo("backup pause reason text install failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /** Static-name fallback kept for the 16.40.x Gallery builds that still expose the old shape. */
    private fun hookLegacyPauseReasonText(classLoader: ClassLoader): Int {
        val methods = runCatching {
            loadExistingClasses(classLoader, NAS_BACKUP_STATE_INFO_CLASSES)
                .flatMap { candidate ->
                    candidate.type.declaredMethods.filter { method ->
                        !Modifier.isStatic(method.modifiers) &&
                            method.returnType == String::class.java &&
                            method.parameterTypes.contentEquals(arrayOf(Context::class.java))
                    }
                }
        }.getOrDefault(emptyList())
        methods.forEach { method ->
            method.isAccessible = true
            runCatching { hook(method).intercept(BackupPauseReasonTextHook) }
        }
        return methods.size
    }

    private fun installMobileDataBackup(
        classLoader: ClassLoader,
        targets: EnhancementTargets,
    ) {
        runCatching {
            val networkMonitor = BridgeReflect.findClassOrNull(NETWORK_MONITOR_CLASS, classLoader)
            MobileDataBackupPolicy.networkMonitorClass = networkMonitor

            val conditionObserverHooks = loadExistingClasses(
                classLoader,
                NAS_BACKUP_CONDITION_OBSERVER_CLASSES,
            ).mapNotNull { candidate ->
                val refreshMethod = candidate.type.declaredMethods.firstOrNull { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.contentEquals(
                            arrayOf(Boolean::class.javaPrimitiveType),
                        )
                }?.apply { isAccessible = true } ?: return@mapNotNull null
                BridgeReflect.allConstructors(candidate.type).forEach { constructor ->
                    hook(constructor).intercept(RememberConditionObserverHook(refreshMethod))
                }
                candidate
            }

            val wlanMethods = networkMonitor?.declaredMethods?.filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.name == WLAN_VALIDATED_METHOD &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.isEmpty()
            }.orEmpty()
            wlanMethods.forEach { method ->
                method.isAccessible = true
                hook(method).intercept(AllowValidatedMobileNetworkHook)
            }

            // The mobile-network evaluation scope is opened by the NAS condition checkers, which the
            // temperature/diagnostics installers already hook; only the legacy builds still need the
            // standalone notification-condition hook.
            val notificationConditionMethods = loadExistingClasses(
                classLoader,
                NAS_NOTIFICATION_CONDITION_CLASSES,
            ).flatMap { candidate ->
                candidate.type.declaredMethods.filter { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.name == NAS_NOTIFICATION_CONDITION_METHOD &&
                        method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
                }
            }
            notificationConditionMethods.forEach { method ->
                method.isAccessible = true
                hook(method).intercept(MobileNetworkEvaluationScopeHook)
            }

            val scopeAvailable = notificationConditionMethods.isNotEmpty() ||
                targets.pauseConditionCheckers.isNotEmpty()
            if (wlanMethods.isEmpty() || !scopeAvailable) {
                logInfo(
                    "mobile data backup compatibility partially unavailable " +
                        "wlanHooks=${wlanMethods.size} scope=$scopeAvailable " +
                        "observerHooks=${conditionObserverHooks.size}",
                )
            } else {
                logInfo(
                    "mobile data backup compatibility installed " +
                        "observerHooks=${conditionObserverHooks.size}",
                )
            }
        }.onFailure { error ->
            logInfo("mobile data backup compatibility install failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    /**
     * Hooks every located method that still matches its recorded name and descriptor on the live
     * class loader. Returns how many methods were hooked.
     */
    private fun hookLocatedTargets(
        classLoader: ClassLoader,
        targets: List<LocatedMethod>,
        requireStatic: Boolean,
        hooker: Hooker,
    ): Int {
        var hooked = 0
        targets.forEach { target ->
            val type = BridgeReflect.findClassOrNull(target.className, classLoader) ?: return@forEach
            type.declaredMethods
                .filter { method ->
                    method.name == target.methodName &&
                        methodDescriptor(method) == target.descriptor &&
                        (!requireStatic || Modifier.isStatic(method.modifiers))
                }
                .forEach { method ->
                    runCatching {
                        method.isAccessible = true
                        hook(method).intercept(hooker)
                        hooked += 1
                    }.onFailure { error ->
                        logWarn(
                            "hook ${target.className}.${target.methodName} failed: " +
                                "${error.javaClass.simpleName}: ${error.message}",
                        )
                    }
                }
        }
        return hooked
    }

    /**
     * The 16.40.x builds exposed the foreground flag on an obfuscated `c50`; 17.9.24 moved it to the
     * un-obfuscated `ActivityLifecycle`. Both are tried, and the method is picked by shape.
     */
    private fun resolveActivityForegroundMethod(classLoader: ClassLoader): Method? {
        ACTIVITY_LIFECYCLE_CLASSES.forEach { className ->
            val type = BridgeReflect.findClassOrNull(className, classLoader) ?: return@forEach
            ACTIVITY_FOREGROUND_METHODS.forEach { methodName ->
                type.declaredMethods
                    .firstOrNull { method ->
                        Modifier.isStatic(method.modifiers) &&
                            method.name == methodName &&
                            method.returnType == Boolean::class.javaPrimitiveType &&
                            method.parameterTypes.isEmpty()
                    }
                    ?.let { method ->
                        method.isAccessible = true
                        return method
                    }
            }
        }
        return null
    }


    private fun installMobileDataPreference(classLoader: ClassLoader) {
        runCatching {
            val settingsFragment = Class.forName(SETTINGS_FRAGMENT_CLASS, false, classLoader)
            BridgeReflect.allMethodsNamed(settingsFragment, SETTINGS_CREATE_PREFERENCES_METHOD)
                .forEach { method ->
                    hook(method).intercept(AddMobileDataPreferenceHook(classLoader))
                }
            logInfo("mobile data backup preference hook installed")
        }.onFailure { error ->
            logInfo("mobile data backup preference hook failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private object PrivateLanTlsCompatibilityHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val original = chain.proceed()
            val address = chain.getArg(0) as? String
            if (
                !PrivateLanTlsPolicy.shouldUseCompatibility(
                    originalResult = original as? Boolean,
                    hasThrowable = false,
                    address = address,
                )
            ) {
                return original
            }

            if (shouldLogActivation()) {
                logInfo("private LAN TLS compatibility activated")
            }
            return true
        }

        private fun shouldLogActivation(): Boolean {
            if (activationLogged) return false
            return synchronized(this) {
                if (activationLogged) {
                    false
                } else {
                    activationLogged = true
                    true
                }
            }
        }

        @Volatile
        private var activationLogged = false
    }

    private object BackupPauseReasonHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result: Any?
            try {
                result = chain.proceed()
            } catch (throwable: Throwable) {
                logState("ERROR:${throwable.javaClass.simpleName}")
                throw throwable
            }

            logState(result?.toString() ?: "NONE")
            return result
        }

        private fun logState(state: String) {
            synchronized(this) {
                if (state == lastState) return
                lastState = state
            }
            logInfo("backup pause state=$state")
        }

        @Volatile
        private var lastState: String? = null
    }

    private object BackupConditionEvaluationScopeHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            backupConditionEvaluationDepth.set((backupConditionEvaluationDepth.get() ?: 0) + 1)
            mobileNetworkEvaluationDepth.set((mobileNetworkEvaluationDepth.get() ?: 0) + 1)
            backupConditionForeground.set(chain.getArg(1) as? Boolean)
            try {
                return chain.proceed()
            } finally {
                val remaining = (backupConditionEvaluationDepth.get() ?: 0) - 1
                if (remaining <= 0) {
                    backupConditionEvaluationDepth.remove()
                    backupConditionForeground.remove()
                } else {
                    backupConditionEvaluationDepth.set(remaining)
                }
                leaveMobileNetworkEvaluationScope()
            }
        }
    }

    private object MobileNetworkEvaluationScopeHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            mobileNetworkEvaluationDepth.set((mobileNetworkEvaluationDepth.get() ?: 0) + 1)
            try {
                return chain.proceed()
            } finally {
                leaveMobileNetworkEvaluationScope()
            }
        }
    }

    private object AllowValidatedMobileNetworkHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if (result == true) return result
            if ((mobileNetworkEvaluationDepth.get() ?: 0) <= 0) return result
            if (!MobileDataBackupPolicy.isEnabled()) return result
            if (!MobileDataBackupPolicy.isValidatedMobileNetwork()) return result

            MobileDataBackupPolicy.logUseOnce()
            return true
        }
    }

    private class RememberConditionObserverHook(
        private val refreshMethod: Method,
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            MobileDataBackupPolicy.rememberConditionObserver(chain.getThisObject(), refreshMethod)
            return result
        }
    }

    private object RelaxBackupTemperatureHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            if ((backupConditionEvaluationDepth.get() ?: 0) <= 0) return result

            val actualTemperature = result as? Float ?: return result
            val decision = CloudBackupTemperaturePolicy.evaluate(actualTemperature)
            val newResult = if (decision.allow && actualTemperature > ORIGINAL_BACKUP_TEMPERATURE_C) {
                ORIGINAL_BACKUP_TEMPERATURE_C
            } else {
                result
            }
            if (CloudBackupTemperaturePolicy.shouldLog(decision)) {
                logInfo(
                    "backup temperature policy actual=${actualTemperature}C " +
                        "foreground=${decision.foreground} max=${decision.maxTemperature}C " +
                        "retry=${CLOUD_RETRY_TEMPERATURE_C}C allow=${decision.allow}",
                )
            }
            return newResult
        }
    }

    private object CloudBackupTemperaturePolicy {
        @Volatile
        var activityLifecycleMethod: Method? = null

        private var blocked = false
        private var lastLoggedState: String? = null

        fun evaluate(actualTemperature: Float): TemperatureDecision {
            val foreground = backupConditionForeground.get() ?: runCatching {
                val method = activityLifecycleMethod
                method?.let { candidate ->
                    candidate.isAccessible = true
                    candidate.invoke(null) as Boolean
                }
            }.getOrDefault(false)
            val maxTemperature = if (foreground) {
                CLOUD_FOREGROUND_MAX_TEMPERATURE_C
            } else {
                CLOUD_BACKGROUND_MAX_TEMPERATURE_C
            }

            synchronized(this) {
                val allow = when {
                    actualTemperature <= CLOUD_RETRY_TEMPERATURE_C -> {
                        blocked = false
                        true
                    }

                    actualTemperature > maxTemperature -> {
                        blocked = true
                        false
                    }

                    else -> !blocked
                }
                return TemperatureDecision(
                    actualTemperature = actualTemperature,
                    foreground = foreground,
                    maxTemperature = maxTemperature,
                    allow = allow,
                )
            }
        }

        fun shouldLog(decision: TemperatureDecision): Boolean {
            val state = "${decision.foreground}:${decision.maxTemperature}:${decision.allow}"
            return synchronized(this) {
                if (state == lastLoggedState) {
                    false
                } else {
                    lastLoggedState = state
                    true
                }
            }
        }
    }

    private data class TemperatureDecision(
        val actualTemperature: Float,
        val foreground: Boolean,
        val maxTemperature: Float,
        val allow: Boolean,
    )

    private object BackupPauseReasonTextHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            return runCatching { enhancedText(chain, result) }.getOrElse { error ->
                logWarn("backup pause reason text skipped: ${error.javaClass.simpleName}: ${error.message}")
                result
            }
        }

        private fun enhancedText(chain: Chain, result: Any?): Any? {
            val context = contextArgument(chain) ?: return result
            MobileDataBackupPolicy.rememberContext(context)

            // Dynamic guard: any backup state that carries a PauseReason is a paused state, whatever
            // the obfuscator called the class on this build.
            val receiver = chain.getThisObject() ?: return result
            val backupState = BridgeReflect.getObjectField(receiver, NAS_BACKUP_STATE_FIELD)
                ?: return result
            val reason = findPauseReason(backupState) ?: return result
            return resolvePauseReasonText(context, reason) ?: result
        }

        /** The located text method takes `(Context)` or `(int, Context)` depending on the build. */
        private fun contextArgument(chain: Chain): Context? {
            for (index in 0 until 2) {
                (chain.getArg(index) as? Context)?.let { return it }
            }
            return null
        }
    }

    private class AddMobileDataPreferenceHook(
        private val classLoader: ClassLoader,
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            return runCatching { addPreference(chain, result) }.getOrElse { error ->
                logWarn(
                    "mobile data backup preference skipped: " +
                        "${error.javaClass.simpleName}: ${error.message}",
                )
                result
            }
        }

        private fun addPreference(chain: Chain, result: Any?): Any? {
            val fragment = chain.getThisObject() ?: return result
            if (!declaresMethodNamed(fragment.javaClass, "findPreference")) {
                logWarnOnce(
                    "mobileDataPreference",
                    "mobile data backup preference unavailable: findPreference missing on " +
                        fragment.javaClass.name,
                )
                return result
            }
            val context = BridgeReflect.callMethod(fragment, "getContext") as? Context ?: return result
            MobileDataBackupPolicy.rememberContext(context)

            val existing = BridgeReflect.callMethod(
                fragment,
                "findPreference",
                MOBILE_DATA_PREFERENCE_KEY,
            )
            if (existing != null) return result

            val nasPreference = BridgeReflect.callMethod(
                fragment,
                "findPreference",
                NAS_BACKUP_PREFERENCE_KEY,
            ) ?: return result
            val visible = BridgeReflect.callMethod(nasPreference, "isVisible") as? Boolean ?: true
            if (!visible) return result

            val category = BridgeReflect.callMethod(
                fragment,
                "findPreference",
                CLOUD_SYNC_CATEGORY_KEY,
            ) ?: return result
            val switchClass = Class.forName(COUI_SWITCH_PREFERENCE_CLASS, false, classLoader)
            val preference = BridgeReflect.newInstance(switchClass, context) ?: return result
            BridgeReflect.callMethod(preference, "setKey", MOBILE_DATA_PREFERENCE_KEY)
            BridgeReflect.callMethod(preference, "setTitle", mobileDataPreferenceTitle(context))
            BridgeReflect.callMethod(preference, "setSummary", mobileDataPreferenceSummary(context))
            BridgeReflect.callMethod(preference, "setPersistent", false)
            val mobileDataEnabled = MobileDataBackupPolicy.isEnabled(context)
            BridgeReflect.callMethod(preference, "setChecked", mobileDataEnabled)
            BridgeReflect.callMethod(
                nasPreference,
                "setSummary",
                nasBackupNetworkSummary(mobileDataEnabled, context),
            )
            val order = BridgeReflect.callMethod(nasPreference, "getOrder") as? Int
                ?: (Int.MAX_VALUE - 2)
            BridgeReflect.callMethod(preference, "setOrder", order + 1)

            val listenerClass = Class.forName(PREFERENCE_CHANGE_LISTENER_CLASS, false, classLoader)
            val listener = Proxy.newProxyInstance(
                classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                when (method.name) {
                    "onPreferenceChange" -> {
                        val enabled = args?.getOrNull(1) as? Boolean ?: false
                        MobileDataBackupPolicy.setEnabled(context, enabled)
                        BridgeReflect.callMethod(
                            nasPreference,
                            "setSummary",
                            nasBackupNetworkSummary(enabled, context),
                        )
                        MobileDataBackupPolicy.requestConditionRefresh()
                        true
                    }

                    "toString" -> "ColorOSFeiniuBridgeMobileDataPreferenceListener"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> false
                    else -> null
                }
            }
            BridgeReflect.callMethod(preference, "setOnPreferenceChangeListener", listener)
            BridgeReflect.callMethod(category, "addPreference", preference)
            logInfo(
                "mobile data backup preference added enabled=" +
                    "${MobileDataBackupPolicy.isEnabled(context)}",
            )
            return result
        }
    }

    private object MobileDataBackupPolicy {
        @Volatile
        var networkMonitorClass: Class<*>? = null

        @Volatile
        private var context: Context? = null

        @Volatile
        private var conditionObserver = WeakReference<Any>(null)

        @Volatile
        private var conditionRefreshMethod: Method? = null

        @Volatile
        private var useLogged = false

        fun rememberContext(value: Context) {
            context = value.applicationContext ?: value
        }

        fun isEnabled(explicitContext: Context? = null): Boolean {
            val resolved = explicitContext?.applicationContext ?: explicitContext ?: context ?: currentApplication()
            if (resolved != null) rememberContext(resolved)
            return resolved?.getSharedPreferences(MODULE_PREFERENCES, Context.MODE_PRIVATE)
                ?.getBoolean(MOBILE_DATA_PREFERENCE_KEY, false)
                ?: false
        }

        fun setEnabled(valueContext: Context, enabled: Boolean) {
            rememberContext(valueContext)
            valueContext.applicationContext
                .getSharedPreferences(MODULE_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(MOBILE_DATA_PREFERENCE_KEY, enabled)
                .apply()
            useLogged = false
            logInfo("mobile data backup preference changed enabled=$enabled")
        }

        fun rememberConditionObserver(value: Any, refreshMethod: Method) {
            conditionObserver = WeakReference(value)
            conditionRefreshMethod = refreshMethod
        }

        fun requestConditionRefresh() {
            val observer = conditionObserver.get()
            val refreshMethod = conditionRefreshMethod
            if (observer == null || refreshMethod == null) {
                logInfo("mobile data backup condition refresh unavailable")
                return
            }
            runCatching {
                refreshMethod.invoke(observer, true)
            }.onSuccess {
                logInfo("mobile data backup condition refresh requested")
            }.onFailure { error ->
                logInfo(
                    "mobile data backup condition refresh failed: " +
                        "${error.javaClass.simpleName}: ${error.message}",
                )
            }
        }

        fun isValidatedMobileNetwork(): Boolean {
            val monitor = networkMonitorClass ?: return false
            return runCatching {
                BridgeReflect.callStaticMethod(monitor, MOBILE_VALIDATED_METHOD) as? Boolean ?: false
            }.getOrDefault(false)
        }

        fun logUseOnce() {
            if (useLogged) return
            synchronized(this) {
                if (useLogged) return
                useLogged = true
            }
            logInfo("validated mobile network accepted for NAS backup")
        }
    }

    private object TokenDecryptionDiagnosticHook : Hooker {
        override fun intercept(chain: Chain): Any? {
            val returned: Any?
            try {
                returned = chain.proceed()
            } catch (throwable: Throwable) {
                logEvent("token decrypt result=error type=${throwable.javaClass.simpleName}", chain)
                throw throwable
            }

            val value = returned as? String
            val event = if (value.isNullOrBlank()) {
                "token decrypt result=empty"
            } else {
                "token decrypt result=success len=${value.length}"
            }
            logEvent(event, chain)
            return returned
        }

        private fun logEvent(event: String, chain: Chain) {
            val eventNumber = synchronized(this) {
                if (loggedEventCount >= MAX_TOKEN_DECRYPT_DIAGNOSTIC_EVENTS) return
                loggedEventCount += 1
                loggedEventCount
            }
            logInfo(
                "$event class=${chain.getExecutable().declaringClass.name} " +
                    "event=$eventNumber/$MAX_TOKEN_DECRYPT_DIAGNOSTIC_EVENTS",
            )
        }

        private var loggedEventCount = 0
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

        /** Visits every DEX image of the installed target APKs; never throws on a bad archive. */
        fun forEachDex(
            appInfo: ApplicationInfo?,
            logger: (String) -> Unit,
            action: (ByteArray) -> Unit,
        ) {
            val sourcePaths = buildList {
                add(appInfo?.sourceDir)
                appInfo?.splitSourceDirs?.let(::addAll)
            }.filterNotNull()

            sourcePaths.forEach { sourcePath ->
                val apk = File(sourcePath)
                if (!apk.isFile) {
                    logger("apk scan skipped: missing ${apk.path}")
                    return@forEach
                }
                runCatching {
                    ZipFile(apk).use { zipFile ->
                        zipFile.entries().asSequence()
                            .filter { entry -> entry.name.endsWith(".dex") }
                            .forEach { entry ->
                                val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                                runCatching { action(bytes) }
                            }
                    }
                }.onFailure { error ->
                    logger(
                        "apk scan failed for ${apk.name}: " +
                            "${error.javaClass.simpleName}: ${error.message}",
                    )
                }
            }
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

    companion object {
        private const val TAG = "ColorOSFeiniuBridge"
        private const val GALLERY_PACKAGE = "com.coloros.gallery3d"
        private const val PRIVATE_LAN_TLS_CLASS = "com.oplus.aiunit.vision.ktc0"
        private const val PRIVATE_LAN_TLS_METHOD = "k"
        private const val MAX_TOKEN_DECRYPT_DIAGNOSTIC_EVENTS = 20
        private const val BACKUP_PAUSE_REASON_CLASS =
            "com.oplus.gallery.framework.abilities.cloudsync.nas.backup.state.PauseReason"
        private val NAS_BACKUP_STATE_INFO_CLASSES = arrayOf(
            "com.oplus.aiunit.vision.stf",
            "com.oplus.aiunit.vision.o3q",
            "com.oplus.aiunit.vision.d4q",
        )
        private const val NAS_BACKUP_STATE_FIELD = "g"
        private const val PAUSE_REASON_FIELD = "a"
        private const val NAS_NOTIFICATION_CONDITION_METHOD = "d"
        private val NAS_NOTIFICATION_CONDITION_CLASSES = arrayOf(
            "com.oplus.aiunit.vision.stf",
            "com.oplus.aiunit.vision.o3q\$a",
            "com.oplus.aiunit.vision.d4q\$a",
        )
        // The temperature provider and the NAS condition checkers are located dynamically; these
        // names only survive for the legacy fallback paths in [hookLegacyPauseReasonText].
        private val ACTIVITY_LIFECYCLE_CLASSES = arrayOf(
            "com.oplus.gallery.foundation.uikit.lifecycle.ActivityLifecycle",
            "com.oplus.aiunit.vision.c50",
        )
        private val ACTIVITY_FOREGROUND_METHODS = arrayOf("c", "b")
        private const val NETWORK_MONITOR_CLASS =
            "com.oplus.gallery.standard_lib.util.network.NetworkMonitor"
        private val NAS_BACKUP_CONDITION_OBSERVER_CLASSES = arrayOf(
            "com.oplus.aiunit.vision.jsf",
            "com.oplus.aiunit.vision.k2q",
            "com.oplus.aiunit.vision.z2q",
        )
        private const val WLAN_VALIDATED_METHOD = "e"
        private const val MOBILE_VALIDATED_METHOD = "c"
        private const val SETTINGS_FRAGMENT_CLASS =
            "com.oplus.gallery.settingpage.SettingsActivity\$SettingFragment"
        private const val SETTINGS_CREATE_PREFERENCES_METHOD = "onCreatePreferences"
        private const val COUI_SWITCH_PREFERENCE_CLASS =
            "com.coui.appcompat.preference.COUISwitchPreference"
        private const val PREFERENCE_CHANGE_LISTENER_CLASS =
            "androidx.preference.Preference\$OnPreferenceChangeListener"
        private const val NAS_BACKUP_PREFERENCE_KEY = "pref_key_nas_auto_backup"
        private const val CLOUD_SYNC_CATEGORY_KEY = "pref_category_key_cloud_sync"
        private const val MOBILE_DATA_PREFERENCE_KEY = "coloros_feiniu_mobile_backup"
        private const val MODULE_PREFERENCES = "coloros_feiniu_bridge"
        private const val ORIGINAL_BACKUP_TEMPERATURE_C = 37.0f
        private const val CLOUD_FOREGROUND_MAX_TEMPERATURE_C = 45.0f
        private const val CLOUD_BACKGROUND_MAX_TEMPERATURE_C = 43.0f
        private const val CLOUD_RETRY_TEMPERATURE_C = 41.0f
        private const val KNOWN_PREFIX = "tRiM@2025#GwToken!sEcReT*kEy&vALu"
        private val PREFIX_REGEX = Regex("""[A-Za-z][A-Za-z0-9@#_!*&$%+?.-]{7,79}GwToken[A-Za-z0-9@#_!*&$%+?.-]{4,80}""")
        private val backupConditionEvaluationDepth = ThreadLocal.withInitial { 0 }
        private val backupConditionForeground = ThreadLocal<Boolean?>()
        private val mobileNetworkEvaluationDepth = ThreadLocal.withInitial { 0 }

        @Volatile
        private var fallbackLogged = false
        private val fallbackLock = Any()

        @Volatile
        private var throwLogged = false
        private val throwLock = Any()

        private const val MAX_REFLECTION_MISS_LOGS = 24
        private val reflectionMissLock = Any()
        private var reflectionMissLogged = 0
        private val warnedOnce = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
        )

        private data class ClassCandidate(
            val name: String,
            val type: Class<*>,
        )

        private fun loadExistingClasses(
            classLoader: ClassLoader,
            classNames: Array<String>,
        ): List<ClassCandidate> {
            return classNames.mapNotNull { className ->
                BridgeReflect.findClassOrNull(className, classLoader)?.let { type ->
                    ClassCandidate(className, type)
                }
            }
        }

        private fun leaveMobileNetworkEvaluationScope() {
            val remaining = (mobileNetworkEvaluationDepth.get() ?: 0) - 1
            if (remaining <= 0) {
                mobileNetworkEvaluationDepth.remove()
            } else {
                mobileNetworkEvaluationDepth.set(remaining)
            }
        }

        private fun currentApplication(): Context? {
            return runCatching {
                val activityThread = Class.forName("android.app.ActivityThread")
                BridgeReflect.callStaticMethod(activityThread, "currentApplication") as? Context
            }.getOrNull()
        }

        private fun findPauseReason(backupState: Any): String? {
            val reasonField = backupState.javaClass.declaredFields.firstOrNull { field ->
                !Modifier.isStatic(field.modifiers) && field.type.name == BACKUP_PAUSE_REASON_CLASS
            }
            if (reasonField != null) {
                return runCatching {
                    reasonField.isAccessible = true
                    reasonField.get(backupState)?.toString()
                }.getOrNull()
            }
            return runCatching {
                BridgeReflect.getObjectField(backupState, PAUSE_REASON_FIELD)?.toString()
            }.getOrNull()
        }

        private fun resolvePauseReasonText(context: Context, reason: String): String? {
            return if (isChinese(context)) {
                when (reason) {
                    "NAS_DISCONNECTED" -> "飞牛私有云未连接，备份暂停"
                    "NAS_STORAGE_FULL" -> "私有云存储空间不足，备份暂停"
                    "NETWORK_PERMISSION_DENIED" -> "相册网络权限未开启，私有云备份暂停"
                    "NO_WLAN" -> "没有可用网络，私有云备份暂停"
                    "HIGH_TEMPERATURE" -> "设备温度较高，私有云备份暂停"
                    "LOW_BATTERY" -> "设备电量不足，私有云备份暂停"
                    "POWER_SAVE_MODE" -> "省电模式已开启，私有云备份暂停"
                    "CLOUD_SYNC_ACTIVE" -> "官方云服务正在同步，私有云备份暂停"
                    "NAS_BATCH_DOWNLOAD_ACTIVE" -> "私有云正在批量下载，备份暂停"
                    "EXTERNAL_APP_FOREGROUND" -> "其他应用正在前台运行，私有云备份暂停"
                    else -> null
                }
            } else {
                when (reason) {
                    "NAS_DISCONNECTED" -> "Private cloud disconnected. Backup paused."
                    "NAS_STORAGE_FULL" -> "Private cloud storage is full. Backup paused."
                    "NETWORK_PERMISSION_DENIED" ->
                        "Gallery network access is disabled. Private cloud backup paused."
                    "NO_WLAN" -> "No usable network. Private cloud backup paused."
                    "HIGH_TEMPERATURE" -> "Device temperature is high. Private cloud backup paused."
                    "LOW_BATTERY" -> "Battery is low. Private cloud backup paused."
                    "POWER_SAVE_MODE" -> "Power saving mode is on. Private cloud backup paused."
                    "CLOUD_SYNC_ACTIVE" ->
                        "Official cloud sync is active. Private cloud backup paused."
                    "NAS_BATCH_DOWNLOAD_ACTIVE" ->
                        "Private cloud batch download is active. Backup paused."
                    "EXTERNAL_APP_FOREGROUND" ->
                        "Another app is in the foreground. Private cloud backup paused."
                    else -> null
                }
            }
        }

        private fun isChinese(context: Context): Boolean {
            val locales = context.resources.configuration.locales
            val locale = if (locales.isEmpty) null else locales[0]
            return locale?.language == "zh" || Locale.getDefault().language == "zh"
        }

        private fun mobileDataPreferenceTitle(context: Context? = null): String {
            val chinese = context?.let(::isChinese) ?: true
            return if (chinese) {
                "允许私有云备份使用移动数据"
            } else {
                "Allow private cloud backup over mobile data"
            }
        }

        private fun mobileDataPreferenceSummary(context: Context? = null): String {
            val chinese = context?.let(::isChinese) ?: true
            return if (chinese) {
                "飞牛私有云自动备份可使用移动网络，可能产生流量费用"
            } else {
                "Feiniu private cloud backup may use mobile data and incur charges"
            }
        }

        private fun nasBackupNetworkSummary(
            mobileDataEnabled: Boolean,
            context: Context? = null,
        ): String {
            val chinese = context?.let(::isChinese) ?: true
            return if (mobileDataEnabled) {
                if (chinese) {
                    "通过 WLAN 或移动数据自动备份到私有云"
                } else {
                    "Automatically back up to private cloud over Wi-Fi or mobile data"
                }
            } else {
                if (chinese) {
                    "仅通过 WLAN 自动备份到私有云"
                } else {
                    "Automatically back up to private cloud over Wi-Fi only"
                }
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

        private fun shouldLogThrow(): Boolean {
            return !throwLogged && synchronized(throwLock) {
                if (throwLogged) {
                    false
                } else {
                    throwLogged = true
                    true
                }
            }
        }

        private fun logInfo(message: String) {
            emitLog(Log.INFO, message, null)
        }

        /**
         * Logs a runtime reflection miss at most [MAX_REFLECTION_MISS_LOGS] times per process, so a
         * hook installed on a method the build no longer has cannot spam the logcat.
         */
        private fun logReflectionMiss(message: String) {
            val count = synchronized(reflectionMissLock) {
                if (reflectionMissLogged >= MAX_REFLECTION_MISS_LOGS) return
                reflectionMissLogged += 1
                reflectionMissLogged
            }
            logWarn("reflection miss: $message (event=$count/$MAX_REFLECTION_MISS_LOGS)")
        }

        /** One-shot log per [key]; used for "this build no longer supports it" diagnostics. */
        private fun logWarnOnce(key: String, message: String) {
            if (!warnedOnce.add(key)) return
            logWarn(message)
        }

        private fun declaresMethodNamed(type: Class<*>, name: String): Boolean =
            BridgeReflect.allMethodsNamed(type, name).isNotEmpty()

        private fun logWarn(message: String, throwable: Throwable? = null) {
            emitLog(Log.WARN, message, throwable)
        }

        private fun logError(message: String, throwable: Throwable? = null) {
            emitLog(Log.ERROR, message, throwable)
        }

        private fun emitLog(priority: Int, message: String, throwable: Throwable?) {
            val sink = logSink
            if (sink != null) {
                sink(priority, TAG, message, throwable)
            } else {
                Log.println(priority, TAG, message)
            }
        }

        private fun installLogSink(sink: (Int, String, String, Throwable?) -> Unit) {
            logSink = sink
        }

        @Volatile
        private var logSink: ((Int, String, String, Throwable?) -> Unit)? = null
    }
}
