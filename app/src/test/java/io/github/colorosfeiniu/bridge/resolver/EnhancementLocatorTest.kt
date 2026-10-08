package io.github.colorosfeiniu.bridge.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the enhancement locators against the shapes measured from the reference Gallery 17.9.24 APK,
 * expressed as [ClassView]s so plain JVM tests exercise the anchors without shipping the APK.
 *
 * The measured 17.9.24 shapes (see `scripts/fixtures/gallery-17.9.24/`):
 *
 * * `com.oplus.aiunit.vision.ui90` — `a()F`, loads `debug.gallery.temperature.test` /
 *   `debug.gallery.temperature.level`;
 * * `com.oplus.aiunit.vision.wxr` — `b(ZZ)PauseReason` (and the raw `a(ZZ)...`), loads `NasBackupCondChk`;
 * * `com.oplus.aiunit.vision.u0s` extends `com.oplus.gallery.business_lib.cloudsync.SyncStateInfo`
 *   and its `m(int, Context)` text method reads the paused state: a `PauseReason`-typed field and
 *   the `R.string.nas_backup_paused*` resources.
 */
class EnhancementLocatorTest {

    private fun method(
        name: String,
        descriptor: String,
        isStatic: Boolean = false,
        fieldRefs: List<FieldRefView> = emptyList(),
    ) = MethodView(name, descriptor, isStatic, fieldRefs)

    private fun pauseReasonField() = FieldRefView(
        owner = "com.oplus.aiunit.vision.q0s\$h",
        name = "a",
        type = EnhancementLocator.PAUSE_REASON_DESCRIPTOR,
    )

    private fun pausedStringField(name: String) = FieldRefView(
        owner = EnhancementLocator.NAS_STRING_RESOURCE_OWNER,
        name = name,
        type = "I",
    )

    private val temperatureProvider = ClassView(
        className = "com.oplus.aiunit.vision.ui90",
        superClassName = "java.lang.Object",
        methods = listOf(
            method("a", "()F", isStatic = true),
            method("b", "Lcom/oplus/gallery/foundation/util/systemcore/TemperatureThreshold;"),
        ),
        strings = setOf(
            EnhancementLocator.TEMPERATURE_TEST_FLAG,
            EnhancementLocator.TEMPERATURE_LEVEL_FLAG,
        ),
    )

    private val conditionChecker = ClassView(
        className = "com.oplus.aiunit.vision.wxr",
        superClassName = "java.lang.Object",
        methods = listOf(
            method("a", "(ZZ)${EnhancementLocator.PAUSE_REASON_DESCRIPTOR}"),
            method("b", "(ZZ)${EnhancementLocator.PAUSE_REASON_DESCRIPTOR}"),
        ),
        strings = setOf(EnhancementLocator.CONDITION_CHECKER_TAG),
    )

    private val stateInfo = ClassView(
        className = "com.oplus.aiunit.vision.u0s",
        superClassName = EnhancementLocator.STATE_INFO_SUPERCLASS,
        methods = listOf(
            method(
                "m",
                "(ILandroid/content/Context;)Ljava/lang/String;",
                fieldRefs = listOf(pauseReasonField(), pausedStringField("nas_backup_paused")),
            ),
            method("l", "(Landroid/content/Context;)Ljava/util/List;"),
        ),
        strings = emptySet(),
    )

    private val decoys = listOf(
        ClassView(
            className = "com.oplus.aiunit.vision.decoy",
            superClassName = "java.lang.Object",
            methods = listOf(method("a", "()F", isStatic = true)),
            strings = emptySet(),
        ),
        ClassView(
            className = "com.oplus.gallery.other.OtherStateInfo",
            superClassName = "java.lang.Object",
            methods = listOf(method("m", "(ILandroid/content/Context;)Ljava/lang/String;")),
            strings = emptySet(),
        ),
    )

    @Test
    fun `locates every group from the measured 17 9 24 shapes`() {
        val targets = EnhancementLocator.locate(listOf(temperatureProvider, conditionChecker, stateInfo))

        assertEquals("com.oplus.aiunit.vision.ui90", targets.temperatureProvider?.className)
        assertEquals("a", targets.temperatureProvider?.methodName)
        assertEquals(2, targets.pauseConditionCheckers.size)
        assertEquals("com.oplus.aiunit.vision.wxr", targets.pauseConditionCheckers.first().className)
        assertEquals("com.oplus.aiunit.vision.u0s", targets.pauseStateInfoClass)
        assertEquals("m", targets.pauseReasonText?.methodName)
    }

    @Test
    fun `ignores unrelated classes outside the anchors`() {
        val targets = EnhancementLocator.locate(decoys)

        assertNull(targets.temperatureProvider)
        assertTrue(targets.pauseConditionCheckers.isEmpty())
        assertNull(targets.pauseStateInfoClass)
        assertNull(targets.pauseReasonText)
        assertTrue(targets.isEmpty)
    }

    @Test
    fun `temperature anchor needs both debug flags`() {
        val halfAnchored = temperatureProvider.copy(
            strings = setOf(EnhancementLocator.TEMPERATURE_TEST_FLAG),
        )

        assertNull(EnhancementLocator.locate(listOf(halfAnchored)).temperatureProvider)
    }

    @Test
    fun `temperature anchor rejects a class that loads the flags but declares no float probe`() {
        val noProbe = temperatureProvider.copy(
            methods = listOf(method("a", "()Z", isStatic = true)),
        )

        assertNull(EnhancementLocator.locate(listOf(noProbe)).temperatureProvider)
    }

    @Test
    fun `condition checker anchor needs the log tag and the PauseReason prototype`() {
        val tagOnly = conditionChecker.copy(
            methods = listOf(method("a", "(ZZ)V")),
        )

        assertTrue(EnhancementLocator.locate(listOf(tagOnly)).pauseConditionCheckers.isEmpty())
    }

    @Test
    fun `an ambiguous temperature anchor is dropped instead of guessed`() {
        val twin = temperatureProvider.copy(className = "com.oplus.aiunit.vision.ui91")

        val targets = EnhancementLocator.locate(listOf(temperatureProvider, twin))

        assertNull(targets.temperatureProvider)
        assertTrue(targets.diagnostics.any { it.startsWith("temperature:") })
    }

    @Test
    fun `an ambiguous state info anchor is dropped instead of guessed`() {
        val twin = stateInfo.copy(className = "com.oplus.aiunit.vision.u0t")

        val targets = EnhancementLocator.locate(listOf(stateInfo, twin))

        assertNull(targets.pauseStateInfoClass)
        assertNull(targets.pauseReasonText)
        assertTrue(targets.diagnostics.any { it == "pause reason text: candidates=2" })
    }

    @Test
    fun `a state info subclass without the pause trace is not a candidate`() {
        // The loose "SyncStateInfo subclass with a text method" predicate matches 14 classes in the
        // real 17.9.24 APK; without the field trace the enhancement must stay off.
        val textOnly = stateInfo.copy(
            methods = listOf(method("m", "(ILandroid/content/Context;)Ljava/lang/String;")),
        )

        val targets = EnhancementLocator.locate(listOf(textOnly))

        assertNull(targets.pauseStateInfoClass)
        assertNull(targets.pauseReasonText)
        assertTrue(targets.diagnostics.any { it == "pause reason text: candidates=0" })
    }

    @Test
    fun `a nas_backup_paused resource reference is a sufficient pause trace`() {
        val resourceTraced = stateInfo.copy(
            methods = listOf(
                method(
                    "m",
                    "(Landroid/content/Context;)Ljava/lang/String;",
                    fieldRefs = listOf(pausedStringField("nas_backup_paused_low_battery")),
                ),
            ),
        )

        val targets = EnhancementLocator.locate(listOf(resourceTraced))

        assertEquals("com.oplus.aiunit.vision.u0s", targets.pauseStateInfoClass)
        assertEquals("m", targets.pauseReasonText?.methodName)
    }

    @Test
    fun `state info still resolves when the text method takes an int first`() {
        val intFirst = stateInfo.copy(
            methods = listOf(
                method(
                    "m",
                    "(ILandroid/content/Context;)Ljava/lang/String;",
                    fieldRefs = listOf(pauseReasonField()),
                ),
            ),
        )

        assertEquals("com.oplus.aiunit.vision.u0s", EnhancementLocator.locate(listOf(intFirst)).pauseStateInfoClass)
    }
}
