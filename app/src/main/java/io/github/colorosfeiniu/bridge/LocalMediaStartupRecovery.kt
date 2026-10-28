package io.github.colorosfeiniu.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Restores the first local sync when its listener is added after Gallery entered the foreground. */
internal object LocalMediaStartupRecovery {
    private val installed = AtomicBoolean(false)
    private val states = WeakHashMap<Any, ColdStartSyncReplay>()

    fun install(module: XposedInterface, context: Context, classLoader: ClassLoader, log: (String) -> Unit) {
        if (installed.get()) return
        runCatching {
            @Suppress("DEPRECATION")
            val version = context.packageManager.getPackageInfo("com.coloros.gallery3d", 0).versionName
            val mapping = ColdStartSyncTargets.forVersion(version) ?: return
            val lifecycle = Class.forName(mapping.lifecycle, false, classLoader)
            val foreground = lifecycle.getDeclaredMethod(mapping.foreground).apply {
                require(Modifier.isStatic(modifiers) && returnType == Boolean::class.javaPrimitiveType)
                isAccessible = true
            }
            val listenerType = Class.forName(mapping.listener, false, classLoader)
            val nativeStart = listenerType.getDeclaredMethod("g").apply {
                require(!Modifier.isStatic(modifiers) && returnType == Void.TYPE)
                isAccessible = true
            }
            val registrationType = Class.forName(mapping.registration, false, classLoader)
            val listenerField = registrationType.getDeclaredField("\$listener").apply {
                require(type.isAssignableFrom(listenerType))
                isAccessible = true
            }
            val registration = registrationType.getDeclaredMethod("invokeSuspend", Any::class.java).apply {
                require(!Modifier.isStatic(modifiers) && returnType == Any::class.java)
                isAccessible = true
            }
            if (!installed.compareAndSet(false, true)) return
            module.hook(nativeStart).intercept { chain ->
                chain.getThisObject()?.let { listener -> state(listener).onNativeStart() }
                StartupRefreshDiagnostics.pipeline("native-local-start class=${mapping.listener}")
                chain.proceed()
            }
            val main = Handler(Looper.getMainLooper())
            module.hook(registration).intercept { chain ->
                val result = chain.proceed()
                // Registration can be launched from a worker. Its coroutine completes on Main;
                // post once more so permission/init work on that main-loop turn can finish first.
                runCatching {
                    if (result?.javaClass?.name != "kotlin.Unit") return@runCatching
                    val registrationObject = chain.getThisObject()
                    val listener = registrationObject?.let { listenerField.get(it) }
                    if (listener != null && listenerType.isInstance(listener)) {
                        val weak = WeakReference(listener)
                        main.post {
                            runCatching replay@ {
                                val current = weak.get() ?: return@replay
                                val isForeground = foreground.invoke(null) == true
                                val shouldReplay = state(current).claimReplay(isForeground)
                                log("local startup sync registration foreground=$isForeground replay=$shouldReplay")
                                if (shouldReplay) {
                                    nativeStart.invoke(current)
                                    log("local startup sync replay completed source=native-onStarted")
                                }
                            }.onFailure { log("local startup sync replay failed type=${it.javaClass.simpleName}") }
                        }
                    }
                }.onFailure { log("local startup sync registration diagnostic failed type=${it.javaClass.simpleName}") }
                result
            }
            log("local startup sync recovery installed version=$version")
        }.onFailure { log("local startup sync recovery unavailable type=${it.javaClass.simpleName}") }
    }

    private fun state(listener: Any): ColdStartSyncReplay = synchronized(states) {
        states.getOrPut(listener) { ColdStartSyncReplay() }
    }

}
