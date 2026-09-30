package io.github.colorosfeiniu.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenDecryptorTargetsTest {
    @Test
    fun `supports known Gallery token decryptor classes in compatibility order`() {
        assertEquals(
            listOf(
                "com.oplus.aiunit.vision.erq",
                "com.oplus.aiunit.vision.in80",
                "com.oplus.aiunit.vision.op80",
                "com.oplus.aiunit.vision.qp80",
            ),
            TokenDecryptorTargets.classNames,
        )
    }

    @Test
    fun `resolves one profile per target package`() {
        assertEquals(
            TokenDecryptorTargets.GALLERY,
            TokenDecryptorTargets.forPackage("com.coloros.gallery3d"),
        )
        assertEquals(
            TokenDecryptorTargets.MY_DEVICES,
            TokenDecryptorTargets.forPackage("com.heytap.mydevices"),
        )
        assertNull(TokenDecryptorTargets.forPackage("com.example.unknown"))
    }

    @Test
    fun `Device Space profile keeps its own member contract`() {
        val profile = TokenDecryptorTargets.MY_DEVICES

        assertEquals("com.heytap.mydevices", profile.packageName)
        assertEquals(emptyList<String>(), profile.classNames)
        assertEquals("m", profile.prefixMethod)
        assertEquals("()Ljava/lang/String;", profile.prefixMethodDescriptor)
        assertEquals("c", profile.decryptMethod)
        assertEquals(
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            profile.decryptMethodDescriptor,
        )
    }
}
