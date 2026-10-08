package io.github.colorosfeiniu.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenDecryptorMethodsTest {
    private class Gallery {
        fun e(): String = "prefix"
        fun b(token: String, deviceId: String): String = token + deviceId
        fun b(number: Int): String = number.toString()
    }
    private class Legacy {
        fun e(): String = "prefix"
        fun b(token: String, deviceId: String): Int = token.length + deviceId.length
    }
    private class Devices {
        fun obtainPresharedSecretForDecrypt(): String = "prefix"
        fun decrypt(token: String, deviceId: String): String = token + deviceId
        fun c(token: String, deviceId: String): String = token + deviceId
    }
    @Test fun `keeps gallery decrypt method and rejects unrelated overload`() {
        val methods = TokenDecryptorMethods.decryptMethodsOf(Gallery::class.java, TokenDecryptorTargets.GALLERY)
        assertEquals(1, methods.size)
        assertEquals("b", methods.single().name)
        assertEquals(listOf(String::class.java, String::class.java), methods.single().parameterTypes.toList())
    }
    @Test fun `legacy prefix only target does not gain a guessed diagnostic hook`() {
        assertTrue(TokenDecryptorMethods.decryptMethodsOf(Legacy::class.java, TokenDecryptorTargets.GALLERY).isEmpty())
    }
    @Test fun `device space diagnostics follow a complete contract`() {
        assertEquals(listOf("decrypt"), TokenDecryptorMethods.decryptMethodsOf(Devices::class.java, TokenDecryptorTargets.MY_DEVICES).map { it.name })
        assertTrue(TokenDecryptorMethods.decryptMethodsOf(Devices::class.java, TokenDecryptorTargets.GALLERY).isEmpty())
        assertTrue(TokenDecryptorMethods.decryptMethodsOf(Gallery::class.java, TokenDecryptorTargets.MY_DEVICES).isEmpty())
    }
}
