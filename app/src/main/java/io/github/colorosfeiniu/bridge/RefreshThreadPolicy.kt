package io.github.colorosfeiniu.bridge

/** Idle executor workers must not consume the bounded diagnostic sample slots. */
internal object RefreshThreadPolicy {
    fun isIdle(frames: Array<StackTraceElement>): Boolean =
        frames.any { it.className == "java.util.concurrent.ThreadPoolExecutor" && it.methodName == "getTask" } ||
            frames.any { it.className == "java.lang.ref.ReferenceQueue" && it.methodName == "remove" }

    fun priority(state: Thread.State, frames: Array<StackTraceElement>): Int = when {
        state == Thread.State.BLOCKED -> 0
        frames.any { it.className.contains("mediadbsync") || it.className.startsWith("android.database.") } -> 1
        state == Thread.State.RUNNABLE -> 2
        else -> 3
    }
}
