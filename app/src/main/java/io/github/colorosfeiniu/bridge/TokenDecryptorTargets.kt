package io.github.colorosfeiniu.bridge

/**
 * One obfuscation variant of the Feiniu token decryptor contract.
 *
 * [prefixMethod] loads the `GwToken` prefix and [decryptMethod] is the entry point that consumes it.
 * The prefix loader is always a no-argument method returning `String`, the decrypt entry point always
 * takes two `String`s and returns `String`, so only the member names carry the variant.
 */
internal data class TokenDecryptorContract(
    val prefixMethod: String,
    val prefixMethodDescriptor: String,
    val decryptMethod: String,
    val decryptMethodDescriptor: String,
)

/**
 * Describes one application's token decryptor.
 *
 * OPPO reshuffles the obfuscated class names on nearly every release, so [classNames] is only the
 * fast path. When none of them satisfy [contracts], [TokenDecryptorLocator] finds the class by
 * shape instead, using the members and the log tag captured here.
 *
 * A profile lists every contract seen for the application's package rather than a single one: the
 * same decryptor ships under different obfuscation across builds, and Device Space is already known
 * to have two.
 */
internal data class TokenDecryptorProfile(
    val packageName: String,
    val classNames: List<String>,
    val contracts: List<TokenDecryptorContract>,
) {
    /** True when [methodName] is the prefix loader of any known contract. */
    fun matchesPrefixLoader(methodName: String): Boolean =
        contracts.any { it.prefixMethod == methodName }

    /** True when [methodName] is the decrypt entry point of any known contract. */
    fun matchesDecryptEntryPoint(methodName: String): Boolean =
        contracts.any { it.decryptMethod == methodName }
}

internal object TokenDecryptorTargets {

    /** Log tag the decryptor passes to its logger; survives obfuscation as a literal. */
    const val LOG_TAG = "TokenDecryptor"

    private const val PREFIX_DESCRIPTOR = "()Ljava/lang/String;"
    private const val DECRYPT_DESCRIPTOR =
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"

    private fun contract(prefixMethod: String, decryptMethod: String) =
        TokenDecryptorContract(
            prefixMethod = prefixMethod,
            prefixMethodDescriptor = PREFIX_DESCRIPTOR,
            decryptMethod = decryptMethod,
            decryptMethodDescriptor = DECRYPT_DESCRIPTOR,
        )

    /**
     * ColorOS Gallery — `erq`/`in80` on early ColorOS 16, `op80` on 16.40.13, `qp80` on 16.40.22.
     * Every build seen so far keeps the `e()` / `b(String, String)` shape; only the class name moves.
     */
    val GALLERY = TokenDecryptorProfile(
        packageName = "com.coloros.gallery3d",
        classNames = listOf(
            "com.oplus.aiunit.vision.erq",
            "com.oplus.aiunit.vision.in80",
            "com.oplus.aiunit.vision.op80",
            "com.oplus.aiunit.vision.qp80",
        ),
        contracts = listOf(contract(prefixMethod = "e", decryptMethod = "b")),
    )

    /**
     * Device Space ("设备空间"). Two obfuscations of the same decryptor are known:
     *
     * - MyDevices 17.5.5 (ColorOS 16.1 / Android 16): `m()` / `c(String, String)`, inside a
     *   package-local obfuscated class (`aa.d80`), so structural discovery is the only path.
     * - MyDevices 17.25.10 (ColorOS 17 / Android 17): the decryptor is not obfuscated at all and
     *   ships as `com.trim.connectiondemo.utils.TokenDecryptor`, carrying
     *   `obtainPresharedSecretForDecrypt()` / `decrypt(String, String)`. The class name is stable
     *   enough to keep as the fast path.
     *
     * Both variants share the 33 character prefix and `SHA-256(prefix + deviceId)` key derivation.
     */
    val MY_DEVICES = TokenDecryptorProfile(
        packageName = "com.heytap.mydevices",
        classNames = listOf("com.trim.connectiondemo.utils.TokenDecryptor"),
        contracts = listOf(
            contract(prefixMethod = "m", decryptMethod = "c"),
            contract(
                prefixMethod = "obtainPresharedSecretForDecrypt",
                decryptMethod = "decrypt",
            ),
        ),
    )

    val profiles = listOf(GALLERY, MY_DEVICES)

    fun forPackage(packageName: String): TokenDecryptorProfile? =
        profiles.firstOrNull { it.packageName == packageName }
}
