package io.github.colorosfeiniu.bridge

/**
 * Finds an application's token decryptor by shape rather than by obfuscated name.
 *
 * A class qualifies only when all three hold, which across Gallery 16.40.22 and MyDevices 17.5.5
 * matches exactly one class in the whole APK:
 *
 * 1. the DEX declares the `TokenDecryptor` log tag at all,
 * 2. the class declares both [TokenDecryptorProfile.prefixMethod] and the decrypt entry point
 *    described by [TokenDecryptorProfile.decryptMethod],
 * 3. the class body actually loads that log tag, ruling out unrelated classes that happen to share
 *    the two obfuscated member names.
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
            clazz.declaresMethod(profile.prefixMethod, profile.prefixMethodDescriptor) &&
                clazz.declaresMethod(profile.decryptMethod, profile.decryptMethodDescriptor) &&
                clazz.referencesString(tagIndex)
        }?.className
    }
}
