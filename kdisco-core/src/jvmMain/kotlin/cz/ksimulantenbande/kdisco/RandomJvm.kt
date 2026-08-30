// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlin.math.sqrt
import kotlin.random.Random as KRandom
import kotlin.random.asKotlinRandom

/**
 * JVM implementation of [Random] backed by [java.util.Random].
 *
 * This preserves the random sequences of jDisco's `Random` class
 * (which extends `java.util.Random`), including:
 * - `normal()` → Marsaglia polar method with caching (mirroring `nextGaussian()`)
 * - `exp()` → `-a * ln(nextDouble())`
 * - `negexp()` → `-ln(nextDouble()) / a`
 *
 * Transcendental functions (`ln`, `exp`) go through [PortableMath] instead of
 * `java.lang.Math` so that [exp], [negexp], [normal] and [poisson] draws are
 * bit-identical across JVM, JS and Native (see issue #69). [PortableMath] is a
 * faithful fdlibm port, so on the JVM it matches `StrictMath.log`/`StrictMath.exp`
 * (which `java.util.Random.nextGaussian()` itself uses). `sqrt` is correctly
 * rounded per IEEE-754 on all platforms and needs no replacement.
 *
 * **Backward compatibility vs jDisco / pre-PR kDisco**: `normal()` and `poisson()`
 * are bit-unchanged (`nextGaussian()` uses `StrictMath.log`; `Math.exp` matches
 * `StrictMath.exp` for the `poisson` acceptance limit). `exp()`/`negexp()` switched
 * from `java.lang.Math.log` (a HotSpot intrinsic, JLS-guaranteed only to <= 1 ulp)
 * to fdlibm, so on a JVM whose `Math.log` differs from `StrictMath.log` the
 * `exp()`/`negexp()` draw shifts by <= 1 ulp — the intended trade-off for
 * byte-identical cross-platform streams (pinned by `PortableMathStrictMathParityTest`).
 */
actual class Random {
    private val jRandom: java.util.Random
    private var nextNextGaussian: Double = 0.0
    private var haveNextNextGaussian: Boolean = false

    actual constructor() {
        jRandom = java.util.Random()
    }

    actual constructor(seed: Long) {
        jRandom = java.util.Random(seed)
    }

    actual fun asKotlinRandom(): KRandom = jRandom.asKotlinRandom()

    actual fun normal(mean: Double, stdDev: Double): Double {
        require(stdDev >= 0.0) { "stdDev must be non-negative, got $stdDev" }
        return mean + stdDev * nextGaussian()
    }

    /**
     * Marsaglia polar method with caching, mirroring `java.util.Random.nextGaussian()`
     * but using [PortableMath.ln] for cross-platform bit-identical results.
     * (`java.util.Random.nextGaussian()` uses `StrictMath.log`, which [PortableMath.ln]
     * matches exactly, so JVM sequences are unchanged.)
     */
    private fun nextGaussian(): Double {
        if (haveNextNextGaussian) {
            haveNextNextGaussian = false
            return nextNextGaussian
        }
        var v1: Double
        var v2: Double
        var s: Double
        do {
            v1 = 2.0 * jRandom.nextDouble() - 1.0
            v2 = 2.0 * jRandom.nextDouble() - 1.0
            s = v1 * v1 + v2 * v2
        } while (s >= 1.0 || s == 0.0)
        val multiplier = sqrt(-2.0 * PortableMath.ln(s) / s)
        nextNextGaussian = v2 * multiplier
        haveNextNextGaussian = true
        return v1 * multiplier
    }

    /** Returns nextDouble(), re-sampling if exactly 0.0 to avoid log(0) = -Infinity. */
    private fun nextDoubleNonZero(): Double {
        var d: Double
        do { d = jRandom.nextDouble() } while (d == 0.0)
        return d
    }

    actual fun negexp(a: Double): Double {
        require(a > 0.0) { "negexp: parameter must be positive, got $a" }
        return -PortableMath.ln(nextDoubleNonZero()) / a
    }

    actual fun exp(a: Double): Double {
        return -a * PortableMath.ln(nextDoubleNonZero())
    }

    actual fun uniform(a: Double, b: Double): Double {
        return a + (b - a) * jRandom.nextDouble()
    }

    actual fun draw(a: Double): Boolean {
        return jRandom.nextDouble() < a
    }

    actual fun randInt(a: Int, b: Int): Int {
        require(a <= b) { "Lower bound a=$a must be <= upper bound b=$b" }
        val range = b.toLong() - a.toLong() + 1L
        require(range <= Int.MAX_VALUE) { "Range [$a, $b] too large (size $range exceeds Int.MAX_VALUE)" }
        return a + jRandom.nextInt(range.toInt())
    }

    actual fun poisson(a: Double): Int {
        val limit = PortableMath.exp(-a)
        var k = 0
        var p = 1.0
        do {
            k++
            p *= jRandom.nextDouble()
        } while (p > limit)
        return k - 1
    }

    actual fun erlang(a: Double, b: Double): Double {
        val bi = b.toInt()
        var sum = 0.0
        for (i in 0 until bi) {
            sum += negexp(1.0 / a)
        }
        return sum
    }
}
