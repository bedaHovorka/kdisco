package cz.hovorka.kdisco.engine

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random as KRandom

/**
 * Non-JVM implementation of [Random] using pure Kotlin.
 * Uses the Marsaglia polar method with caching for [normal] to match
 * the JVM's `java.util.Random.nextGaussian()` algorithm.
 */
actual class Random {
	private val rng: KRandom
	private var nextNextGaussian: Double = 0.0
	private var haveNextNextGaussian: Boolean = false

	actual constructor() {
		rng = KRandom.Default
	}

	actual constructor(seed: Long) {
		rng = KRandom(seed)
	}

	actual fun asKotlinRandom(): KRandom = rng

	actual fun normal(mean: Double, stdDev: Double): Double {
		require(stdDev >= 0.0) { "stdDev must be non-negative, got $stdDev" }
		return mean + stdDev * nextGaussian()
	}

	private fun nextGaussian(): Double {
		if (haveNextNextGaussian) {
			haveNextNextGaussian = false
			return nextNextGaussian
		}
		var v1: Double
		var v2: Double
		var s: Double
		do {
			v1 = 2.0 * rng.nextDouble() - 1.0
			v2 = 2.0 * rng.nextDouble() - 1.0
			s = v1 * v1 + v2 * v2
		} while (s >= 1.0 || s == 0.0)
		val multiplier = sqrt(-2.0 * ln(s) / s)
		nextNextGaussian = v2 * multiplier
		haveNextNextGaussian = true
		return v1 * multiplier
	}

	/** Returns nextDouble(), re-sampling if exactly 0.0 to avoid log(0) = -Infinity. */
	private fun nextDoubleNonZero(): Double {
		var d: Double
		do { d = rng.nextDouble() } while (d == 0.0)
		return d
	}

	actual fun negexp(a: Double): Double {
		require(a > 0.0) { "negexp: parameter must be positive, got $a" }
		return -ln(nextDoubleNonZero()) / a
	}

	actual fun exp(a: Double): Double {
		return -a * ln(nextDoubleNonZero())
	}

	actual fun uniform(a: Double, b: Double): Double {
		return a + (b - a) * rng.nextDouble()
	}

	actual fun draw(a: Double): Boolean {
		return rng.nextDouble() < a
	}

	actual fun randInt(a: Int, b: Int): Int {
		require(a <= b) { "Lower bound a=$a must be <= upper bound b=$b" }
		return rng.nextLong(a.toLong(), b.toLong() + 1L).toInt()
	}

	actual fun poisson(a: Double): Int {
		val limit = kotlin.math.exp(-a)
		var k = 0
		var p = 1.0
		do {
			k++
			p *= rng.nextDouble()
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
