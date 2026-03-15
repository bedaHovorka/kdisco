package cz.hovorka.kdisco

/**
 * A predicate used with [Process.waitUntil] for specifying state-events.
 *
 * `waitUntil(cond)` causes the active discrete process to become passive until
 * the [test] method evaluates to `true`.
 *
 * ### Example
 * ```kotlin
 * val pressureHigh = Condition { pressure.state >= 200 }
 * waitUntil(pressureHigh)
 * ```
 *
 * Since this is a `fun interface`, SAM conversion is supported:
 * ```kotlin
 * waitUntil { pressure.state >= 200 }
 * ```
 *
 * @see Process.waitUntil
 * @since 0.2.0
 */
fun interface Condition {
    /**
     * Returns `true` if the condition is fulfilled; `false` otherwise.
     */
    fun test(): Boolean
}
