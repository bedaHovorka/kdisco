package cz.hovorka.kdisco

/**
 * A random number generator with statistical distribution sampling methods.
 *
 * Extends the platform's base random number generator with methods for common
 * probability distributions used in discrete-event simulation.
 *
 * ### Example
 * ```kotlin
 * val rng = Random(42L)  // Seeded for reproducibility
 * val serviceTime = rng.negexp(0.5)  // Exponential with mean 2.0
 * val arrivalDelay = rng.normal(10.0, 2.0)  // Normal distribution
 * ```
 *
 * @since 0.2.0
 */
expect class Random {
    /** Creates a Random instance seeded with current time. */
    constructor()

    /** Creates a Random instance with the given seed for reproducible results. */
    constructor(seed: Long)

    /** Returns a normally distributed double with given mean and standard deviation. */
    fun normal(a: Double, b: Double): Double

    /** Returns a double from the negative exponential distribution with mean 1/a. */
    fun negexp(a: Double): Double

    /** Returns a double from the exponential distribution with mean a. */
    fun exp(a: Double): Double

    /** Returns a double uniformly distributed in [a, b). */
    fun uniform(a: Double, b: Double): Double

    /** Returns true with probability a. */
    fun draw(a: Double): Boolean

    /** Returns a uniformly distributed integer in [a, b]. */
    fun randInt(a: Int, b: Int): Int

    /** Returns an integer from the Poisson distribution with mean a. */
    fun poisson(a: Double): Int

    /** Returns a double from the Erlang distribution. */
    fun erlang(a: Double, b: Double): Double
}
