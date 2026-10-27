package io.github.colorosfeiniu.bridge

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Bounded debug-build capture of the first refresh. Never logs arguments or exception messages. */
internal object StartupRefreshDiagnostics {
    private val started = AtomicBoolean(false)
    private val events = AtomicInteger()
    @Volatile private var startedAt = 0L
    @Volatile private var logger: (String) -> Unit = {}
    private val pipelineEvents = AtomicInteger()

    fun isActive(): Boolean = BuildConfig.DEBUG && BuildConfig.REFRESH_DIAGNOSTICS &&
        startedAt != 0L && elapsed() <= WINDOW_MS

    fun pipeline(stage: String) {
        if (isActive() && pipelineEvents.incrementAndGet() <= 200) emit("local $stage")
    }

    fun start(log: (String) -> Unit) {
        if (!BuildConfig.DEBUG || !BuildConfig.REFRESH_DIAGNOSTICS || !started.compareAndSet(false, true)) return
        logger = log
        startedAt = SystemClock.elapsedRealtime()
        Thread({
            runCatching {
                emit("capture=start revision=3 windowMs=$WINDOW_MS")
                val previous = mutableMapOf<Long, Pair<String, Long>>()
                repeat(22) {
                    Thread.sleep(4_000)
                    val traces = Thread.getAllStackTraces()
                    traces.entries.asSequence()
                        .filter { (thread, frames) ->
                            thread !== Thread.currentThread() &&
                                (thread.name == "main" ||
                                    (!RefreshThreadPolicy.isIdle(frames) && frames.any { isGalleryFrame(it) }))
                        }
                        .sortedBy { if (it.key.name == "main") -1 else RefreshThreadPolicy.priority(it.key.state, it.value) }
                        .take(24)
                        .forEach { (thread, frames) ->
                            val role = if (thread.name == "main") "main" else "worker"
                            val stack = frames.take(32).joinToString(" <- ") { it.toString() }
                            val signature = "${thread.state} $stack"
                            val last = previous[thread.id]
                            if (last?.first == signature && elapsed() - last.second < 12_000) return@forEach
                            previous[thread.id] = signature to elapsed()
                            // Frame names and line numbers only; thread names may contain user data.
                            emit("stack role=$role thread=${thread.id} state=${thread.state} " +
                                stack)
                        }
                }
                emit("capture=end")
            }
        }, "FeiniuRefreshDiagnostics").apply { isDaemon = true }.start()
    }

    fun <T> measure(stage: String, action: () -> T): T {
        if (!isActive()) return action()
        val before = SystemClock.elapsedRealtime()
        try {
            return action()
        } finally {
            val duration = SystemClock.elapsedRealtime() - before
            if (duration >= 500) {
                emit("slow stage=$stage durationMs=$duration thread=${Thread.currentThread().id}")
            }
        }
    }

    private fun isGalleryFrame(frame: StackTraceElement): Boolean =
        frame.className.startsWith("com.oplus.gallery.") ||
            frame.className.startsWith("com.oplus.aiunit.vision.") ||
            frame.className.startsWith("com.trim.") ||
            frame.className.startsWith("io.github.colorosfeiniu.bridge.")

    private fun elapsed() = SystemClock.elapsedRealtime() - startedAt

    private fun emit(message: String) {
        if (events.incrementAndGet() <= 600) {
            runCatching { logger("refresh-diag t=${elapsed()}ms $message") }
        }
    }

    private const val WINDOW_MS = 90_000L
}
