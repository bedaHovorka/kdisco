// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlin.math.sqrt
import kotlin.random.Random as KRandom

/**
 * JVM implementation of [Random] using the same 48-bit LCG as `java.util.Random`
 * so that seeded sequences are deterministic and **match jDisco output**, including:
 * - `normal()` → Marsaglia polar method with caching (identical to `java.util.Random.nextGaussian()`)
 * - `exp()` → `-a * ln(u)` (matching jDisco exactly)
 * - `negexp()` → `-ln(u) / a` (matching jDisco exactly)
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
 *
 * State is managed entirely within this class (a faithful reimplementation of the
 * `java.util.Random` 48-bit LCG, so sequences are unchanged), which enables
 * [captureState]/[restoreState] without reflection.
 */
actual class Random {
	private var seed: Long
	private var nextNextGaussian: Double = 0.0
	private var haveNextNextGaussian: Boolean = false

	actual constructor() : this(defaultSeed())

	actual constructor(seed: Long) {
		this.seed = initialScramble(seed)
	}

	actual fun asKotlinRandom(): KRandom = kotlinRandom

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
		do { d = nextDouble() } while (d == 0.0)
		return d
	}

	private fun next(bits: Int): Int {
		seed = (seed * MULTIPLIER + ADDEND) and MASK
		return (seed ushr (48 - bits)).toInt()
	}

	private fun nextDouble(): Double {
		return ((next(26).toLong() shl 27) + next(27)) / (1L shl 53).toDouble()
	}

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

	actual fun negexp(a: Double): Double {
		require(a > 0.0) { "negexp: parameter must be positive, got $a" }
		return -PortableMath.ln(nextDoubleNonZero()) / a
	}

	actual fun exp(a: Double): Double {
		return -a * PortableMath.ln(nextDoubleNonZero())
	}

	actual fun uniform(a: Double, b: Double): Double {
		return a + (b - a) * nextDouble()
	}

	actual fun draw(a: Double): Boolean {
		return nextDouble() < a
	}

	actual fun randInt(a: Int, b: Int): Int {
		require(a <= b) { "Lower bound a=$a must be <= upper bound b=$b" }
		val range = b.toLong() - a.toLong() + 1L
		require(range <= Int.MAX_VALUE) { "Range [$a, $b] too large (size $range exceeds Int.MAX_VALUE)" }
		return a + nextInt(range.toInt())
	}

	actual fun poisson(a: Double): Int {
		val limit = PortableMath.exp(-a)
		var k = 0
		var p = 1.0
		do {
			k++
			p *= nextDouble()
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

	actual fun captureState(): RandomState = RandomState(
		longArrayOf(
			seed,
			nextNextGaussian.toBits(),
			if (haveNextNextGaussian) 1L else 0L,
		)
	)

	actual fun restoreState(state: RandomState) {
		seed = state.data[0]
		nextNextGaussian = Double.fromBits(state.data[1])
		haveNextNextGaussian = state.data[2] != 0L
	}

	private val kotlinRandom: KRandom by lazy {
		object : KRandom() {
			override fun nextBits(bitCount: Int): Int = next(bitCount)
		}
	}

	private companion object {
		private const val MULTIPLIER = 0x5DEECE66DL
		private const val ADDEND = 0xBL
		private const val MASK = (1L shl 48) - 1
		private fun initialScramble(seed: Long): Long = (seed xor MULTIPLIER) and MASK
		private fun defaultSeed(): Long = KRandom.Default.nextLong()
	}
}

