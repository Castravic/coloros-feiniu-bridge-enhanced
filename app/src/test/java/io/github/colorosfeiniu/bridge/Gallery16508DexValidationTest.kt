package io.github.colorosfeiniu.bridge

import io.github.colorosfeiniu.bridge.resolver.ClassView
import io.github.colorosfeiniu.bridge.resolver.EnhancementDexView
import io.github.colorosfeiniu.bridge.resolver.EnhancementLocator
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional local APK evidence; proprietary APKs are never needed or uploaded by CI. */
class Gallery16508DexValidationTest {
    @Test
    fun `locates 16 50 8 targets from actual dex and validates retained mappings`() {
        val path = System.getProperty("gallery16508.apk.path")
        assumeTrue("Supply -Dgallery16508.apk.path to check Gallery 16.50.8", !path.isNullOrBlank())
        val apk = File(requireNotNull(path))
        assertTrue("Supplied APK does not exist", apk.isFile)
        val views = mutableListOf<ClassView>()
        val tokens = mutableSetOf<String>()
        val mappings = mutableMapOf<String, List<DexFile.MethodShape>>()
        val retainedClasses = setOf("zaq", "j6d0", "k11", "wdq\$a")
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    views += EnhancementDexView.from(bytes)
                    TokenDecryptorLocator.locate(bytes)?.let(tokens::add)
                    // EnhancementDexView deliberately omits unrelated classes; inspect retained
                    // fixed mappings directly through the DEX reader instead.
                    DexFile.parse(bytes)?.classes()?.forEach { type ->
                        val name = type.className.removePrefix("com.oplus.aiunit.vision.")
                        if (name in retainedClasses) mappings[name] = type.methodShapes()
                    }
                }
        }
        assertEquals(setOf("com.oplus.aiunit.vision.y190"), tokens)
        val targets = EnhancementLocator.locate(views)
        assertEquals("com.oplus.aiunit.vision.oh70", targets.temperatureProvider?.className)
        assertEquals(setOf("com.oplus.aiunit.vision.naq"), targets.pauseConditionCheckers.map { it.className }.toSet())
        assertEquals(setOf("a", "b"), targets.pauseConditionCheckers.map { it.methodName }.toSet())
        assertEquals("com.oplus.aiunit.vision.wdq", targets.pauseStateInfoClass)
        assertEquals("m", targets.pauseReasonText?.methodName)
        fun requireMethod(className: String, name: String, descriptor: String, isStatic: Boolean) {
            val methods = requireNotNull(mappings[className]) { "Missing DEX class: $className" }
            assertEquals("$className.$name", 1, methods.count {
                it.name == name && it.descriptor == descriptor && it.isStatic == isStatic
            })
        }
        requireMethod("zaq", "a", "(Z)V", false)
        requireMethod("j6d0", "k", "(Ljava/lang/String;)Z", true)
        requireMethod("k11", "b", "()Z", true)
        requireMethod("wdq\$a", "d", "(Z)Lcom/oplus/gallery/framework/abilities/cloudsync/nas/backup/state/PauseReason;", true)
    }
}
