package io.github.colorosfeiniu.bridge

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class PrivateLanAddressTest(
    private val address: String?,
    private val expected: Boolean,
) {
    @Test
    fun recognizesOnlyRfc1918Ipv4Literals() {
        assertEquals(expected, PrivateLanAddress.isRfc1918Ipv4(address))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0} -> {1}")
        fun cases(): List<Array<Any?>> = listOf(
            arrayOf("10.0.0.0", true),
            arrayOf("10.255.255.255", true),
            arrayOf(" 192.168.1.15 ", true),
            arrayOf("192.168.0.0", true),
            arrayOf("192.168.255.255", true),
            arrayOf("172.16.0.0", true),
            arrayOf("172.31.255.255", true),
            arrayOf("172.15.255.255", false),
            arrayOf("172.32.0.0", false),
            arrayOf("8.8.8.8", false),
            arrayOf("100.64.0.1", false),
            arrayOf("127.0.0.1", false),
            arrayOf("169.254.1.1", false),
            arrayOf("::1", false),
            arrayOf("fe80::1", false),
            arrayOf("nas.example.test", false),
            arrayOf("192.168.1", false),
            arrayOf("192.168.1.1.5", false),
            arrayOf("192.168.1.", false),
            arrayOf(".192.168.1.1", false),
            arrayOf("192.168.1.256", false),
            arrayOf("00010.0.0.1", false),
            arrayOf("192.168.-1.1", false),
            arrayOf("192.168.+1.1", false),
            arrayOf("１９２.１６８.１.１", false),
            arrayOf("", false),
            arrayOf("   ", false),
            arrayOf(null, false),
        )
    }
}
