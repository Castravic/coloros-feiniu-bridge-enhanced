package io.github.colorosfeiniu.bridge

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ColdStartSyncDexValidationTest {
    @Test fun `real 16 50 8 local startup callbacks match the trial hooks`() =
        validate("16.50.8", "gallery16508.apk.path")

    @Test fun `real 17 10 7 local startup callbacks match the trial hooks`() =
        validate("17.10.7", "gallery17107.apk.path")

    @Test fun `unknown gallery versions do not use an obfuscated guess`() {
        assertNull(ColdStartSyncTargets.forVersion("17.10.8"))
        assertNull(ColdStartSyncTargets.forVersion(null))
    }

    private fun validate(version: String, property: String) {
        val path = System.getProperty(property)
        assumeTrue("Supply -D$property for real local-sync validation", !path.isNullOrBlank())
        val targets = requireNotNull(ColdStartSyncTargets.forVersion(version))
        val names = setOf(targets.lifecycle, targets.registration, targets.listener)
        val shapes = mutableMapOf<String, List<DexFile.MethodShape>>()
        ZipFile(File(requireNotNull(path))).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.forEach { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                requireNotNull(DexFile.parse(bytes)).classes().filter { it.className in names }.forEach {
                    shapes[it.className] = it.methodShapes()
                }
            }
        }
        assertEquals(names, shapes.keys)
        assertTrue(shapes.getValue(targets.lifecycle).any {
            it.name == targets.foreground && it.descriptor == "()Z" && it.isStatic
        })
        assertTrue(shapes.getValue(targets.listener).any {
            it.name == "g" && it.descriptor == "()V" && !it.isStatic
        })
        val registration = shapes.getValue(targets.registration).single {
            it.name == "invokeSuspend" && it.descriptor == "(Ljava/lang/Object;)Ljava/lang/Object;"
        }
        assertFalse(registration.isStatic)
        assertTrue(registration.fieldRefs.any {
            it.owner == targets.registration && it.name == "\$listener"
        })
    }
}
