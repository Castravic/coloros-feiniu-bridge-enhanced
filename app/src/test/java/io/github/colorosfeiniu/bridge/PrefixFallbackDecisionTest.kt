package io.github.colorosfeiniu.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class PrefixFallbackDecisionTest {

    private val prefix = ResolvedPrefix("tRiM@2025#GwToken!sEcReT*kEy&vALu", "apk-dex")

    @Test
    fun `keeps a usable loader value without resolving a fallback`() {
        var resolutions = 0

        val outcome = decide(returned = "loaded", error = null) {
            resolutions++
            prefix
        }

        assertEquals(PrefixFallbackDecision.Outcome.Passthrough("loaded"), outcome)
        assertEquals(0, resolutions)
    }

    @Test
    fun `supplies the prefix when the loader answers blank`() {
        assertEquals(PrefixFallbackDecision.Outcome.Supply(prefix), decide(returned = "", error = null))
        assertEquals(PrefixFallbackDecision.Outcome.Supply(prefix), decide(returned = "  ", error = null))
        assertEquals(PrefixFallbackDecision.Outcome.Supply(prefix), decide(returned = null, error = null))
    }

    @Test
    fun `supplies the prefix when the loader throws`() {
        val error = IllegalStateException("device id unavailable")

        val outcome = decide(returned = null, error = error)

        assertEquals(PrefixFallbackDecision.Outcome.Supply(prefix), outcome)
    }

    @Test
    fun `keeps a blank loader value when no prefix can be recovered`() {
        val outcome = decide(returned = "", error = null, fallback = { null })

        assertEquals(PrefixFallbackDecision.Outcome.Passthrough(""), outcome)
    }

    @Test
    fun `rethrows the loader failure when no prefix can be recovered`() {
        val error = IllegalStateException("device id unavailable")

        val outcome = decide(returned = null, error = error, fallback = { null })

        assertEquals(PrefixFallbackDecision.Outcome.Rethrow(error), outcome)
    }

    @Test
    fun `resolves the fallback at most once per call`() {
        var resolutions = 0

        decide(returned = "", error = null) {
            resolutions++
            prefix
        }

        assertEquals(1, resolutions)
    }

    private fun decide(
        returned: Any?,
        error: Throwable?,
        fallback: () -> ResolvedPrefix? = { prefix },
    ): PrefixFallbackDecision.Outcome = PrefixFallbackDecision.decide(returned, error, fallback)
}
