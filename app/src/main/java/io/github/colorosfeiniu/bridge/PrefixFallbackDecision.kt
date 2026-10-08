package io.github.colorosfeiniu.bridge

/** The prefix recovered for the hooked loader, together with where it came from. */
internal data class ResolvedPrefix(
    val value: String,
    val source: String,
)

/**
 * What the hook returns once the intercepted prefix loader has run.
 *
 * The loader belongs to the target application, so it can fail in ways the module cannot influence:
 * it may answer blank, and it may throw instead of answering at all. A failure that can be repaired
 * is repaired; one that cannot still reaches the caller unchanged, so the hook never hides a broken
 * application behind a value it invented. The choice lives here, apart from the Android and
 * libxposed types the hook works with, because no device can be asked to throw on demand.
 */
internal object PrefixFallbackDecision {

    sealed interface Outcome {
        /** The loader's own value is handed back untouched. */
        data class Passthrough(val value: Any?) : Outcome

        /** The recovered prefix stands in for a failed or blank loader result. */
        data class Supply(val prefix: ResolvedPrefix) : Outcome

        /** Nothing was recovered, so the loader's failure reaches the caller unchanged. */
        data class Rethrow(val error: Throwable) : Outcome
    }

    /**
     * [fallback] runs only when the loader did not answer with a usable value, which keeps the DEX
     * scan behind it off the happy path.
     */
    fun decide(returned: Any?, error: Throwable?, fallback: () -> ResolvedPrefix?): Outcome {
        if (error == null && !returned.isNullOrBlankString()) return Outcome.Passthrough(returned)

        fallback()?.let { return Outcome.Supply(it) }

        return if (error == null) Outcome.Passthrough(returned) else Outcome.Rethrow(error)
    }

    private fun Any?.isNullOrBlankString(): Boolean = (this as? String).isNullOrBlank()
}
