package io.github.colorosfeiniu.bridge.resolver

import io.github.colorosfeiniu.bridge.SyntheticDex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the DEX → [ClassView] projection.
 *
 * The bug fixed here was `EnhancementDexView.from` returning early when a dex loaded none of the
 * anchor strings: the `SyncStateInfo` subclasses live in several dex files (17.9.24: classes2/9/10/17),
 * so every dex but `classes2.dex` was dropped and the pause-reason-text group silently lost 12 of its
 * 14 candidates. These tests build a real (synthetic) DEX image with no anchor string at all and
 * drive both the projection and the locators through the production parser.
 */
class EnhancementDexViewTest {

    private val stateInfoSuper = "Lcom/oplus/gallery/business_lib/cloudsync/SyncStateInfo;"

    private fun pauseTraceField() = SyntheticDex.FieldRef(
        owner = "Lcom/oplus/aiunit/vision/q0s\$h;",
        name = "a",
        type = EnhancementLocator.PAUSE_REASON_DESCRIPTOR,
    )

    private fun stateInfoClass(
        descriptor: String,
        fieldRefs: List<SyntheticDex.FieldRef> = listOf(pauseTraceField()),
    ) = SyntheticDex.Clazz(
        descriptor = descriptor,
        superDescriptor = stateInfoSuper,
        methods = listOf(
            SyntheticDex.Method(
                name = "m",
                proto = SyntheticDex.PROTO_INT_CONTEXT_STRING,
                fieldRefs = fieldRefs,
            ),
        ),
    )

    @Test
    fun `collects a state info subclass from a dex that loads no anchor string`() {
        val dex = SyntheticDex.build(listOf(stateInfoClass("Lcom/oplus/aiunit/vision/u0s;")))

        val views = EnhancementDexView.from(dex)

        assertEquals(listOf("com.oplus.aiunit.vision.u0s"), views.map { it.className })
        assertEquals(EnhancementLocator.STATE_INFO_SUPERCLASS, views.single().superClassName)
        assertTrue("the fixture dex must not load an anchor", views.single().strings.isEmpty())
    }

    @Test
    fun `locates the pause reason text through the production dex parser without anchors`() {
        val dex = SyntheticDex.build(listOf(stateInfoClass("Lcom/oplus/aiunit/vision/u0s;")))

        val targets = EnhancementLocator.locate(EnhancementDexView.from(dex))

        assertEquals("com.oplus.aiunit.vision.u0s", targets.pauseStateInfoClass)
        assertEquals("m", targets.pauseReasonText?.methodName)
        assertEquals(
            "(ILandroid/content/Context;)Ljava/lang/String;",
            targets.pauseReasonText?.descriptor,
        )
    }

    @Test
    fun `an anchor-bearing class in the same dex is still projected`() {
        val dex = SyntheticDex.build(
            listOf(
                SyntheticDex.Clazz(
                    descriptor = "Lcom/oplus/aiunit/vision/wxr;",
                    methods = listOf(
                        SyntheticDex.Method(
                            name = "a",
                            proto = SyntheticDex.PROTO_NO_ARG,
                            constString = EnhancementLocator.CONDITION_CHECKER_TAG,
                        ),
                    ),
                ),
                stateInfoClass("Lcom/oplus/aiunit/vision/u0s;"),
            ),
        )

        val views = EnhancementDexView.from(dex)

        assertEquals(
            setOf("com.oplus.aiunit.vision.wxr", "com.oplus.aiunit.vision.u0s"),
            views.map { it.className }.toSet(),
        )
        assertEquals(setOf("NasBackupCondChk"), views.first { it.className.endsWith("wxr") }.strings)
    }

    @Test
    fun `two pause-traced state info classes are ambiguous and dropped`() {
        val dex = SyntheticDex.build(
            listOf(
                stateInfoClass("Lcom/oplus/aiunit/vision/u0s;"),
                stateInfoClass("Lcom/oplus/aiunit/vision/u0t;"),
            ),
        )

        val targets = EnhancementLocator.locate(EnhancementDexView.from(dex))

        assertNull(targets.pauseStateInfoClass)
        assertNull(targets.pauseReasonText)
        assertTrue(targets.diagnostics.any { it == "pause reason text: candidates=2" })
    }

    @Test
    fun `a state info text method without a pause trace is not located`() {
        val dex = SyntheticDex.build(
            listOf(stateInfoClass("Lcom/oplus/aiunit/vision/hzf;", fieldRefs = emptyList())),
        )

        val targets = EnhancementLocator.locate(EnhancementDexView.from(dex))

        assertNull(targets.pauseStateInfoClass)
        assertNull(targets.pauseReasonText)
    }
}
