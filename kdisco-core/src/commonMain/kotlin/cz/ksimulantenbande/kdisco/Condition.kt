// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

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
