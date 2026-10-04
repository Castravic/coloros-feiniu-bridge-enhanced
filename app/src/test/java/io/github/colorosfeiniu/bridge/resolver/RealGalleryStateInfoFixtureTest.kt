package io.github.colorosfeiniu.bridge.resolver

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Runs the production locator over the *full* `SyncStateInfo` candidate set measured from the real
 * Gallery 17.9.24 APK.
 *
 * The fixture (`gallery-17.9.24-stateinfo-candidates.txt`) is a DEX-derived projection written by
 * `.analysis/replay_pause_predicate.py --write`: all 15 direct `SyncStateInfo` subclasses, all of
 * their declared methods and every field access those methods make. The proprietary APK stays out of
 * the repository, but this keeps the "predicate over the real domain" check running in CI.
 *
 * It exists because the phase-2 ledger claimed that "a `SyncStateInfo` subclass with a text method"
 * resolves to `u0s` only; over the full domain that loose predicate actually matches 14 classes /
 * 17 methods, which is why the pause trace is now part of the predicate.
 */
class RealGalleryStateInfoFixtureTest {

    private fun contextToText(descriptor: String): Boolean =
        descriptor == "(Landroid/content/Context;)Ljava/lang/String;" ||
            descriptor == "(ILandroid/content/Context;)Ljava/lang/String;"

    @Test
    fun `the full real candidate set resolves to u0s and its text method only`() {
        val views = loadFixture()

        assertEquals("all direct SyncStateInfo subclasses", 15, views.size)
        assertEquals(
            "the loose predicate is genuinely ambiguous over the full domain",
            17,
            views.flatMap { clazz -> clazz.methods }
                .count { method -> !method.isStatic && contextToText(method.descriptor) },
        )

        val targets = EnhancementLocator.locate(views)

        assertEquals("com.oplus.aiunit.vision.u0s", targets.pauseStateInfoClass)
        assertEquals("m", targets.pauseReasonText?.methodName)
        assertEquals(
            "(ILandroid/content/Context;)Ljava/lang/String;",
            targets.pauseReasonText?.descriptor,
        )
    }

    private fun loadFixture(): List<ClassView> {
        val stream = javaClass.getResourceAsStream(FIXTURE) ?: error("missing fixture $FIXTURE")
        val classes = mutableListOf<ClassView>()
        var className: String? = null
        var superName: String? = null
        var methods = mutableListOf<MethodBuilder>()

        fun flush() {
            val name = className ?: return
            classes += ClassView(
                className = name,
                superClassName = superName,
                methods = methods.map { it.build() },
                strings = emptySet(),
            )
            methods = mutableListOf()
        }

        stream.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val parts = line.split(" ")
                when (parts[0]) {
                    "C" -> {
                        flush()
                        className = parts[1]
                        superName = parts[2]
                    }

                    "M" -> methods += MethodBuilder(parts[1], parts[2], parts[3] == "static")

                    "F" -> methods.last().fields += FieldRefView(parts[1], parts[2], parts[3])
                }
            }
        }
        flush()
        return classes
    }

    private class MethodBuilder(
        val name: String,
        val descriptor: String,
        val isStatic: Boolean,
    ) {
        val fields = mutableListOf<FieldRefView>()

        fun build() = MethodView(name, descriptor, isStatic, fields.toList())
    }

    private companion object {
        const val FIXTURE = "/gallery-17.9.24-stateinfo-candidates.txt"
    }
}
