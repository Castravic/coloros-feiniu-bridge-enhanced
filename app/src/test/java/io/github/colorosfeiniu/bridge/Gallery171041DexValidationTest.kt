package io.github.colorosfeiniu.bridge

import io.github.colorosfeiniu.bridge.resolver.ClassView
import io.github.colorosfeiniu.bridge.resolver.EnhancementDexView
import io.github.colorosfeiniu.bridge.resolver.EnhancementLocator
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional local APK validation for Gallery 17.10.41; no proprietary binary is included in CI. */
class Gallery171041DexValidationTest {
    @Test
    fun `validates Gallery 17 10 41 from the supplied apk`() {
        val path = System.getProperty("gallery171041.apk.path")
        assumeTrue("Supply -Dgallery171041.apk.path for Gallery 17.10.41", !path.isNullOrBlank())
        val apk = File(requireNotNull(path))
        assertTrue("Supplied APK does not exist", apk.isFile)

        val views = mutableListOf<ClassView>()
        val productionTokens = mutableSetOf<String>()
        val prefixes = mutableSetOf<String>()
        val coldStartScan = ColdStartSyncLocator.ScanState()
        val methods = mutableMapOf<String, List<DexFile.MethodShape>>()
        val statClasses = mutableSetOf<String>()

        val observer = "com.oplus.gallery.framework.abilities.cloudsync.nas.backup.condition.NasBackupConditionObserver"
        val lifecycle = "com.oplus.gallery.foundation.uikit.lifecycle.ActivityLifecycle"
        val network = "com.oplus.gallery.standard_lib.util.network.NetworkMonitor"
        val settings = "com.oplus.gallery.settingpage.SettingsActivity\$SettingFragment"
        val retained = setOf(
            observer,
            lifecycle,
            network,
            settings,
            "com.coui.appcompat.preference.COUISwitchPreference",
            "com.oplus.aiunit.vision.od5",
            "com.oplus.aiunit.vision.opd",
            "com.oplus.aiunit.vision.ktc0",
            "com.oplus.aiunit.vision.j6d0",
        )
        val prefixRegex = Regex("""[A-Za-z][A-Za-z0-9@#_!*&$%+?.-]{7,79}GwToken[A-Za-z0-9@#_!*&$%+?.-]{4,80}""")

        ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val dex = requireNotNull(DexFile.parse(bytes))
                    TokenDecryptorLocator.locate(dex)?.let(productionTokens::add)
                    dex.firstStringContaining("GwToken") {
                        it.length in 16..80 && prefixRegex.matches(it)
                    }?.let(prefixes::add)
                    views += EnhancementDexView.from(dex)
                    coldStartScan.accept(dex)

                    val statString = dex.firstString { it.startsWith("getGalleryStat failed for device:") }
                    val statIndex = statString?.let(dex::indexOfString) ?: -1
                    dex.classes().forEach { type ->
                        if (type.className in retained) methods[type.className] = type.methodShapes()
                        if (type.referencesString(statIndex)) statClasses += type.className
                    }
                }
        }

        assertEquals(setOf("com.oplus.aiunit.vision.fva0"), productionTokens)
        assertEquals(1, prefixes.size)
        assertEquals(33, prefixes.single().length)
        assertTrue("Stat failure anchor must still be loaded by a class", statClasses.isNotEmpty())

        val targets = EnhancementLocator.locate(views)
        assertEquals("com.oplus.aiunit.vision.ae90", targets.temperatureProvider?.className)
        assertEquals(setOf("com.oplus.aiunit.vision.bzr"), targets.pauseConditionCheckers.map { it.className }.toSet())
        assertEquals(setOf("a", "b"), targets.pauseConditionCheckers.map { it.methodName }.toSet())
        assertEquals("com.oplus.aiunit.vision.w1s", targets.pauseStateInfoClass)
        assertEquals("m", targets.pauseReasonText?.methodName)
        assertEquals("(ILandroid/content/Context;)Ljava/lang/String;", targets.pauseReasonText?.descriptor)

        val coldStart = coldStartScan.resolve()
        assertEquals(ColdStartSyncTargets.forVersion("17.10.41"), coldStart)

        fun requireMethod(type: String, name: String, descriptor: String, isStatic: Boolean) {
            val shapes = requireNotNull(methods[type]) { "Missing class $type" }
            assertEquals("$type.$name", 1, shapes.count {
                it.name == name && it.descriptor == descriptor && it.isStatic == isStatic
            })
        }
        requireMethod(observer, "a", "(Z)V", false)
        // R8 inlines NasBackupConditionObserver.<init> into its caller on 17.10.41; register b()V remains.
        requireMethod(observer, "b", "()V", false)
        requireMethod(lifecycle, "c", "()Z", true)
        requireMethod(network, "c", "()Z", true)
        requireMethod(network, "e", "()Z", true)
        requireMethod("com.oplus.aiunit.vision.opd", "a", "(Ljava/lang/String;Z)Lcom/trim/interconnection/internal/grpc/b;", true)
        requireMethod("com.oplus.aiunit.vision.od5", "a", "()Lio/grpc/ManagedChannel;", false)
        assertTrue(methods.getValue(settings).any { it.name == "onCreatePreferences" })
        assertTrue(methods.containsKey("com.coui.appcompat.preference.COUISwitchPreference"))
        for (oldName in listOf("ktc0", "j6d0")) {
            assertFalse(methods["com.oplus.aiunit.vision.$oldName"].orEmpty().any {
                it.name == "k" && it.descriptor == "(Ljava/lang/String;)Z" && it.isStatic
            })
        }
    }
}
