package io.github.colorosfeiniu.bridge.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectionHookRefsTest {
    private val prefix = MethodRef(
        descriptor = "Lx/T;->p()Ljava/lang/String;",
        className = "x.T",
        name = "p",
        returnTypeName = "java.lang.String",
        parameterTypeNames = emptyList(),
        isStatic = false,
    )
    private val decrypt = MethodRef(
        descriptor = "Lx/T;->d(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        className = "x.T",
        name = "d",
        returnTypeName = "java.lang.String",
        parameterTypeNames = listOf("java.lang.String", "java.lang.String"),
        isStatic = false,
    )

    @Test fun `resolved hooks require at least one complete group`() {
        assertThrows(IllegalArgumentException::class.java) {
            ResolvedConnectionHooks(token = null, gallery = null)
        }
    }

    @Test fun `token group preserves immutable descriptors and source`() {
        val token = TokenHookRefs(prefix, decrypt, ResolutionSource.SEMANTIC)
        assertEquals("x.T", token.prefix.className)
        assertEquals(ResolutionSource.SEMANTIC, token.source)
    }
}
