package io.github.colorosfeiniu.bridge

internal object PrivateLanAddress {
    fun isRfc1918Ipv4(address: String?): Boolean {
        val parts = address?.trim()?.split('.') ?: return false
        if (parts.size != 4) return false

        val octets = parts.map { part ->
            if (part.length !in 1..3 || part.any { it !in '0'..'9' }) return false
            part.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }

        return when (octets[0]) {
            10 -> true
            172 -> octets[1] in 16..31
            192 -> octets[1] == 168
            else -> false
        }
    }
}
