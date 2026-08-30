// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlin.random.Random as KRandom

/**
 * Non-JVM implementation of [Random], delegating to the shared [RandomCore] so that
 * seeded sequences are deterministic and **match JVM output** across platforms.
 */
actual class Random {
    private val core: RandomCore

    actual constructor() : this(RandomCore.defaultSeed())

    actual constructor(seed: Long) {
        core = RandomCore(seed)
    }

    actual fun asKotlinRandom(): KRandom = core.kotlinRandom

    actual fun normal(mean: Double, stdDev: Double): Double = core.normal(mean, stdDev)

    actual fun negexp(a: Double): Double = core.negexp(a)

    actual fun exp(a: Double): Double = core.exp(a)

    actual fun uniform(a: Double, b: Double): Double = core.uniform(a, b)

    actual fun draw(a: Double): Boolean = core.draw(a)

    actual fun randInt(a: Int, b: Int): Int = core.randInt(a, b)

    actual fun poisson(a: Double): Int = core.poisson(a)

    actual fun erlang(a: Double, b: Double): Double = core.erlang(a, b)

    actual fun captureState(): RandomState = core.captureState()

    actual fun restoreState(state: RandomState) = core.restoreState(state)
}
