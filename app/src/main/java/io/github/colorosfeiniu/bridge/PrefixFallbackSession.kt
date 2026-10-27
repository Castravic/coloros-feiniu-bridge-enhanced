package io.github.colorosfeiniu.bridge

/** One prefix loader hook's lifetime. Never shares recovered values across loaders or processes. */
internal class PrefixFallbackSession {
    @Volatile
    private var recovered: PrefixFallbackDecision.Outcome.Supply? = null

    fun resolve(
        loader: () -> Any?,
        fallback: () -> ResolvedPrefix?,
        onLoaderFailure: (Throwable) -> Unit = {},
    ): PrefixFallbackDecision.Outcome {
        recovered?.let { return it }
        // Parallel backup/refresh calls must not all retry the failing hardware service.
        return synchronized(this) {
            recovered?.let { return@synchronized it }
            val result = runCatching(loader)
            val error = result.exceptionOrNull()
            if (error is Error) throw error
            val decision = PrefixFallbackDecision.decide(result.getOrNull(), error, fallback)
            if (error != null) onLoaderFailure(error)
            if (decision is PrefixFallbackDecision.Outcome.Supply) recovered = decision
            decision
        }
    }
}
