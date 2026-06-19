package io.github.colorosfeiniu.bridge

import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.zip.ZipFile

class FeiniuBridgeHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        log("loading for ${lpparam.packageName}/${lpparam.processName}")

        installPrefixFallback(lpparam)
        installBackupPauseDiagnostics(lpparam)
        installBackupTemperatureCompatibility(lpparam)
        installBackupPauseReasonText(lpparam)
        installMobileDataBackup(lpparam)
        installMobileDataPreference(lpparam)
    }

    private fun installPrefixFallback(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val tokenDecryptor = XposedHelpers.findClass(TOKEN_DECRYPTOR_CLASS, lpparam.classLoader)
            XposedBridge.hookAllMethods(tokenDecryptor, PREFIX_METHOD, PrefixFallbackHook(lpparam))
            log("prefix fallback installed")
        }.onFailure { error ->
            log("prefix fallback install failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun installBackupPauseDiagnostics(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val conditionChecker = XposedHelpers.findClass(
                BACKUP_CONDITION_CHECKER_CLASS,
                lpparam.classLoader,
            )
            val pauseReason = XposedHelpers.findClass(
                BACKUP_PAUSE_REASON_CLASS,
                lpparam.classLoader,
            )
            val methods = conditionChecker.declaredMethods.filter { method ->
                method.name == BACKUP_CONDITION_METHOD &&
                    method.returnType == pauseReason &&
                    method.parameterTypes.contentEquals(
                        arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
                    )
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, BackupPauseReasonHook)
            }

            if (methods.isEmpty()) {
                log("backup pause diagnostics unavailable")
            } else {
                log("backup pause diagnostics installed")
            }
        }.onFailure { error ->
            log("backup pause diagnostics install failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun installBackupTemperatureCompatibility(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val conditionChecker = XposedHelpers.findClass(
                BACKUP_CONDITION_CHECKER_CLASS,
                lpparam.classLoader,
            )
            val pauseReason = XposedHelpers.findClass(
                BACKUP_PAUSE_REASON_CLASS,
                lpparam.classLoader,
            )
            val temperatureUtil = XposedHelpers.findClass(
                TEMPERATURE_UTIL_CLASS,
                lpparam.classLoader,
            )
            val activityLifecycle = XposedHelpers.findClass(
                ACTIVITY_LIFECYCLE_CLASS,
                lpparam.classLoader,
            )
            CloudBackupTemperaturePolicy.activityLifecycleClass = activityLifecycle

            val conditionMethods = conditionChecker.declaredMethods.filter { method ->
                method.name == RAW_BACKUP_CONDITION_METHOD &&
                    method.returnType == pauseReason &&
                    method.parameterTypes.contentEquals(
                        arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
                    )
            }
            val temperatureMethods = temperatureUtil.declaredMethods.filter { method ->
                method.name == TEMPERATURE_METHOD &&
                    method.returnType == Float::class.javaPrimitiveType &&
                    method.parameterTypes.isEmpty()
            }

            conditionMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, BackupConditionEvaluationScopeHook)
            }
            temperatureMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, RelaxBackupTemperatureHook)
            }

            if (conditionMethods.isEmpty() || temperatureMethods.isEmpty()) {
                log("backup temperature compatibility unavailable")
            } else {
                log(
                    "backup temperature compatibility installed " +
                        "foregroundMax=${CLOUD_FOREGROUND_MAX_TEMPERATURE_C}C " +
                        "backgroundMax=${CLOUD_BACKGROUND_MAX_TEMPERATURE_C}C " +
                        "retry=${CLOUD_RETRY_TEMPERATURE_C}C",
                )
            }
        }.onFailure { error ->
            log("backup temperature compatibility install failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun installBackupPauseReasonText(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val stateInfoClass = XposedHelpers.findClass(NAS_BACKUP_STATE_INFO_CLASS, lpparam.classLoader)
            val methods = stateInfoClass.declaredMethods.filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.returnType == String::class.java &&
                    method.parameterTypes.contentEquals(arrayOf(Context::class.java))
            }
            methods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, BackupPauseReasonTextHook)
            }

            if (methods.isEmpty()) {
                log("backup pause reason text unavailable")
            } else {
                log("backup pause reason text installed")
            }
        }.onFailure { error ->
            log("backup pause reason text install failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun installMobileDataBackup(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val networkMonitor = XposedHelpers.findClass(NETWORK_MONITOR_CLASS, lpparam.classLoader)
            MobileDataBackupPolicy.networkMonitorClass = networkMonitor
            val conditionObserver = XposedHelpers.findClass(
                NAS_BACKUP_CONDITION_OBSERVER_CLASS,
                lpparam.classLoader,
            )
            MobileDataBackupPolicy.conditionRefreshMethod =
                conditionObserver.declaredMethods.firstOrNull { method ->
                    !Modifier.isStatic(method.modifiers) &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.contentEquals(
                            arrayOf(Boolean::class.javaPrimitiveType),
                        )
                }?.apply { isAccessible = true }
            XposedBridge.hookAllConstructors(
                conditionObserver,
                RememberConditionObserverHook,
            )

            val wlanMethods = networkMonitor.declaredMethods.filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.name == WLAN_VALIDATED_METHOD &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.isEmpty()
            }
            wlanMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, AllowValidatedMobileNetworkHook)
            }

            val stateInfoClass = XposedHelpers.findClass(NAS_BACKUP_STATE_INFO_CLASS, lpparam.classLoader)
            val notificationConditionMethods = stateInfoClass.declaredMethods.filter { method ->
                Modifier.isStatic(method.modifiers) &&
                    method.name == NAS_NOTIFICATION_CONDITION_METHOD &&
                    method.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            }
            notificationConditionMethods.forEach { method ->
                method.isAccessible = true
                XposedBridge.hookMethod(method, MobileNetworkEvaluationScopeHook)
            }

            if (wlanMethods.isEmpty() || notificationConditionMethods.isEmpty()) {
                log("mobile data backup compatibility partially unavailable")
            } else {
                log("mobile data backup compatibility installed")
            }
        }.onFailure { error ->
            log("mobile data backup compatibility install failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun installMobileDataPreference(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val settingsFragment = XposedHelpers.findClass(SETTINGS_FRAGMENT_CLASS, lpparam.classLoader)
            XposedBridge.hookAllMethods(
                settingsFragment,
                SETTINGS_CREATE_PREFERENCES_METHOD,
                AddMobileDataPreferenceHook(lpparam.classLoader),
            )
            log("mobile data backup preference hook installed")
        }.onFailure { error ->
            log("mobile data backup preference hook failed: ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private object BackupPauseReasonHook : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val state = if (param.hasThrowable()) {
                "ERROR:${param.throwable.javaClass.simpleName}"
            } else {
                param.result?.toString() ?: "NONE"
            }

            synchronized(BackupPauseReasonHook::class.java) {
                if (state == lastState) return
                lastState = state
            }
            log("backup pause state=$state")
        }

        @Volatile
        private var lastState: String? = null
    }

    private object BackupConditionEvaluationScopeHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            backupConditionEvaluationDepth.set((backupConditionEvaluationDepth.get() ?: 0) + 1)
            mobileNetworkEvaluationDepth.set((mobileNetworkEvaluationDepth.get() ?: 0) + 1)
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            val remaining = (backupConditionEvaluationDepth.get() ?: 0) - 1
            if (remaining <= 0) {
                backupConditionEvaluationDepth.remove()
            } else {
                backupConditionEvaluationDepth.set(remaining)
            }
            leaveMobileNetworkEvaluationScope()
        }
    }

    private object MobileNetworkEvaluationScopeHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            mobileNetworkEvaluationDepth.set((mobileNetworkEvaluationDepth.get() ?: 0) + 1)
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            leaveMobileNetworkEvaluationScope()
        }
    }

    private object AllowValidatedMobileNetworkHook : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.hasThrowable() || param.result == true) return
            if ((mobileNetworkEvaluationDepth.get() ?: 0) <= 0) return
            if (!MobileDataBackupPolicy.isEnabled()) return
            if (!MobileDataBackupPolicy.isValidatedMobileNetwork()) return

            param.result = true
            MobileDataBackupPolicy.logUseOnce()
        }
    }

    private object RememberConditionObserverHook : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            MobileDataBackupPolicy.rememberConditionObserver(param.thisObject)
        }
    }

    private object RelaxBackupTemperatureHook : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.hasThrowable() || (backupConditionEvaluationDepth.get() ?: 0) <= 0) return

            val actualTemperature = param.result as? Float ?: return
            val decision = CloudBackupTemperaturePolicy.evaluate(actualTemperature)
            if (decision.allow && actualTemperature > ORIGINAL_BACKUP_TEMPERATURE_C) {
                param.result = ORIGINAL_BACKUP_TEMPERATURE_C
            }
            if (CloudBackupTemperaturePolicy.shouldLog(decision)) {
                log(
                    "backup temperature policy actual=${actualTemperature}C " +
                        "foreground=${decision.foreground} max=${decision.maxTemperature}C " +
                        "retry=${CLOUD_RETRY_TEMPERATURE_C}C allow=${decision.allow}",
                )
            }
        }
    }

    private object CloudBackupTemperaturePolicy {
        @Volatile
        var activityLifecycleClass: Class<*>? = null

        private var blocked = false
        private var lastForeground: Boolean? = null
        private var lastLoggedState: String? = null

        fun evaluate(actualTemperature: Float): TemperatureDecision {
            val foreground = runCatching {
                XposedHelpers.callStaticMethod(
                    activityLifecycleClass,
                    ACTIVITY_FOREGROUND_METHOD,
                ) as Boolean
            }.getOrDefault(false)
            val maxTemperature = if (foreground) {
                CLOUD_FOREGROUND_MAX_TEMPERATURE_C
            } else {
                CLOUD_BACKGROUND_MAX_TEMPERATURE_C
            }

            synchronized(this) {
                if (lastForeground != foreground) {
                    blocked = false
                    lastForeground = foreground
                }

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

    private object BackupPauseReasonTextHook : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.hasThrowable()) return
            val context = param.args.firstOrNull() as? Context ?: return
            MobileDataBackupPolicy.rememberContext(context)

            val backupState = runCatching {
                XposedHelpers.getObjectField(param.thisObject, NAS_BACKUP_STATE_FIELD)
            }.getOrNull() ?: return
            if (backupState.javaClass.name != NAS_PAUSED_STATE_CLASS) return

            val reason = runCatching {
                XposedHelpers.getObjectField(backupState, PAUSE_REASON_FIELD)?.toString()
            }.getOrNull() ?: return
            val text = resolvePauseReasonText(context, reason) ?: return
            param.result = text
        }
    }

    private class AddMobileDataPreferenceHook(
        private val classLoader: ClassLoader,
    ) : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            val fragment = param.thisObject ?: return
            val context = XposedHelpers.callMethod(fragment, "getContext") as? Context ?: return
            MobileDataBackupPolicy.rememberContext(context)

            val existing = XposedHelpers.callMethod(
                fragment,
                "findPreference",
                MOBILE_DATA_PREFERENCE_KEY,
            )
            if (existing != null) return

            val nasPreference = XposedHelpers.callMethod(
                fragment,
                "findPreference",
                NAS_BACKUP_PREFERENCE_KEY,
            ) ?: return
            val visible = runCatching {
                XposedHelpers.callMethod(nasPreference, "isVisible") as Boolean
            }.getOrDefault(true)
            if (!visible) return

            val category = XposedHelpers.callMethod(
                fragment,
                "findPreference",
                CLOUD_SYNC_CATEGORY_KEY,
            ) ?: return
            val switchClass = XposedHelpers.findClass(COUI_SWITCH_PREFERENCE_CLASS, classLoader)
            val preference = XposedHelpers.newInstance(switchClass, context)
            XposedHelpers.callMethod(preference, "setKey", MOBILE_DATA_PREFERENCE_KEY)
            XposedHelpers.callMethod(preference, "setTitle", mobileDataPreferenceTitle(context))
            XposedHelpers.callMethod(preference, "setSummary", mobileDataPreferenceSummary(context))
            XposedHelpers.callMethod(preference, "setPersistent", false)
            val mobileDataEnabled = MobileDataBackupPolicy.isEnabled(context)
            XposedHelpers.callMethod(preference, "setChecked", mobileDataEnabled)
            XposedHelpers.callMethod(
                nasPreference,
                "setSummary",
                nasBackupNetworkSummary(mobileDataEnabled, context),
            )
            val order = runCatching {
                XposedHelpers.callMethod(nasPreference, "getOrder") as Int
            }.getOrDefault(Int.MAX_VALUE - 2)
            XposedHelpers.callMethod(preference, "setOrder", order + 1)

            val listenerClass = XposedHelpers.findClass(PREFERENCE_CHANGE_LISTENER_CLASS, classLoader)
            val listener = Proxy.newProxyInstance(
                classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                when (method.name) {
                    "onPreferenceChange" -> {
                        val enabled = args?.getOrNull(1) as? Boolean ?: false
                        MobileDataBackupPolicy.setEnabled(context, enabled)
                        XposedHelpers.callMethod(
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
            XposedHelpers.callMethod(preference, "setOnPreferenceChangeListener", listener)
            XposedHelpers.callMethod(category, "addPreference", preference)
            log("mobile data backup preference added enabled=${MobileDataBackupPolicy.isEnabled(context)}")
        }
    }

    private object MobileDataBackupPolicy {
        @Volatile
        var networkMonitorClass: Class<*>? = null

        @Volatile
        var conditionRefreshMethod: Method? = null

        @Volatile
        private var context: Context? = null

        @Volatile
        private var conditionObserver = WeakReference<Any>(null)

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
            log("mobile data backup preference changed enabled=$enabled")
        }

        fun rememberConditionObserver(value: Any) {
            conditionObserver = WeakReference(value)
        }

        fun requestConditionRefresh() {
            val observer = conditionObserver.get()
            val refreshMethod = conditionRefreshMethod
            if (observer == null || refreshMethod == null) {
                log("mobile data backup condition refresh unavailable")
                return
            }
            runCatching {
                refreshMethod.invoke(observer, true)
            }.onSuccess {
                log("mobile data backup condition refresh requested")
            }.onFailure { error ->
                log(
                    "mobile data backup condition refresh failed: " +
                        "${error.javaClass.simpleName}: ${error.message}",
                )
            }
        }

        fun isValidatedMobileNetwork(): Boolean {
            val monitor = networkMonitorClass ?: return false
            return runCatching {
                XposedHelpers.callStaticMethod(monitor, MOBILE_VALIDATED_METHOD) as Boolean
            }.getOrDefault(false)
        }

        fun logUseOnce() {
            if (useLogged) return
            synchronized(this) {
                if (useLogged) return
                useLogged = true
            }
            log("validated mobile network accepted for NAS backup")
        }
    }

    private class PrefixFallbackHook(
        private val lpparam: XC_LoadPackage.LoadPackageParam,
    ) : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            if (param.hasThrowable()) return
            if (!param.result.isNullOrBlankString()) return

            val resolved = PrefixResolver.resolve(lpparam)
            if (resolved == null) {
                log("prefix fallback unavailable")
                return
            }

            param.result = resolved.value
            if (shouldLogFallback()) {
                log("prefix fallback supplied source=${resolved.source} len=${resolved.value.length}")
            }
        }

        private fun shouldLogFallback(): Boolean {
            return !fallbackLogged && synchronized(PrefixFallbackHook::class.java) {
                if (fallbackLogged) {
                    false
                } else {
                    fallbackLogged = true
                    true
                }
            }
        }

        companion object {
            @Volatile
            private var fallbackLogged = false
        }
    }

    private object PrefixResolver {
        @Volatile
        private var cachedPrefix: ResolvedPrefix? = null

        fun resolve(lpparam: XC_LoadPackage.LoadPackageParam): ResolvedPrefix? {
            cachedPrefix?.let { return it }

            val resolved = findFromApkStrings(lpparam)
                ?: ResolvedPrefix(KNOWN_PREFIX, "builtin")

            cachedPrefix = resolved
            return resolved
        }

        private fun findFromApkStrings(lpparam: XC_LoadPackage.LoadPackageParam): ResolvedPrefix? {
            val sourcePaths = buildList {
                add(lpparam.appInfo?.sourceDir)
                lpparam.appInfo?.splitSourceDirs?.let(::addAll)
            }.filterNotNull()

            if (sourcePaths.isEmpty()) {
                log("apk scan skipped: no source paths")
                return null
            }

            for (sourcePath in sourcePaths) {
                val prefix = findFromZip(File(sourcePath))
                if (!prefix.isNullOrBlank()) return ResolvedPrefix(prefix, "apk-dex")
            }
            return null
        }

        private fun findFromZip(apk: File): String? {
            if (!apk.isFile) {
                log("apk scan skipped: missing ${apk.path}")
                return null
            }

            return try {
                ZipFile(apk).use { zipFile ->
                    val dexEntries = zipFile.entries().asSequence()
                        .filter { it.name.endsWith(".dex") }
                        .toList()

                    if (dexEntries.isEmpty()) {
                        log("apk scan skipped: no dex entries in ${apk.name}")
                        return null
                    }

                    for (entry in dexEntries) {
                        val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                        val prefix = findFromDexStrings(bytes, entry.name)
                        if (!prefix.isNullOrBlank()) return prefix
                    }
                }
                log("apk scan did not find Feiniu prefix in ${apk.name}")
                null
            } catch (error: Throwable) {
                log("apk scan failed for ${apk.name}: ${error.javaClass.simpleName}: ${error.message}")
                null
            }
        }

        private fun findFromDexStrings(dex: ByteArray, entryName: String): String? {
            if (dex.size < DEX_HEADER_SIZE) {
                log("dex scan skipped: $entryName is too small")
                return null
            }
            if (!dex.startsWithDexMagic()) {
                log("dex scan skipped: $entryName is not standard dex")
                return null
            }

            val stringIdsSize = dex.readUIntLe(DEX_STRING_IDS_SIZE_OFFSET)
            val stringIdsOffset = dex.readUIntLe(DEX_STRING_IDS_OFFSET_OFFSET)
            if (stringIdsSize <= 0 || stringIdsOffset <= 0) {
                log("dex scan skipped: $entryName has invalid string table")
                return null
            }

            for (index in 0 until stringIdsSize) {
                val stringIdOffset = stringIdsOffset + index * DEX_STRING_ID_SIZE
                if (stringIdOffset + DEX_STRING_ID_SIZE > dex.size) {
                    log("dex scan stopped: $entryName string id table out of bounds")
                    return null
                }

                val stringDataOffset = dex.readUIntLe(stringIdOffset)
                val stringValue = dex.readDexString(stringDataOffset) ?: continue
                if (stringValue.isFeiniuPrefix()) return stringValue
            }

            return null
        }

        private fun ByteArray.startsWithDexMagic(): Boolean {
            return size >= 4 && this[0] == 'd'.code.toByte() && this[1] == 'e'.code.toByte() &&
                this[2] == 'x'.code.toByte() && this[3] == '\n'.code.toByte()
        }

        private fun ByteArray.readUIntLe(offset: Int): Int {
            if (offset < 0 || offset + 4 > size) return -1
            return (this[offset].toInt() and 0xff) or
                ((this[offset + 1].toInt() and 0xff) shl 8) or
                ((this[offset + 2].toInt() and 0xff) shl 16) or
                ((this[offset + 3].toInt() and 0xff) shl 24)
        }

        private fun ByteArray.readDexString(offset: Int): String? {
            if (offset < 0 || offset >= size) return null

            var cursor = offset
            while (cursor < size) {
                val value = this[cursor].toInt() and 0xff
                cursor++
                if ((value and 0x80) == 0) break
            }
            if (cursor >= size) return null

            val start = cursor
            while (cursor < size && this[cursor].toInt() != 0) cursor++
            if (cursor >= size || cursor == start) return null

            return runCatching { String(this, start, cursor - start, Charsets.UTF_8) }.getOrNull()
        }

        private fun String.isFeiniuPrefix(): Boolean {
            return length in 16..80 && PREFIX_REGEX.matches(this)
        }

    }

    private data class ResolvedPrefix(
        val value: String,
        val source: String,
    )

    companion object {
        private const val TARGET_PACKAGE = "com.coloros.gallery3d"
        private const val TOKEN_DECRYPTOR_CLASS = "com.oplus.aiunit.vision.erq"
        private const val PREFIX_METHOD = "e"
        private const val BACKUP_CONDITION_CHECKER_CLASS = "com.oplus.aiunit.vision.bsf"
        private const val BACKUP_PAUSE_REASON_CLASS =
            "com.oplus.gallery.framework.abilities.cloudsync.nas.backup.state.PauseReason"
        private const val BACKUP_CONDITION_METHOD = "b"
        private const val RAW_BACKUP_CONDITION_METHOD = "a"
        private const val NAS_BACKUP_STATE_INFO_CLASS = "com.oplus.aiunit.vision.stf"
        private const val NAS_BACKUP_STATE_FIELD = "g"
        private const val NAS_PAUSED_STATE_CLASS = "com.oplus.aiunit.vision.otf\$h"
        private const val PAUSE_REASON_FIELD = "a"
        private const val NAS_NOTIFICATION_CONDITION_METHOD = "d"
        private const val TEMPERATURE_UTIL_CLASS = "com.oplus.aiunit.vision.vwp"
        private const val TEMPERATURE_METHOD = "a"
        private const val ACTIVITY_LIFECYCLE_CLASS = "com.oplus.aiunit.vision.c50"
        private const val ACTIVITY_FOREGROUND_METHOD = "b"
        private const val NETWORK_MONITOR_CLASS =
            "com.oplus.gallery.standard_lib.util.network.NetworkMonitor"
        private const val NAS_BACKUP_CONDITION_OBSERVER_CLASS = "com.oplus.aiunit.vision.jsf"
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
        private const val DEX_HEADER_SIZE = 0x70
        private const val DEX_STRING_IDS_SIZE_OFFSET = 0x38
        private const val DEX_STRING_IDS_OFFSET_OFFSET = 0x3c
        private const val DEX_STRING_ID_SIZE = 4
        private val PREFIX_REGEX = Regex("""[A-Za-z][A-Za-z0-9@#_!*&$%+?.-]{7,79}GwToken[A-Za-z0-9@#_!*&$%+?.-]{4,80}""")
        private val backupConditionEvaluationDepth = ThreadLocal.withInitial { 0 }
        private val mobileNetworkEvaluationDepth = ThreadLocal.withInitial { 0 }

        private fun Any?.isNullOrBlankString(): Boolean {
            return (this as? String).isNullOrBlank()
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
                val activityThread = XposedHelpers.findClass("android.app.ActivityThread", null)
                XposedHelpers.callStaticMethod(activityThread, "currentApplication") as? Context
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

        private fun log(message: String) {
            XposedBridge.log("ColorOSFeiniuBridge: $message")
        }
    }
}
