package cz.hovorka.kdisco.engine

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
}
