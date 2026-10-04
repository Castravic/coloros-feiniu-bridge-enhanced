package io.github.colorosfeiniu.bridge

import io.github.colorosfeiniu.bridge.resolver.EnhancementDexView
import io.github.colorosfeiniu.bridge.resolver.EnhancementLocator
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional device-validation test for the dynamically located enhancement groups.
 *
 * Supply `-Dgallery.apk.path=/path/to/gallery-17.9.24.apk` (a legally obtained APK) to run the real
 * DEX projection + locators end to end. CI intentionally has no proprietary APK; the same anchor
 * logic is covered by [io.github.colorosfeiniu.bridge.resolver.EnhancementLocatorTest].
 */
class RealGalleryEnhancementDexValidationTest {

    @Test
    fun `locates temperature, pause condition and state info in the supplied gallery apk`() {
        val path = System.getProperty(APK_PATH_PROPERTY)
        assumeTrue(
            "Set -D$APK_PATH_PROPERTY to a Gallery 17.9.24 APK path",
            !path.isNullOrBlank(),
        )

        val apk = File(requireNotNull(path))
        assumeTrue("Supplied APK does not exist: $apk", apk.isFile)

        val views = mutableListOf<io.github.colorosfeiniu.bridge.resolver.ClassView>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.endsWith(".dex") }
                .forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    views += EnhancementDexView.from(bytes)
                }
        }

        val targets = EnhancementLocator.locate(views)
        assertEquals("com.oplus.aiunit.vision.ui90", targets.temperatureProvider?.className)
        assertEquals("com.oplus.aiunit.vision.wxr", targets.pauseConditionCheckers.firstOrNull()?.className)
        assertNotNull(targets.pauseStateInfoClass)
        assertNotNull(targets.pauseReasonText)
    }

    private companion object {
        const val APK_PATH_PROPERTY = "gallery.apk.path"
    }
}
