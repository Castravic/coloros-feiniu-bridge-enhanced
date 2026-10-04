package io.github.colorosfeiniu.bridge.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the Android-free part of the enhancement cache contract: the preference key layout, the
 * "never write an empty result" rule and the structural re-validation applied before the live
 * class-loader check.
 */
class EnhancementCachePolicyTest {

    private val text = LocatedMethod(
        className = "com.oplus.aiunit.vision.u0s",
        methodName = "m",
        descriptor = "(ILandroid/content/Context;)Ljava/lang/String;",
    )

    private fun targets(
        temperature: LocatedMethod? = null,
        checkers: List<LocatedMethod> = emptyList(),
        stateInfo: String? = null,
        pauseText: LocatedMethod? = null,
    ) = EnhancementTargets(
        temperatureProvider = temperature,
        pauseConditionCheckers = checkers,
        pauseStateInfoClass = stateInfo,
        pauseReasonText = pauseText,
        diagnostics = emptyList(),
    )

    @Test
    fun `the key is prefixed so every enhancement entry is recognisable in the prefs file`() {
        val key = EnhancementCachePolicy.keyFor("abc123")

        assertEquals("enhancement.abc123", key)
        assertTrue(key.startsWith("enhancement."))
    }

    @Test
    fun `an empty result is never written to the cache`() {
        assertFalse(EnhancementCachePolicy.shouldWrite(targets()))
    }

    @Test
    fun `a located group makes the result cacheable`() {
        assertTrue(
            EnhancementCachePolicy.shouldWrite(
                targets(stateInfo = text.className, pauseText = text),
            ),
        )
    }

    @Test
    fun `state info and pause text must come from the same trace`() {
        assertTrue(
            EnhancementCachePolicy.isConsistent(
                targets(stateInfo = text.className, pauseText = text),
            ),
        )
        assertTrue(EnhancementCachePolicy.isConsistent(targets()))
        assertFalse(EnhancementCachePolicy.isConsistent(targets(pauseText = text)))
        assertFalse(EnhancementCachePolicy.isConsistent(targets(stateInfo = text.className)))
        assertFalse(
            EnhancementCachePolicy.isConsistent(
                targets(stateInfo = "com.oplus.aiunit.vision.hzf", pauseText = text),
            ),
        )
    }
}
