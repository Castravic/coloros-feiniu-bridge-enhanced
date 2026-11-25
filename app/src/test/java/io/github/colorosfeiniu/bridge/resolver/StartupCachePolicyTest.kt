package io.github.colorosfeiniu.bridge.resolver

import io.github.colorosfeiniu.bridge.ColdStartSyncTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupCachePolicyTest {

    @Test
    fun `key is prefixed per fingerprint namespace`() {
        val key = StartupCachePolicy.keyFor("fp123")
        assertEquals("startup.fp123", key)
        assertTrue(key.startsWith("startup."))
    }

    @Test
    fun `round-trips positive cache snapshot in xml-safe single line`() {
        val snapshot = StartupCacheSnapshot(
            tokenScanned = true,
            tokenClassName = "com.oplus.aiunit.vision.fva0",
            prefixValue = "tRiM@2025#GwToken!sEcReT*kEy&vALu",
            prefixSource = "apk-dex",
            coldStartSyncScanned = true,
            coldStartSync = ColdStartSyncTargets(
                lifecycle = "com.oplus.gallery.foundation.uikit.lifecycle.ActivityLifecycle",
                foreground = "c",
                registration = "com.oplus.gallery.foundation.uikit.lifecycle.ActivityLifecycle\$addListener\$1",
                listener = "com.oplus.aiunit.vision.feq\$b",
                nativeStartMethod = "g",
            ),
            enhancementsScanned = true,
            galleryStatScanned = true,
        )

        val encoded = StartupCachePolicy.encode(snapshot)
        assertFalse(encoded.any { it == '\n' || it == '\r' || it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' })
        assertEquals(snapshot, StartupCachePolicy.decode(encoded))
        assertEquals("ok", StartupCachePolicy.decodeWithReason(encoded).reason)
    }

    @Test
    fun `round-trips negative cache snapshot without targets`() {
        val snapshot = StartupCacheSnapshot(
            tokenScanned = true,
            tokenClassName = null,
            prefixValue = "tRiM@2025#GwToken!sEcReT*kEy&vALu",
            prefixSource = "builtin",
            coldStartSyncScanned = true,
            coldStartSync = null,
            enhancementsScanned = true,
            galleryStatScanned = true,
        )

        val encoded = StartupCachePolicy.encode(snapshot)
        assertFalse(encoded.any { it == '\n' || it == '\r' || it == '&' })
        assertEquals(snapshot, StartupCachePolicy.decode(encoded))
    }

    @Test
    fun `rejects inconsistent or malformed entries`() {
        assertNull(StartupCachePolicy.decode(""))
        assertEquals("schema", StartupCachePolicy.decodeWithReason("schema=99;tokenScanned=true;coldStartSyncScanned=false;enhancementsScanned=false;galleryStatScanned=false").reason)

        // tokenClassName present while tokenScanned=false
        val unScannedToken = "schema=2;tokenScanned=false;tokenClassName=com.example.Token;coldStartSyncScanned=false;enhancementsScanned=false;galleryStatScanned=false"
        assertEquals("token-inconsistent", StartupCachePolicy.decodeWithReason(unScannedToken).reason)
        assertNull(StartupCachePolicy.decode(unScannedToken))

        // prefixValue without prefixSource
        val halfPrefix = "schema=2;tokenScanned=false;prefixValue=tRiM%402025%23GwToken;coldStartSyncScanned=false;enhancementsScanned=false;galleryStatScanned=false"
        assertEquals("prefix-pair", StartupCachePolicy.decodeWithReason(halfPrefix).reason)
        assertNull(StartupCachePolicy.decode(halfPrefix))

        // partial coldStart fields
        val partialColdStart = "schema=2;tokenScanned=false;coldStartSyncScanned=true;coldStart.lifecycle=com.example.Lifecycle;enhancementsScanned=false;galleryStatScanned=false"
        assertEquals("coldstart-partial", StartupCachePolicy.decodeWithReason(partialColdStart).reason)
        assertNull(StartupCachePolicy.decode(partialColdStart))

        // malformed percent escape
        val badEscape = "schema=2;tokenScanned=true;tokenClassName=com.example.%ZZBad;coldStartSyncScanned=false;enhancementsScanned=false;galleryStatScanned=false"
        assertEquals("escape", StartupCachePolicy.decodeWithReason(badEscape).reason)
        assertNull(StartupCachePolicy.decode(badEscape))
    }

    @Test
    fun `rejects legacy v1 multiline cache so device upgrades cleanly`() {
        val legacyV1 = """
            schema=1
            tokenScanned=true
            tokenClassName=com.oplus.aiunit.vision.fva0
            coldStartSyncScanned=false
            enhancementsScanned=true
            galleryStatScanned=true
        """.trimIndent()
        val result = StartupCachePolicy.decodeWithReason(legacyV1)
        assertNull(result.snapshot)
        assertEquals("multiline", result.reason)
    }
}
