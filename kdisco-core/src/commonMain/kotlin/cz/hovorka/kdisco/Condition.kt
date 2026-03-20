package cz.hovorka.kdisco

/**
 * A testable condition used with [Process.waitUntil].
 *
 * This is a functional interface (SAM), so lambdas can be used directly:
 * ```kotlin
 * waitUntil { velocity.state == 0.0 }
 * waitUntil(Condition { pathIsAvailable() })
 * ```
 */
fun interface Condition {
    fun test(): Boolean
}
