package io.github.colorosfeiniu.bridge.resolver

import io.github.colorosfeiniu.bridge.ColdStartSyncTargets

internal data class StartupCacheSnapshot(
    val tokenScanned: Boolean = false,
    val tokenClassName: String? = null,
    val prefixValue: String? = null,
    val prefixSource: String? = null,
    val coldStartSyncScanned: Boolean = false,
    val coldStartSync: ColdStartSyncTargets? = null,
    val enhancementsScanned: Boolean = false,
    val galleryStatScanned: Boolean = false,
)

internal data class StartupCacheDecodeResult(
    val snapshot: StartupCacheSnapshot?,
    val reason: String,
)

/**
 * Pure-JVM serialization and consistency policy for per-APK startup resolution state
 * (including negative cache flags and recovered prefix), testable without Android/Robolectric.
 *
 * Uses a single-line `;`-delimited format with uppercase `%XX` percent-encoding for any byte outside
 * `[A-Za-z0-9._$-]`, avoiding XML escaping / whitespace issues in SharedPreferences across OS builds.
 */
internal object StartupCachePolicy {

    const val PREFERENCES_FILE = "coloros_feiniu_bridge"
    const val CACHE_SCHEMA = 2

    private const val KEY_PREFIX = "startup."
    private val VALID_PREFIX_SOURCES = setOf("apk-dex", "builtin")
    private val HEX_DIGITS = "0123456789ABCDEF"

    fun keyFor(namespace: String): String = KEY_PREFIX + namespace

    fun encode(snapshot: StartupCacheSnapshot): String = buildString {
        append("schema=").append(CACHE_SCHEMA)
        append(";tokenScanned=").append(snapshot.tokenScanned)
        snapshot.tokenClassName?.let {
            append(";tokenClassName=").append(escape(it))
        }
        if (snapshot.prefixValue != null && snapshot.prefixSource != null) {
            append(";prefixValue=").append(escape(snapshot.prefixValue))
            append(";prefixSource=").append(escape(snapshot.prefixSource))
        }
        append(";coldStartSyncScanned=").append(snapshot.coldStartSyncScanned)
        snapshot.coldStartSync?.let { sync ->
            append(";coldStart.lifecycle=").append(escape(sync.lifecycle))
            append(";coldStart.foreground=").append(escape(sync.foreground))
            append(";coldStart.registration=").append(escape(sync.registration))
            append(";coldStart.listener=").append(escape(sync.listener))
            append(";coldStart.method=").append(escape(sync.nativeStartMethod))
        }
        append(";enhancementsScanned=").append(snapshot.enhancementsScanned)
        append(";galleryStatScanned=").append(snapshot.galleryStatScanned)
    }

    fun decode(raw: String): StartupCacheSnapshot? = decodeWithReason(raw).snapshot

    fun decodeWithReason(raw: String): StartupCacheDecodeResult {
        if (raw.isBlank()) return StartupCacheDecodeResult(null, "blank")
        if ('\n' in raw || '\r' in raw) return StartupCacheDecodeResult(null, "multiline")

        val entries = linkedMapOf<String, String>()
        for (segment in raw.split(';')) {
            if (segment.isEmpty()) continue
            val eq = segment.indexOf('=')
            if (eq <= 0) return StartupCacheDecodeResult(null, "syntax")
            val key = segment.substring(0, eq)
            if (key in entries) return StartupCacheDecodeResult(null, "dup")
            val value = unescape(segment.substring(eq + 1))
                ?: return StartupCacheDecodeResult(null, "escape")
            entries[key] = value
        }

        if (entries["schema"]?.toIntOrNull() != CACHE_SCHEMA) {
            return StartupCacheDecodeResult(null, "schema")
        }
        val tokenScanned = entries["tokenScanned"]?.toBooleanStrictOrNull()
            ?: return StartupCacheDecodeResult(null, "bool")
        val coldStartSyncScanned = entries["coldStartSyncScanned"]?.toBooleanStrictOrNull()
            ?: return StartupCacheDecodeResult(null, "bool")
        val enhancementsScanned = entries["enhancementsScanned"]?.toBooleanStrictOrNull()
            ?: return StartupCacheDecodeResult(null, "bool")
        val galleryStatScanned = entries["galleryStatScanned"]?.toBooleanStrictOrNull()
            ?: return StartupCacheDecodeResult(null, "bool")

        val tokenClassName = entries["tokenClassName"]
        if (tokenClassName != null && (tokenClassName.isBlank() || !tokenScanned)) {
            return StartupCacheDecodeResult(null, "token-inconsistent")
        }

        val prefixValue = entries["prefixValue"]
        val prefixSource = entries["prefixSource"]
        if ((prefixValue == null) != (prefixSource == null)) {
            return StartupCacheDecodeResult(null, "prefix-pair")
        }
        if (prefixValue != null) {
            if (prefixValue.length !in 16..80 || prefixSource !in VALID_PREFIX_SOURCES) {
                return StartupCacheDecodeResult(null, "prefix-invalid")
            }
            if (prefixValue.any { it == '\n' || it == '\r' }) {
                return StartupCacheDecodeResult(null, "prefix-invalid")
            }
        }

        val coldStartKeys = listOf(
            "coldStart.lifecycle",
            "coldStart.foreground",
            "coldStart.registration",
            "coldStart.listener",
            "coldStart.method",
        )
        val presentColdStart = coldStartKeys.count { it in entries }
        val coldStartSync = when (presentColdStart) {
            0 -> null
            coldStartKeys.size -> {
                val lifecycle = entries.getValue("coldStart.lifecycle")
                val foreground = entries.getValue("coldStart.foreground")
                val registration = entries.getValue("coldStart.registration")
                val listener = entries.getValue("coldStart.listener")
                val method = entries.getValue("coldStart.method")
                if (
                    lifecycle.isBlank() || foreground.isBlank() || registration.isBlank() ||
                    listener.isBlank() || method.isBlank() || !coldStartSyncScanned
                ) {
                    return StartupCacheDecodeResult(null, "coldstart-inconsistent")
                }
                ColdStartSyncTargets(lifecycle, foreground, registration, listener, method)
            }
            else -> return StartupCacheDecodeResult(null, "coldstart-partial")
        }

        val snapshot = StartupCacheSnapshot(
            tokenScanned = tokenScanned,
            tokenClassName = tokenClassName,
            prefixValue = prefixValue,
            prefixSource = prefixSource,
            coldStartSyncScanned = coldStartSyncScanned,
            coldStartSync = coldStartSync,
            enhancementsScanned = enhancementsScanned,
            galleryStatScanned = galleryStatScanned,
        )
        return StartupCacheDecodeResult(snapshot, "ok")
    }

    private fun isSafeByte(b: Int): Boolean =
        (b in 'A'.code..'Z'.code) ||
            (b in 'a'.code..'z'.code) ||
            (b in '0'.code..'9'.code) ||
            b == '.'.code ||
            b == '_'.code ||
            b == '$'.code ||
            b == '-'.code

    private fun escape(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return buildString(bytes.size) {
            for (rawByte in bytes) {
                val b = rawByte.toInt() and 0xff
                if (isSafeByte(b)) {
                    append(b.toChar())
                } else {
                    append('%')
                    append(HEX_DIGITS[b ushr 4])
                    append(HEX_DIGITS[b and 0x0f])
                }
            }
        }
    }

    private fun unescape(value: String): String? {
        val out = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%') {
                if (i + 2 >= value.length) return null
                val hi = hexValue(value[i + 1])
                val lo = hexValue(value[i + 2])
                if (hi < 0 || lo < 0) return null
                out += ((hi shl 4) or lo).toByte()
                i += 3
            } else {
                val code = ch.code
                if (code > 0x7f) return null
                out += code.toByte()
                i += 1
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun hexValue(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch.code - '0'.code
        in 'A'..'F' -> ch.code - 'A'.code + 10
        in 'a'..'f' -> ch.code - 'a'.code + 10
        else -> -1
    }
}
