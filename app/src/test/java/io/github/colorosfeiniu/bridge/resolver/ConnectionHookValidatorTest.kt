package io.github.colorosfeiniu.bridge.resolver

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionHookValidatorTest {
    @Test
    fun `complete linked gallery signatures validate`() {
        assertNotNull(
            ConnectionHookValidator.validateGallery(
                validGalleryRefs(),
                javaClass.classLoader!!,
            ),
        )
    }

    @Test
    fun `wrong real albums connection type is rejected`() {
        val valid = validGalleryRefs()
        val invalid = valid.copy(
            realAlbums = valid.realAlbums.copy(
                parameterTypeNames = listOf(
                    String::class.java.name,
                    String::class.java.name,
                    "int",
                    "int",
                ),
            ),
        )
        assertNull(ConnectionHookValidator.validateGallery(invalid, javaClass.classLoader!!))
    }

    private fun validGalleryRefs(): GalleryHookRefs {
        val provider = ProviderFixture::class.java
        val stat = provider.getDeclaredMethod(
            "stat",
            ConnectionFixture::class.java,
            String::class.java,
        )
        val albums = provider.getDeclaredMethod(
            "albums",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
        )
        val connection = provider.getDeclaredMethod(
            "connection",
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        val realAlbums = provider.getDeclaredMethod(
            "realAlbums",
            ConnectionFixture::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val cache = CacheFixture::class.java.getDeclaredMethod("get", String::class.java)
        return GalleryHookRefs(
            stat = stat.toMethodRef(),
            albums = albums.toMethodRef(),
            connection = connection.toMethodRef(),
            realAlbums = realAlbums.toMethodRef(),
            cache = cache.toMethodRef(),
            photoCount = StatFixture::class.java.getDeclaredField("photos").toFieldRef(),
            videoCount = StatFixture::class.java.getDeclaredField("videos").toFieldRef(),
            source = ResolutionSource.KNOWN,
        )
    }

    private class ConnectionFixture

    private class StatFixture {
        @JvmField
        var photos: Int = 0

        @JvmField
        var videos: Int = 0
    }

    private class ProviderFixture {
        fun albums(offset: Int, limit: Int, device: String): List<String> = emptyList()

        fun connection(device: String, cached: Boolean): ConnectionFixture =
            ConnectionFixture()

        fun realAlbums(
            connection: ConnectionFixture,
            device: String,
            limit: Int,
            offset: Int,
        ): List<String> = emptyList()

        companion object {
            @JvmStatic
            fun stat(connection: ConnectionFixture, device: String): StatFixture =
                StatFixture()
        }
    }

    private object CacheFixture {
        @JvmStatic
        fun get(device: String): StatFixture = StatFixture()
    }
}
