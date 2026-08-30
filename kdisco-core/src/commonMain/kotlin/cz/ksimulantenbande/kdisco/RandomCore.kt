// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlin.math.sqrt
import kotlin.random.Random as KRandom

/**
 * The 48-bit LCG and distribution-sampling logic shared by every platform's [Random] actual.
 *
 * A faithful reimplementation of the `java.util.Random` 48-bit LCG (not a delegate), so state is
 * managed entirely within this class and [captureState]/[restoreState] work without reflection.
 * Living in `commonMain` means the JVM, JS and Native [Random] actuals draw bit-identical raw
 * streams from one implementation instead of three copies of it.
 *
 * Transcendental functions (`ln`, `exp`) go through [PortableMath] instead of `kotlin.math`/
 * `java.lang.Math` so that [exp], [negexp], [normal] and [poisson] draws are bit-identical across
 * JVM, JS and Native (see issue #69). `sqrt` is correctly rounded per IEEE-754 on all platforms
 * and needs no replacement.
 */
internal class RandomCore(seed: Long) {
    private var seed: Long = initialScramble(seed)
    private var nextNextGaussian: Double = 0.0
    private var haveNextNextGaussian: Boolean = false

    val kotlinRandom: KRandom by lazy {
        object : KRandom() {
            override fun nextBits(bitCount: Int): Int = next(bitCount)
        }
    }

    fun normal(mean: Double, stdDev: Double): Double {
        require(stdDev >= 0.0) { "stdDev must be non-negative, got $stdDev" }
        return mean + stdDev * nextGaussian()
    }

    /**
     * Marsaglia polar method with caching, mirroring `java.util.Random.nextGaussian()`
     * but using [PortableMath.ln] for cross-platform bit-identical results.
     * (`java.util.Random.nextGaussian()` uses `StrictMath.log`, which [PortableMath.ln]
     * matches exactly, so JVM sequences are unchanged.)
     */
    @Suppress("MagicNumber")
    private fun nextGaussian(): Double {
        if (haveNextNextGaussian) {
            haveNextNextGaussian = false
            return nextNextGaussian
        }
        var v1: Double
        var v2: Double
        var s: Double
        do {
            v1 = 2.0 * nextDouble() - 1.0
            v2 = 2.0 * nextDouble() - 1.0
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
        do {
            d = nextDouble()
        } while (d == 0.0)
        return d
    }

    @Suppress("MagicNumber")
    private fun next(bits: Int): Int {
        seed = (seed * MULTIPLIER + ADDEND) and MASK
        return (seed ushr (48 - bits)).toInt()
    }

    @Suppress("MagicNumber")
    fun nextDouble(): Double = ((next(26).toLong() shl 27) + next(27)) / (1L shl 53).toDouble()

    @Suppress("MagicNumber")
    private fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive, got $bound" }
        if (bound and -bound == bound) {
            // Power of two: fast path
            return ((bound.toLong() * next(31)) shr 31).toInt()
        }
        var bits: Int
        var value: Int
        do {
            bits = next(31)
            value = bits % bound
        } while (bits - value + (bound - 1) < 0)
        return value
    }

    fun negexp(a: Double): Double {
        require(a > 0.0) { "negexp: parameter must be positive, got $a" }
        return -PortableMath.ln(nextDoubleNonZero()) / a
    }

    fun exp(a: Double): Double = -a * PortableMath.ln(nextDoubleNonZero())

    fun uniform(a: Double, b: Double): Double = a + (b - a) * nextDouble()

    fun draw(a: Double): Boolean = nextDouble() < a

    fun randInt(a: Int, b: Int): Int {
        require(a <= b) { "Lower bound a=$a must be <= upper bound b=$b" }
        val range = b.toLong() - a.toLong() + 1L
        require(range <= Int.MAX_VALUE) { "Range [$a, $b] too large (size $range exceeds Int.MAX_VALUE)" }
        return a + nextInt(range.toInt())
    }

    fun poisson(a: Double): Int {
        val limit = PortableMath.exp(-a)
        var k = 0
        var p = 1.0
        do {
            k++
            p *= nextDouble()
        } while (p > limit)
        return k - 1
    }

    fun erlang(a: Double, b: Double): Double {
        val bi = b.toInt()
        var sum = 0.0
        repeat(bi) {
            sum += negexp(1.0 / a)
        }
        return sum
    }

    /**
     * Captures a complete snapshot of the generator's current internal state,
     * including the LCG seed and any cached Gaussian value (Marsaglia polar method).
     *
     * The returned [RandomState] can be passed to [restoreState] to reset this
     * generator to exactly this point, so that subsequent draws reproduce the
     * same sequence.
     */
    fun captureState(): RandomState = RandomState(
        longArrayOf(
            seed,
            nextNextGaussian.toBits(),
            if (haveNextNextGaussian) 1L else 0L,
        ),
    )

    /**
     * Restores the generator to a previously captured state. After this call,
     * every distribution method will produce the same sequence as it would have
     * produced had the generator never advanced past the captured point.
     */
    fun restoreState(state: RandomState) {
        seed = state.data[0]
        nextNextGaussian = Double.fromBits(state.data[1])
        haveNextNextGaussian = state.data[2] != 0L
    }

    companion object {
        private const val MULTIPLIER = 0x5DEECE66DL
        private const val ADDEND = 0xBL
        private const val MASK = (1L shl 48) - 1
        private fun initialScramble(seed: Long): Long = (seed xor MULTIPLIER) and MASK
        fun defaultSeed(): Long = KRandom.Default.nextLong()
    }
}
