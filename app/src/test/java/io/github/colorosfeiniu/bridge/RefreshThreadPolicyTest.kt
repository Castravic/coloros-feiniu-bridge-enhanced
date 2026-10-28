package io.github.colorosfeiniu.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshThreadPolicyTest {
    private fun frame(owner: String, method: String) = StackTraceElement(owner, method, "SourceFile", 1)

    @Test fun `empty executor does not displace local sync worker`() {
        assertTrue(RefreshThreadPolicy.isIdle(arrayOf(
            frame("java.util.concurrent.LinkedBlockingQueue", "take"),
            frame("java.util.concurrent.ThreadPoolExecutor", "getTask"),
            frame("com.oplus.aiunit.vision.v010", "run"),
        )))
        assertFalse(RefreshThreadPolicy.isIdle(arrayOf(
            frame("java.util.concurrent.CountDownLatch", "await"),
            frame("com.oplus.gallery.framework.abilities.mediadbsync.Sync", "run"),
            frame("java.util.concurrent.ThreadPoolExecutor", "runWorker"),
        )))
    }

    @Test fun `reference cleanup is idle but monitor contention has first priority`() {
        assertTrue(RefreshThreadPolicy.isIdle(arrayOf(frame("java.lang.ref.ReferenceQueue", "remove"))))
        assertEquals(0, RefreshThreadPolicy.priority(Thread.State.BLOCKED, emptyArray()))
        assertEquals(1, RefreshThreadPolicy.priority(Thread.State.WAITING,
            arrayOf(frame("com.oplus.gallery.framework.abilities.mediadbsync.Sync", "run"))))
    }
}
