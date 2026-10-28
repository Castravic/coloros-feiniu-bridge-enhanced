package io.github.colorosfeiniu.bridge

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Observe the local notification path without changing arguments, results or scheduling. */
internal object LocalMediaObserverDiagnostics {
    private val hooked = ConcurrentHashMap.newKeySet<Method>()
    // Gallery 17 reuses ekb$a for every authority. Hooking its class does not identify the source.
    private val observed = Collections.synchronizedMap(WeakHashMap<ContentObserver, String>())

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        if (!BuildConfig.DEBUG || !BuildConfig.REFRESH_DIAGNOSTICS) return
        runCatching {
            ContentResolver::class.java.declaredMethods.filter { method ->
                method.name == "registerContentObserver" &&
                    method.parameterTypes.getOrNull(2) == ContentObserver::class.java
            }.forEach { method ->
                module.hook(method).intercept { chain ->
                    if (StartupRefreshDiagnostics.isActive()) {
                        runCatching {
                            val observer = chain.getArg(2) as? ContentObserver
                            val uri = chain.getArg(0) as? Uri
                            if (observer != null && uri != null) {
                                observe(module, observer, classifySource(uri), "system")
                            }
                        }.onFailure { StartupRefreshDiagnostics.pipeline("observer-hook=unavailable") }
                    }
                    chain.proceed()
                }
            }
            StartupRefreshDiagnostics.pipeline("observer-registration-hook=ready")
        }.onFailure { StartupRefreshDiagnostics.pipeline("observer-registration-hook=unavailable") }
        installDistributor(module, classLoader)
    }

    /** The shared system observer forwards to these real Gallery callbacks, sometimes via a Handler. */
    private fun installDistributor(module: XposedInterface, classLoader: ClassLoader) {
        runCatching {
            val type = Class.forName("com.oplus.aiunit.vision.ekb", false, classLoader)
            val registration = type.declaredMethods.singleOrNull { method ->
                val params = method.parameterTypes
                Modifier.isStatic(method.modifiers) && method.name == "a" &&
                    method.returnType == Void.TYPE && params.size == 4 &&
                    params[0] == Context::class.java && params[1] == Uri::class.java &&
                    params[2] == Boolean::class.javaPrimitiveType &&
                    ContentObserver::class.java.isAssignableFrom(params[3])
            } ?: return@runCatching
            module.hook(registration).intercept { chain ->
                if (StartupRefreshDiagnostics.isActive()) {
                    runCatching {
                        val observer = chain.getArg(3) as? ContentObserver
                        val uri = chain.getArg(1) as? Uri
                        if (observer != null && uri != null) {
                            observe(module, observer, classifySource(uri), "recipient")
                        }
                    }.onFailure { StartupRefreshDiagnostics.pipeline("recipient-hook=unavailable") }
                }
                chain.proceed()
            }
            StartupRefreshDiagnostics.pipeline("recipient-registration-hook=ready")
        }.onFailure { StartupRefreshDiagnostics.pipeline("recipient-registration-hook=unavailable") }
    }

    private fun classifySource(uri: Uri): String {
        val authority = uri.authority?.substringAfterLast('@').orEmpty()
        return when {
            authority == "media" -> "media-store"
            authority.contains("gallery", ignoreCase = true) -> "gallery-db"
            else -> "other"
        }
    }

    private fun observe(module: XposedInterface, observer: ContentObserver, source: String, route: String) {
        val observerClass = observer.javaClass
        if (!observerClass.name.startsWith("com.oplus.")) return
        val previous = observed.put(observer, source)
        if (previous == null) {
            StartupRefreshDiagnostics.pipeline("observer-register source=$source route=$route class=${observerClass.name}")
        }
        var type: Class<*>? = observerClass
        while (type != null && type != ContentObserver::class.java) {
            type.declaredMethods.filter { it.name == "onChange" }.forEach { callback ->
                if (hooked.add(callback)) {
                    try {
                        module.hook(callback).intercept { chain ->
                            val instance = chain.getThisObject() as? ContentObserver
                            val registeredSource = instance?.let { observed[it] }
                            if (instance == null || registeredSource == null) {
                                chain.proceed()
                            } else {
                                val changedUri = if (callback.parameterTypes.getOrNull(1) == Uri::class.java) {
                                    chain.getArg(1) as? Uri
                                } else null
                                val actualSource = changedUri?.let { classifySource(it) } ?: registeredSource
                                StartupRefreshDiagnostics.pipeline("observer-change source=$actualSource registered=$registeredSource callback=${instance.javaClass.name}.${callback.name} self=${chain.getArg(0) as? Boolean}")
                                StartupRefreshDiagnostics.measure("local-observer-$actualSource") { chain.proceed() }
                            }
                        }
                    } catch (error: Throwable) {
                        hooked.remove(callback)
                        throw error
                    }
                }
            }
            type = type.superclass
        }
    }
}
