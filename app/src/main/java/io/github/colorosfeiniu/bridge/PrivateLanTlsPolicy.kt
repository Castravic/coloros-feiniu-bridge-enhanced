package io.github.colorosfeiniu.bridge

internal object PrivateLanTlsPolicy {
    fun shouldUseCompatibility(
        originalResult: Boolean?,
        hasThrowable: Boolean,
        address: String?,
    ): Boolean {
        return !hasThrowable &&
            originalResult == false &&
            PrivateLanAddress.isRfc1918Ipv4(address)
    }
}
