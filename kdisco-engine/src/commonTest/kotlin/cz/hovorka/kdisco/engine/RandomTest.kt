package cz.hovorka.kdisco.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RandomTest {

    @Test
    fun seededRandomIsReproducible() {
        val r1 = Random(42L)
        val r2 = Random(42L)
        repeat(100) {
            assertEquals(r1.uniform(0.0, 1.0), r2.uniform(0.0, 1.0))
        }
    }

    @Test
    fun uniformReturnsInRange() {
        val r = Random(1L)
        repeat(1000) {
            val v = r.uniform(5.0, 10.0)
            assertTrue(v >= 5.0 && v < 10.0, "uniform($v) out of range")
        }
    }

    @Test
    fun drawReturnsBoolean() {
        val r = Random(1L)
        var trueCount = 0
        val n = 10000
        repeat(n) { if (r.draw(0.5)) trueCount++ }
        val ratio = trueCount.toDouble() / n
        assertTrue(abs(ratio - 0.5) < 0.05, "draw(0.5) ratio was $ratio")
    }

    @Test
    fun randIntReturnsInRange() {
        val r = Random(1L)
        repeat(1000) {
            val v = r.randInt(3, 7)
            assertTrue(v in 3..7, "randInt($v) out of range")
        }
    }

    @Test
    fun negexpReturnsPositive() {
        val r = Random(1L)
        repeat(1000) {
            assertTrue(r.negexp(1.0) >= 0.0)
        }
    }

    @Test
    fun normalDistributionMean() {
        val r = Random(1L)
        val mean = 10.0
        val samples = DoubleArray(10000) { r.normal(mean, 1.0) }
        val sampleMean = samples.average()
        assertTrue(abs(sampleMean - mean) < 0.1, "normal mean was $sampleMean, expected ~$mean")
    }
}
