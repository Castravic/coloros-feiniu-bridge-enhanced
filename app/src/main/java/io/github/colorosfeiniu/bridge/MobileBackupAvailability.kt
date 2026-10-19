package io.github.colorosfeiniu.bridge

/** Records installed dependencies, rather than merely discovered candidates. */
internal data class MobileBackupAvailability(
    val wlanHook: Boolean = false,
    val mobileValidator: Boolean = false,
    val conditionScope: Boolean = false,
    val observerHook: Boolean = false,
) {
    val missing: List<String>
        get() = buildList {
            if (!wlanHook) add("wlan-hook")
            if (!mobileValidator) add("mobile-validator")
            if (!conditionScope) add("condition-scope")
            if (!observerHook) add("observer-hook")
        }

    val ready: Boolean
        get() = missing.isEmpty()
}
