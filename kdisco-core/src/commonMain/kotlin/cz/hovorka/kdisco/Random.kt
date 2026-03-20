package cz.hovorka.kdisco

import kotlin.random.Random as KRandom

/**
 * Random number generator with statistical distribution sampling methods.
 *
 * On JVM, delegates to `java.util.Random` to ensure identical random sequences
 * as jDisco's `Random` class (which extends `java.util.Random`). The `normal()`
 * method uses `nextGaussian()` (Marsaglia polar method with caching), and `exp()`
 * and `negexp()` use the same formulas as jDisco.
 *
 * On other platforms, uses a pure-Kotlin implementation with algorithm parity.
 *
 * Use [Random(seed)] for reproducible simulations. Use [Random()] for
 * non-deterministic runs.
 */
expect class Random {
	constructor()
	constructor(seed: Long)

	/**
	 * Returns the underlying [kotlin.random.Random] instance.
	 * Useful for operations like `MutableList.shuffle(random.asKotlinRandom())`.
	 */
	fun asKotlinRandom(): KRandom

	/** Normally distributed double with given [mean] and standard deviation [stdDev]. */
	fun normal(mean: Double, stdDev: Double): Double

	/** Negative exponential distribution with mean 1/[a]. */
	fun negexp(a: Double): Double

	/** Exponential distribution with mean [a]. */
	fun exp(a: Double): Double

	/** Uniformly distributed double in [[a], [b]). */
	fun uniform(a: Double, b: Double): Double

	/** Returns true with probability [a]. */
	fun draw(a: Double): Boolean

	/** Uniformly distributed integer in [[a], [b]] (inclusive). */
	fun randInt(a: Int, b: Int): Int

	/** Poisson distributed integer with mean [a]. */
	fun poisson(a: Double): Int

	/** Erlang distributed double with shape [b] and mean [a]*[b]. */
	fun erlang(a: Double, b: Double): Double
}
