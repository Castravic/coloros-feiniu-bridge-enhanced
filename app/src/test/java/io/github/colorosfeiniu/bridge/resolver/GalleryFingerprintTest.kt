package io.github.colorosfeiniu.bridge.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GalleryFingerprintTest {
    private val base = GalleryFingerprint(
        packageName = "com.coloros.gallery3d",
        versionCode = 164022,
        lastUpdateTime = 100,
        apks = listOf(
            ApkIdentity("/base.apk", 10, 20),
            ApkIdentity("/split.apk", 30, 40),
        ),
    )

    @Test
    fun `split metadata changes namespace`() {
        assertNotEquals(
            base.cacheNamespace(),
            base.copy(
                apks = listOf(
                    ApkIdentity("/base.apk", 10, 20),
                    ApkIdentity("/split.apk", 31, 40),
                ),
            ).cacheNamespace(),
        )
    }

    @Test
    fun `apk order does not change namespace`() {
        assertEquals(
            base.cacheNamespace(),
            base.copy(apks = base.apks.reversed()).cacheNamespace(),
        )
    }
}
