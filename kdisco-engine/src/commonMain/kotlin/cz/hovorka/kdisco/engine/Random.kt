package cz.hovorka.kdisco.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random as KRandom

/**
 * Random number generator with statistical distribution sampling methods.
 * Pure Kotlin — works on all KMP platforms.
 *
 * Use [Random(seed)] for reproducible simulations. Use [Random()] for
 * non-deterministic runs.
 */
class Random {
    private val rng: KRandom

    constructor() {
        rng = KRandom.Default
    }

    constructor(seed: Long) {
        rng = KRandom(seed)
    }

    /** Normally distributed double with given [mean] and standard deviation [stdDev]. */
    fun normal(mean: Double, stdDev: Double): Double {
        require(stdDev >= 0.0) { "stdDev must be non-negative, got $stdDev" }
        // Box-Muller transform
        var u1 = rng.nextDouble()
        while (u1 == 0.0) u1 = rng.nextDouble()   // guard ln(0)
        val u2 = rng.nextDouble()
        val z = sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
        return mean + stdDev * z
    }

    /** Negative exponential distribution with mean 1/[a]. */
    fun negexp(a: Double): Double {
        return -ln(1.0 - rng.nextDouble()) / a
    }

    /** Exponential distribution with mean [a]. */
    fun exp(a: Double): Double {
        return -a * ln(1.0 - rng.nextDouble())
    }

    /** Uniformly distributed double in [[a], [b]). */
    fun uniform(a: Double, b: Double): Double {
        return a + (b - a) * rng.nextDouble()
    }

    /** Returns true with probability [a]. */
    fun draw(a: Double): Boolean {
        return rng.nextDouble() < a
    }

    /** Uniformly distributed integer in [[a], [b]] (inclusive). */
    fun randInt(a: Int, b: Int): Int {
        require(a <= b) { "Lower bound a=$a must be <= upper bound b=$b" }
        return rng.nextLong(a.toLong(), b.toLong() + 1L).toInt()
    }

    /** Poisson distributed integer with mean [a]. */
    fun poisson(a: Double): Int {
        // Uses kotlin.math.exp (e^x), NOT this.exp() which is a distribution method
        val limit = kotlin.math.exp(-a)
        var k = 0
        var p = 1.0
        do {
            k++
            p *= rng.nextDouble()
        } while (p > limit)
        return k - 1
    }

    /** Erlang distributed double with shape [b] and mean [a]*[b]. */
    fun erlang(a: Double, b: Double): Double {
        val bi = b.toInt()
        var sum = 0.0
        for (i in 0 until bi) {
            sum += negexp(1.0 / a)
        }
        return sum
    }
}
