// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test

/**
 * Universal KMP tests pinning seeded [Random] distribution draws to exact bit
 * patterns on **every** platform (JVM, JS, Native).
 *
 * The golden values were generated on the JVM (java.util.Random LCG stream +
 * StrictMath/fdlibm transcendentals). A pass on all targets proves that
 * `exp()`, `negexp()`, `normal()`, `poisson()` and `erlang()` produce
 * byte-identical sequences for the same seed cross-platform — the exact
 * divergence reported in issue #69 (JVM vs Native `.exp()`/`.normal()` draws
 * diverging via `kotlin.math.ln`/`exp` platform delegation).
 */
class RandomCrossPlatformDeterminismTest {

    private fun bits(x: Double): ULong = x.toRawBits().toULong()

    @Test
    fun expSequenceIsBitIdenticalAcrossPlatforms() {
        // Reproduces the issue #69 scenario: Random(0).exp(43.0), the
        // interlockSim ShuntingLoop inter-arrival pacing draw.
        val expected = listOf(
            0x402AF380F14DFE1EUL,
            0x404EA28F730C8137UL,
            0x40335D3D13493DCCUL,
            0x4039AC3F28F0DBC4UL,
            0x4036244BFC02E0CAUL,
            0x4047A0A8FFB77551UL,
            0x404482EE0794BFC2UL,
            0x3FE5048AE7E82F45UL,
            0x401625AE8E3FBADDUL,
            0x4004D40B320FD025UL,
        )
        val r = Random(0L)
        for (e in expected) {
            assertThat(bits(r.exp(43.0))).isEqualTo(e)
        }
    }

    @Test
    fun negexpSequenceIsBitIdenticalAcrossPlatforms() {
        val expected = listOf(
            0x3FC048CB4BD92C26UL,
            0x3FC380F7FD2E4897UL,
            0x3FDE1697A7418700UL,
            0x3FE06D9FA1903812UL,
            0x3FC4D88181110402UL,
            0x3FA4CFD8081A308EUL,
            0x3FD98986590FCD96UL,
            0x3FE07D65353F5361UL,
            0x3FD3AD365D93C9C4UL,
            0x3FB90FEC3F078456UL,
        )
        val r = Random(42L)
        for (e in expected) {
            assertThat(bits(r.negexp(2.5))).isEqualTo(e)
        }
    }

    @Test
    fun normalSequenceIsBitIdenticalAcrossPlatforms() {
        // Exercises both the fresh Marsaglia-polar draw and the cached
        // nextNextGaussian path on alternating calls.
        val expected = listOf(
            0x401C7EE000F9A2A2UL,
            0x4026896A6F0CB089UL,
            0x4024E77CCCDFEDA6UL,
            0x40251C1E7C962DA9UL,
            0x4024BCBE292459A8UL,
            0x40228A056647D000UL,
            0x4029687A986C46CBUL,
            0x40256FDB9F1A0F2EUL,
            0x40232DCB3FF8777FUL,
            0x402811EA4AF3702EUL,
        )
        val r = Random(123L)
        for (e in expected) {
            assertThat(bits(r.normal(10.0, 2.0))).isEqualTo(e)
        }
    }

    @Test
    fun poissonSequenceIsIdenticalAcrossPlatforms() {
        // poisson() computes its acceptance limit via exp(-a); a 1-ulp
        // cross-platform difference in that limit can change the drawn count.
        val expected = listOf(6, 6, 4, 4, 7, 5, 7, 4, 4, 4, 8, 5, 5, 7, 3, 5, 4, 5, 2, 5)
        val r = Random(7L)
        for (e in expected) {
            assertThat(r.poisson(4.2)).isEqualTo(e)
        }
    }

    @Test
    fun erlangSequenceIsBitIdenticalAcrossPlatforms() {
        val expected = listOf(
            0x400962753E098358UL,
            0x400C27A221D9BAFBUL,
            0x4013DEFFBB8D9676UL,
            0x3FFAB99189128783UL,
            0x4013452D765AE506UL,
            0x401692E8F8E810E6UL,
            0x4013B1C5DF9A2851UL,
            0x400B6DFA0A68D604UL,
            0x400FADF4592502CCUL,
            0x401C050C14859392UL,
        )
        val r = Random(99L)
        for (e in expected) {
            assertThat(bits(r.erlang(2.0, 3.0))).isEqualTo(e)
        }
    }

    @Test
    fun longMixedDrawSequenceChecksumIsIdenticalAcrossPlatforms() {
        // 10 000 interleaved exp/normal/negexp draws hashed together — a strong
        // end-to-end guarantee that the full sampled stream (LCG + transcendental
        // layers, incl. gaussian caching) is bit-identical on every platform.
        val r = Random(2026L)
        var h = 0L
        repeat(10_000) { i ->
            val v = when (i % 3) {
                0 -> r.exp(43.0)
                1 -> r.normal(5.0, 1.5)
                else -> r.negexp(0.25)
            }
            h = h * 31 + v.toRawBits()
        }
        assertThat(h.toULong()).isEqualTo(0xD5CA5F350B2EC464UL)
    }

    @Test
    fun sameSeedSameSequenceForAllDistributions() {
        val r1 = Random(555L)
        val r2 = Random(555L)
        repeat(1000) {
            assertThat(bits(r2.exp(43.0))).isEqualTo(bits(r1.exp(43.0)))
            assertThat(bits(r2.normal(0.0, 1.0))).isEqualTo(bits(r1.normal(0.0, 1.0)))
            assertThat(bits(r2.negexp(1.0))).isEqualTo(bits(r1.negexp(1.0)))
            assertThat(bits(r2.erlang(1.0, 2.0))).isEqualTo(bits(r1.erlang(1.0, 2.0)))
            assertThat(r2.poisson(3.0)).isEqualTo(r1.poisson(3.0))
            assertThat(r2.randInt(0, 100)).isEqualTo(r1.randInt(0, 100))
            assertThat(r2.draw(0.5)).isEqualTo(r1.draw(0.5))
        }
    }
}
