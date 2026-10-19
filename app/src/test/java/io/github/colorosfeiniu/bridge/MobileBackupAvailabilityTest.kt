package io.github.colorosfeiniu.bridge

import org.junit.Assert.*
import org.junit.Test

class MobileBackupAvailabilityTest {
    @Test
    fun `only complete installation enables mobile backup`() {
        for (mask in 0..15) {
            val state = MobileBackupAvailability(
                wlanHook = mask and 1 != 0,
                mobileValidator = mask and 2 != 0,
                conditionScope = mask and 4 != 0,
                observerHook = mask and 8 != 0,
            )
            assertEquals("installation mask=$mask", mask == 15, state.ready)
            assertEquals(4 - Integer.bitCount(mask), state.missing.size)
        }
    }

    @Test
    fun `missing dependencies are identified for diagnostics`() {
        assertEquals(
            listOf("mobile-validator", "condition-scope"),
            MobileBackupAvailability(wlanHook = true, observerHook = true).missing,
        )
        assertFalse(MobileBackupAvailability().ready)
    }
}
