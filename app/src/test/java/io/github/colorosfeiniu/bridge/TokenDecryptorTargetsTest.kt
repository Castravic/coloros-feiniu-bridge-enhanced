package io.github.colorosfeiniu.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            TokenDecryptorTargets.GALLERY.classNames,
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
    fun `Gallery profile keeps the single e and b contract`() {
        assertEquals(
            listOf(contract("e", "b")),
            TokenDecryptorTargets.GALLERY.contracts,
        )
    }

    @Test
    fun `Device Space profile keeps every known obfuscation of its decryptor`() {
        val profile = TokenDecryptorTargets.MY_DEVICES

        assertEquals("com.heytap.mydevices", profile.packageName)
        assertEquals(
            listOf("com.trim.connectiondemo.utils.TokenDecryptor"),
            profile.classNames,
        )
        assertEquals(
            listOf(
                contract("m", "c"),
                contract("obtainPresharedSecretForDecrypt", "decrypt"),
            ),
            profile.contracts,
        )
    }

    @Test
    fun `prefix loader lookup spans every contract of a profile`() {
        val profile = TokenDecryptorTargets.MY_DEVICES

        assertTrue(profile.matchesPrefixLoader("m"))
        assertTrue(profile.matchesPrefixLoader("obtainPresharedSecretForDecrypt"))
        assertFalse(profile.matchesPrefixLoader("e"))
        assertFalse(profile.matchesPrefixLoader("decrypt"))
    }

    @Test
    fun `decrypt entry point lookup spans every contract of a profile`() {
        val profile = TokenDecryptorTargets.MY_DEVICES

        assertTrue(profile.matchesDecryptEntryPoint("c"))
        assertTrue(profile.matchesDecryptEntryPoint("decrypt"))
        assertFalse(profile.matchesDecryptEntryPoint("b"))
        assertFalse(profile.matchesDecryptEntryPoint("m"))
    }

    private fun contract(prefixMethod: String, decryptMethod: String) =
        TokenDecryptorContract(
            prefixMethod = prefixMethod,
            prefixMethodDescriptor = "()Ljava/lang/String;",
            decryptMethod = decryptMethod,
            decryptMethodDescriptor = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        )
}
