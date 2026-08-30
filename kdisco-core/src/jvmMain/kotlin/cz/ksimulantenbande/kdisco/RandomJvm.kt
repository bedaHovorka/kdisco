// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlin.random.Random as KRandom

/**
 * JVM implementation of [Random], delegating to the shared [RandomCore].
 *
 * This preserves the random sequences of jDisco's `Random` class
 * (which extends `java.util.Random`), including:
 * - `normal()` → Marsaglia polar method with caching (mirroring `nextGaussian()`)
 * - `exp()` → `-a * ln(nextDouble())`
 * - `negexp()` → `-ln(nextDouble()) / a`
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
