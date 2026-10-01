package io.github.colorosfeiniu.bridge

/**
 * Finds an application's token decryptor by shape rather than by obfuscated name.
 *
 * A class qualifies only when all three hold, which across every APK seen so far matches exactly one
 * class in the whole APK:
 *
 * 1. the DEX declares the `TokenDecryptor` log tag at all,
 * 2. the class declares both members of one of [TokenDecryptorProfile.contracts],
 * 3. the class body actually loads that log tag, ruling out unrelated classes that happen to share
 *    the two obfuscated member names.
 *
 * Several contracts are tried because one application can ship more than one obfuscation of the same
 * decryptor; see [TokenDecryptorTargets.MY_DEVICES].
 */
internal object TokenDecryptorLocator {

    /** Class name to hook, or null when [dex] does not carry the decryptor of [profile]. */
    fun locate(
        dex: ByteArray,
        profile: TokenDecryptorProfile = TokenDecryptorTargets.GALLERY,
    ): String? {
        val reader = DexFile.parse(dex) ?: return null
        val tagIndex = reader.indexOfString(TokenDecryptorTargets.LOG_TAG)
        if (tagIndex < 0) return null

        return reader.firstClass { clazz ->
            profile.contracts.any { contract ->
                clazz.declaresMethod(contract.prefixMethod, contract.prefixMethodDescriptor) &&
                    clazz.declaresMethod(contract.decryptMethod, contract.decryptMethodDescriptor)
            } && clazz.referencesString(tagIndex)
        }?.className
    }
}
