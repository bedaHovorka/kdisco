package cz.hovorka.kdisco.engine

import kotlin.random.Random as KRandom
import kotlin.random.asKotlinRandom

/**
 * JVM implementation of [Random] backed by [java.util.Random].
 *
 * This ensures identical random sequences as jDisco's `Random` class
 * (which extends `java.util.Random`), including:
 * - `normal()` → delegates to `nextGaussian()` (Marsaglia polar method with caching)
 * - `exp()` → `-a * Math.log(nextDouble())` (matching jDisco exactly)
 * - `negexp()` → `-Math.log(nextDouble()) / a` (matching jDisco exactly)
 */
actual class Random {
	private val jRandom: java.util.Random

	actual constructor() {
		jRandom = java.util.Random()
	}

	actual constructor(seed: Long) {
		jRandom = java.util.Random(seed)
	}

	actual fun asKotlinRandom(): KRandom = jRandom.asKotlinRandom()

	actual fun normal(mean: Double, stdDev: Double): Double {
		require(stdDev >= 0.0) { "stdDev must be non-negative, got $stdDev" }
		return mean + stdDev * jRandom.nextGaussian()
	}

	/** Returns nextDouble(), re-sampling if exactly 0.0 to avoid log(0) = -Infinity. */
	private fun nextDoubleNonZero(): Double {
		var d: Double
		do { d = jRandom.nextDouble() } while (d == 0.0)
		return d
	}

	actual fun negexp(a: Double): Double {
		require(a > 0.0) { "negexp: parameter must be positive, got $a" }
		return -Math.log(nextDoubleNonZero()) / a
	}

	actual fun exp(a: Double): Double {
		return -a * Math.log(nextDoubleNonZero())
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
		val limit = kotlin.math.exp(-a)
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
