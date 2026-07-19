// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlin.random.Random as KRandom

/**
 * Random number generator with statistical distribution sampling methods.
 *
 * On JVM, delegates to `java.util.Random` to ensure identical random sequences
 * as jDisco's `Random` class (which extends `java.util.Random`). The `normal()`
 * method uses the Marsaglia polar method with caching (mirroring `nextGaussian()`),
 * and `exp()` and `negexp()` use the same formulas as jDisco.
 *
 * On other platforms, uses a pure-Kotlin implementation with algorithm parity.
 *
 * **Cross-platform determinism**: for the same seed, all distribution draws
 * ([exp], [negexp], [normal], [poisson], [erlang], [uniform], [draw], [randInt])
 * produce bit-identical sequences on JVM, JS and Native. The raw stream uses the
 * same 48-bit LCG everywhere, and the transcendental functions (`ln`, `exp`) go
 * through [PortableMath] (a pure-Kotlin fdlibm port) instead of `kotlin.math`,
 * whose implementations differ per platform at the last bit (see issue #69).
 *
 * **Thread safety**: not thread-safe. A [Random] instance is confined to its
 * [Simulation]'s single-threaded dispatcher — [Simulation] runs on
 * `Dispatchers.Unconfined` with a thread-local-confined `SimulationContext` and
 * owns exactly one [Random]. Do not share a single instance across threads or
 * coroutines; if you must use a multi-threaded dispatcher, give each thread its
 * own [Random]. (This mirrors the pre-PR JVM behavior: `java.util.Random`'s
 * per-call synchronization never protected the multi-call `normal`/`poisson`/
 * `erlang` sequences anyway.)
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
