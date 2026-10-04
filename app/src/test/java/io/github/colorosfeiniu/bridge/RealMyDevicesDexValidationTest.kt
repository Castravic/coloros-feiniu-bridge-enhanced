package io.github.colorosfeiniu.bridge

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Optional device-validation test. Supply `-Dmydevices.dex.path=` with a MyDevices APK or a single
 * `classes5.dex` when validating a legally obtained build; CI intentionally has no proprietary DEX.
 *
 * The prefix loader and the decrypt entry point are obfuscated per build, so a stale profile makes
 * the module stop hooking without failing any other test. This pins the MyDevices 17.25.10 contract.
 */
class RealMyDevicesDexValidationTest {

    @Test
    fun `locates the Device Space decryptor of MyDevices 17 25 10`() {
        val path = System.getProperty(DEX_PATH_PROPERTY)
        assumeTrue("Set -D$DEX_PATH_PROPERTY to a MyDevices APK or classes5.dex path", !path.isNullOrBlank())

        val source = File(requireNotNull(path))
        assertTrue("Supplied path does not exist: $source", source.isFile)

        assertEquals(
            "com.trim.connectiondemo.utils.TokenDecryptor",
            scan(source) { bytes ->
                TokenDecryptorLocator.locate(bytes, TokenDecryptorTargets.MY_DEVICES)
            },
        )
    }

    /** Mirrors the module's own walk: a whole APK in entry order, or a bare DEX image. */
    private fun scan(source: File, transform: (ByteArray) -> String?): String? {
        if (!source.name.endsWith(".apk")) return transform(source.readBytes())
        return ZipFile(source).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.endsWith(".dex") }
                .firstNotNullOfOrNull { entry ->
                    transform(zip.getInputStream(entry).use { it.readBytes() })
                }
        }
    }

    private companion object {
        const val DEX_PATH_PROPERTY = "mydevices.dex.path"
    }
}
