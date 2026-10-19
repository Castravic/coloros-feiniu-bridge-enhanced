package io.github.colorosfeiniu.bridge

import io.github.colorosfeiniu.bridge.resolver.ClassView
import io.github.colorosfeiniu.bridge.resolver.EnhancementDexView
import io.github.colorosfeiniu.bridge.resolver.EnhancementLocator
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real APK evidence stays optional and local; no proprietary binary is included in CI. */
class Gallery17107DexValidationTest {
    @Test
    fun `validates Gallery 17 10 7 from the supplied apk`() {
        val path = System.getProperty("gallery17107.apk.path")
        assumeTrue("Supply -Dgallery17107.apk.path for Gallery 17.10.7", !path.isNullOrBlank())
        val apk = File(requireNotNull(path))
        assertTrue("Supplied APK does not exist", apk.isFile)
        val views = mutableListOf<ClassView>()
        val tokens = mutableSetOf<String>()
        val methods = mutableMapOf<String, List<DexFile.MethodShape>>()
        val observer = "com.oplus.gallery.framework.abilities.cloudsync.nas.backup.condition.NasBackupConditionObserver"
        val lifecycle = "com.oplus.gallery.foundation.uikit.lifecycle.ActivityLifecycle"
        val network = "com.oplus.gallery.standard_lib.util.network.NetworkMonitor"
        val settings = "com.oplus.gallery.settingpage.SettingsActivity\$SettingFragment"
        val retained = setOf(observer, lifecycle, network, settings,
            "com.coui.appcompat.preference.COUISwitchPreference",
            "com.oplus.aiunit.vision.sg5", "com.oplus.aiunit.vision.std",
            "com.oplus.aiunit.vision.ktc0", "com.oplus.aiunit.vision.j6d0")
        val statClasses = mutableSetOf<String>()
        val productionTokens = mutableSetOf<String>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    views += EnhancementDexView.from(bytes)
                    TokenDecryptorLocator.locate(bytes)?.let(productionTokens::add)
                    val dex = requireNotNull(DexFile.parse(bytes))
                    val tokenTag = dex.indexOfString(TokenDecryptorTargets.LOG_TAG)
                    val statString = dex.firstString { it.startsWith("getGalleryStat failed for device:") }
                    val statIndex = statString?.let(dex::indexOfString) ?: -1
                    dex.classes().forEach { type ->
                        if (type.className in retained) methods[type.className] = type.methodShapes()
                        if (type.referencesString(statIndex)) statClasses += type.className
                        if (type.referencesString(tokenTag) && TokenDecryptorTargets.GALLERY.contracts.any {
                            type.declaresMethod(it.prefixMethod, it.prefixMethodDescriptor) &&
                                type.declaresMethod(it.decryptMethod, it.decryptMethodDescriptor)
                        }) tokens += type.className
                    }
                }
        }
        assertEquals(setOf("com.oplus.aiunit.vision.rab0"), tokens)
        assertEquals(tokens, productionTokens)
        assertTrue("Stat failure anchor must still be loaded by a class", statClasses.isNotEmpty())
        val targets = EnhancementLocator.locate(views)
        assertEquals("com.oplus.aiunit.vision.ps90", targets.temperatureProvider?.className)
        assertEquals(setOf("com.oplus.aiunit.vision.m1s"), targets.pauseConditionCheckers.map { it.className }.toSet())
        assertEquals(setOf("a", "b"), targets.pauseConditionCheckers.map { it.methodName }.toSet())
        assertEquals("com.oplus.aiunit.vision.k4s", targets.pauseStateInfoClass)
        assertEquals("m", targets.pauseReasonText?.methodName)
        assertEquals("(ILandroid/content/Context;)Ljava/lang/String;", targets.pauseReasonText?.descriptor)
        fun requireMethod(type: String, name: String, descriptor: String, isStatic: Boolean) {
            val shapes = requireNotNull(methods[type]) { "Missing class $type" }
            assertEquals("$type.$name", 1, shapes.count {
                it.name == name && it.descriptor == descriptor && it.isStatic == isStatic
            })
        }
        requireMethod(observer, "a", "(Z)V", false)
        assertTrue(methods.getValue(observer).any { it.name == "<init>" })
        requireMethod(lifecycle, "c", "()Z", true)
        requireMethod(network, "c", "()Z", true)
        requireMethod(network, "e", "()Z", true)
        requireMethod("com.oplus.aiunit.vision.std", "a", "(Ljava/lang/String;Z)Lcom/trim/interconnection/internal/grpc/b;", true)
        requireMethod("com.oplus.aiunit.vision.sg5", "a", "()Lio/grpc/ManagedChannel;", false)
        assertTrue(methods.getValue(settings).any { it.name == "onCreatePreferences" })
        assertTrue(methods.containsKey("com.coui.appcompat.preference.COUISwitchPreference"))
        // New TLS routing is handled by Gallery itself; old fixed names must not hook unrelated code.
        for (oldName in listOf("ktc0", "j6d0")) {
            assertFalse(methods["com.oplus.aiunit.vision.$oldName"].orEmpty().any {
                it.name == "k" && it.descriptor == "(Ljava/lang/String;)Z" && it.isStatic
            })
        }
    }
}
