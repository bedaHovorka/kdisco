// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlin.math.abs
import kotlin.test.Test

class RandomTest {

    @Test
    fun seededRandomIsReproducible() {
        val r1 = Random(42L)
        val r2 = Random(42L)
        repeat(100) {
            assertThat(r2.uniform(0.0, 1.0)).isEqualTo(r1.uniform(0.0, 1.0))
        }
    }

    @Test
    fun uniformReturnsInRange() {
        val r = Random(1L)
        repeat(1000) {
            val v = r.uniform(5.0, 10.0)
            assertThat(v).isBetween(5.0, 10.0)
        }
    }

    @Test
    fun drawReturnsBoolean() {
        val r = Random(1L)
        var trueCount = 0
        val n = 10000
        repeat(n) { if (r.draw(0.5)) trueCount++ }
        val ratio = trueCount.toDouble() / n
        assertThat(ratio).isBetween(0.45, 0.55)
    }

    @Test
    fun randIntReturnsInRange() {
        val r = Random(1L)
        repeat(1000) {
            val v = r.randInt(3, 7)
            assertThat(v).isBetween(3, 7)
        }
    }

    @Test
    fun randIntWithPowerOfTwoRangeReturnsInRange() {
        val r = Random(1L)
        repeat(1000) {
            // [0, 3] is a range of size 4 — exercises nextInt's power-of-two fast path.
            val v = r.randInt(0, 3)
            assertThat(v).isBetween(0, 3)
        }
    }

    @Test
    fun asKotlinRandomProducesDeterministicSequenceFromTheSameSeed() {
        val r1 = Random(7L).asKotlinRandom()
        val r2 = Random(7L).asKotlinRandom()
        repeat(20) {
            assertThat(r2.nextInt()).isEqualTo(r1.nextInt())
        }
    }

    @Test
    fun negexpReturnsPositive() {
        val r = Random(1L)
        repeat(1000) {
            assertThat(r.negexp(1.0)).isGreaterThanOrEqualTo(0.0)
        }
    }

    @Test
    fun normalDistributionMean() {
        val r = Random(1L)
        val mean = 10.0
        val samples = DoubleArray(10000) { r.normal(mean, 1.0) }
        val sampleMean = samples.average()
        assertThat(sampleMean).isBetween(mean - 0.1, mean + 0.1)
    }

    // --- captureState / restoreState tests ---

    private fun drawN(r: Random, n: Int): List<Double> = List(n) { r.uniform(0.0, 1.0) }

    @Test
    fun captureAndRestoreReproducesUniform() {
        val r = Random(99L)
        r.uniform(0.0, 1.0) // advance a bit
        val state = r.captureState()
        val first = drawN(r, 20)
        r.restoreState(state)
        val second = drawN(r, 20)
        assertThat(second).isEqualTo(first)
    }

    @Test
    fun captureAndRestoreReproducesAllDistributions() {
        val r = Random(7L)
        // Warm up so the state isn't at position zero
        repeat(5) { r.uniform(0.0, 1.0) }

        val state = r.captureState()

        fun collectSamples(): List<Double> {
            val out = mutableListOf<Double>()
            repeat(5) { out += r.normal(0.0, 1.0) }
            repeat(5) { out += r.negexp(2.0) }
            repeat(5) { out += r.exp(3.0) }
            repeat(5) { out += r.uniform(1.0, 5.0) }
            repeat(5) { out += if (r.draw(0.4)) 1.0 else 0.0 }
            repeat(5) { out += r.randInt(0, 10).toDouble() }
            repeat(5) { out += r.poisson(4.0).toDouble() }
            repeat(5) { out += r.erlang(2.0, 3.0) }
            return out
        }

        val first = collectSamples()
        r.restoreState(state)
        val second = collectSamples()

        assertThat(second).isEqualTo(first)
    }

    /**
     * The Marsaglia polar method generates two Gaussians per iteration and caches
     * the second one. A naive seed-only capture would miss the cache, causing the
     * very next [Random.normal] call after restore to return the cached value instead
     * of re-generating from the restored seed.
     *
     * This test exercises exactly that edge case: capture state *after* the first
     * Gaussian of a pair has been consumed (so the cache is populated), then verify
     * that restoring and drawing again reproduces the cached value correctly.
     */
    @Test
    fun captureAndRestoreIncludesGaussianCache() {
        val r = Random(1234L)
        // Consume the first normal of a pair; the second is now in the cache.
        r.normal(0.0, 1.0)

        val state = r.captureState()
        val fromCache = r.normal(0.0, 1.0) // returns cached value, clears cache
        val afterCache = r.normal(0.0, 1.0) // generates fresh pair

        r.restoreState(state)
        assertThat(r.normal(0.0, 1.0)).isEqualTo(fromCache) // must match cached value
        assertThat(r.normal(0.0, 1.0)).isEqualTo(afterCache) // must match next generated
    }

    @Test
    fun captureStateDoesNotMutateGenerator() {
        val r = Random(55L)
        repeat(10) { r.uniform(0.0, 1.0) }
        val before = drawN(r, 5)
        val r2 = Random(55L)
        repeat(10) { r2.uniform(0.0, 1.0) }
        r2.captureState() // capturing must not advance the generator
        val after = drawN(r2, 5)
        assertThat(after).isEqualTo(before)
    }

    @Test
    fun randomStateEqualityIsStructural() {
        val r1 = Random(9L)
        val r2 = Random(9L)
        r1.uniform(0.0, 1.0)
        r2.uniform(0.0, 1.0)

        val state1 = r1.captureState()
        val state2 = r2.captureState()

        assertThat(state1).isEqualTo(state2)
        assertThat(state1.hashCode()).isEqualTo(state2.hashCode())
        assertThat(state1).isNotEqualTo(Random(10L).captureState())
        assertThat(state1.equals("not a RandomState")).isFalse()
    }
}
