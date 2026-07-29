package io.github.colorosfeiniu.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateLanTlsPolicyTest {
    @Test
    fun enablesCompatibilityOnlyForAnOriginallyFalsePrivateIpv4Result() {
        assertTrue(
            PrivateLanTlsPolicy.shouldUseCompatibility(
                originalResult = false,
                hasThrowable = false,
                address = "192.168.1.15",
            ),
        )

        assertFalse(
            PrivateLanTlsPolicy.shouldUseCompatibility(
                originalResult = true,
                hasThrowable = false,
                address = "192.168.1.15",
            ),
        )
        assertFalse(
            PrivateLanTlsPolicy.shouldUseCompatibility(
                originalResult = null,
                hasThrowable = false,
                address = "192.168.1.15",
            ),
        )
        assertFalse(
            PrivateLanTlsPolicy.shouldUseCompatibility(
                originalResult = false,
                hasThrowable = true,
                address = "192.168.1.15",
            ),
        )
        assertFalse(
            PrivateLanTlsPolicy.shouldUseCompatibility(
                originalResult = false,
                hasThrowable = false,
                address = "8.8.8.8",
            ),
        )
        assertFalse(
            PrivateLanTlsPolicy.shouldUseCompatibility(
                originalResult = false,
                hasThrowable = false,
                address = null,
            ),
        )
    }
}
