package io.github.colorosfeiniu.bridge

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PrefixFallbackSessionTest {
    private val prefix = ResolvedPrefix("recovered-prefix", "test")

    @Test fun `refreshes reuse recovered prefix without retrying loader`() {
        val session = PrefixFallbackSession()
        var calls = 0
        repeat(20) {
            assertEquals(PrefixFallbackDecision.Outcome.Supply(prefix), session.resolve(
                loader = { calls++; null }, fallback = { prefix },
            ))
        }
        assertEquals(1, calls)
    }

    @Test fun `parallel calls attempt failing loader only once`() {
        val session = PrefixFallbackSession()
        val pool = Executors.newFixedThreadPool(8)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        try {
            val tasks = List(32) { Callable {
                session.resolve(loader = { calls.incrementAndGet(); null }, fallback = { prefix })
            } }
            pool.invokeAll(tasks).forEach { assertEquals(PrefixFallbackDecision.Outcome.Supply(prefix), it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun `keeps original success and retries unrecoverable failures`() {
        val session = PrefixFallbackSession()
        assertEquals(PrefixFallbackDecision.Outcome.Passthrough("original"), session.resolve({ "original" }, { error("unused") }))
        val failure = IllegalStateException()
        assertEquals(PrefixFallbackDecision.Outcome.Rethrow(failure), session.resolve({ throw failure }, { null }))
        assertEquals(PrefixFallbackDecision.Outcome.Supply(prefix), session.resolve({ null }, { prefix }))
    }

    @Test fun `different loaders do not share fallback values`() {
        PrefixFallbackSession().resolve({ null }, { prefix })
        assertEquals(PrefixFallbackDecision.Outcome.Passthrough("different"), PrefixFallbackSession().resolve({ "different" }, { prefix }))
    }

    @Test fun `recovered loader exception is cached`() {
        val session = PrefixFallbackSession()
        var calls = 0
        var failures = 0
        repeat(2) {
            assertEquals(PrefixFallbackDecision.Outcome.Supply(prefix), session.resolve(
                loader = { calls++; throw IllegalStateException() },
                fallback = { prefix },
                onLoaderFailure = { failures++ },
            ))
        }
        assertEquals(1, calls)
        assertEquals(1, failures)
    }
    @Test fun `fatal errors propagate without fallback`() {
        val failure = LinkageError()
        try {
            PrefixFallbackSession().resolve({ throw failure }, { error("unused") })
            fail("must throw")
        } catch (actual: LinkageError) { assertSame(failure, actual) }
    }
}
