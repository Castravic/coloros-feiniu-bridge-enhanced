package io.github.colorosfeiniu.bridge

/**
 * Describes one application's token decryptor.
 *
 * OPPO reshuffles the obfuscated class names on nearly every release, so [classNames] is only the
 * fast path. When none of them satisfy the full method contract, [TokenDecryptorLocator] finds the
 * class by shape instead, using [prefixMethod], [decryptMethod] and the log tag captured here.
 *
 * Two applications embed the same Feiniu token scheme (`SHA-256(prefix + deviceId)` with the same
 * 33 character prefix), but they obfuscate the members differently:
 *
 * - ColorOS Gallery (`com.coloros.gallery3d`): prefix loader `e()`, decrypt entry `b(String, String)`.
 * - Device Space (`com.heytap.mydevices`): prefix loader `m()`, decrypt entry `c(String, String)`.
 */
internal data class TokenDecryptorProfile(
    val packageName: String,
    val classNames: List<String>,
    val prefixMethod: String,
    val prefixMethodDescriptor: String,
    val decryptMethod: String,
    val decryptMethodDescriptor: String,
)

internal object TokenDecryptorTargets {

    /** Log tag the decryptor passes to its logger; survives obfuscation as a literal. */
    const val LOG_TAG = "TokenDecryptor"

    /** ColorOS Gallery — `erq`/`in80` on early ColorOS 16, `op80` on 16.40.13, `qp80` on 16.40.22. */
    val GALLERY = TokenDecryptorProfile(
        packageName = "com.coloros.gallery3d",
        classNames = listOf(
            "com.oplus.aiunit.vision.erq",
            "com.oplus.aiunit.vision.in80",
            "com.oplus.aiunit.vision.op80",
            "com.oplus.aiunit.vision.qp80",
        ),
        prefixMethod = "e",
        prefixMethodDescriptor = "()Ljava/lang/String;",
        decryptMethod = "b",
        decryptMethodDescriptor = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
    )

    /**
     * Device Space ("设备空间"). No stable class name is known: the decryptor lives in a
     * package-local obfuscated class (e.g. `aa.d80` on MyDevices 17.5.5), so structural discovery
     * is the only path and it resolves to exactly one class across the whole APK.
     */
    val MY_DEVICES = TokenDecryptorProfile(
        packageName = "com.heytap.mydevices",
        classNames = emptyList(),
        prefixMethod = "m",
        prefixMethodDescriptor = "()Ljava/lang/String;",
        decryptMethod = "c",
        decryptMethodDescriptor = "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
    )

    val profiles = listOf(GALLERY, MY_DEVICES)

    fun forPackage(packageName: String): TokenDecryptorProfile? =
        profiles.firstOrNull { it.packageName == packageName }

    // --- Gallery aliases kept for the existing unit tests and legacy call sites. ---

    val classNames = GALLERY.classNames

    /** Prefix loader the bridge hooks in Gallery — `getOrLoadPrefix()` before obfuscation. */
    const val PREFIX_METHOD = "e"
    const val PREFIX_METHOD_DESCRIPTOR = "()Ljava/lang/String;"

    /** Token decrypt entry point, kept as a structural marker only. Never hooked. */
    const val DECRYPT_METHOD = "b"
    const val DECRYPT_METHOD_DESCRIPTOR =
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
}
