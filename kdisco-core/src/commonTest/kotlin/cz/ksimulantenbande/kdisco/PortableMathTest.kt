// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isLessThan
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import kotlin.math.abs
import kotlin.math.ulp
import kotlin.test.Test

/**
 * Universal KMP tests pinning [PortableMath.ln] / [PortableMath.exp] to exact
 * fdlibm bit patterns on **every** platform (JVM, JS, Native).
 *
 * The golden values were generated on the JVM with `StrictMath.log` /
 * `StrictMath.exp` (the fdlibm reference). Because these tests run unchanged on
 * all targets, a pass proves the functions are bit-identical cross-platform —
 * closing the `kotlin.math.ln`/`exp` platform-delegation gap from issue #69.
 */
class PortableMathTest {

	private fun bits(x: Double): ULong = x.toRawBits().toULong()

	@Test
	fun lnMatchesFdlibmBitPatternsOnAllPlatforms() {
		val golden = listOf(
			0x3FE0000000000000UL to 0xBFE62E42FEFA39EFUL, // ln(0.5)
			0x3FF8000000000000UL to 0x3FD9F323ECBF984CUL, // ln(1.5)
			0x4000000000000000UL to 0x3FE62E42FEFA39EFUL, // ln(2.0)
			0x4005BF0A8B145769UL to 0x3FF0000000000000UL, // ln(e)
			0x400921FB54442D18UL to 0x3FF250D048E7A1BDUL, // ln(pi)
			0x4024000000000000UL to 0x40026BB1BBB55516UL, // ln(10.0)
			0x3DDB7CDFD9D7BDBBUL to 0xC037069E2AA2AA5BUL, // ln(1.0E-10)
			0x4202A05F20000000UL to 0x4037069E2AA2AA5BUL, // ln(1.0E10)
			0x3FEFFFFFFFFFFFFFUL to 0xBCA0000000000000UL, // ln(0.9999999999999999)
			0x3FF0000000000001UL to 0x3CAFFFFFFFFFFFFFUL, // ln(1.0000000000000002)
			0x3F7D7DBF487FCB92UL to 0xC013BC151A7652ECUL, // ln(0.0072)
			0x40FE240C9FBE76C9UL to 0x40277281CAD8A844UL, // ln(123456.789)
			0x0000000000000001UL to 0xC0874385446D71C3UL, // ln(Double.MIN_VALUE) — subnormal
			0x7FEFFFFFFFFFFFFFUL to 0x40862E42FEFA39EFUL, // ln(Double.MAX_VALUE)
		)
		for ((inBits, outBits) in golden) {
			val x = Double.fromBits(inBits.toLong())
			assertThat(bits(PortableMath.ln(x))).isEqualTo(outBits)
		}
	}

	@Test
	fun expMatchesFdlibmBitPatternsOnAllPlatforms() {
		val golden = listOf(
			0xBFF0000000000000UL to 0x3FD78B56362CEF38UL, // exp(-1.0)
			0x3FF0000000000000UL to 0x4005BF0A8B14576AUL, // exp(1.0)
			0x3FE0000000000000UL to 0x3FFA61298E1E069CUL, // exp(0.5)
			0xBFE0000000000000UL to 0x3FE368B2FC6F960AUL, // exp(-0.5)
			0x4000000000000000UL to 0x401D8E64B8D4DDAEUL, // exp(2.0)
			0x4024000000000000UL to 0x40D5829DCF950560UL, // exp(10.0)
			0xC024000000000000UL to 0x3F07CD79B5647C9AUL, // exp(-10.0)
			0x4059000000000000UL to 0x48F3494A9B171BF5UL, // exp(100.0)
			0xC059000000000000UL to 0x36EA8C1F14E2AF5DUL, // exp(-100.0)
			0x4085E00000000000UL to 0x7F0D945DF4F8EC8EUL, // exp(700.0)
			0xC085E00000000000UL to 0x00D14F2B0FB9307FUL, // exp(-700.0) — subnormal-adjacent
			0x3E112E0BE826D695UL to 0x3FF000000044B830UL, // exp(1.0E-9)
			0xBE112E0BE826D695UL to 0x3FEFFFFFFF768FA1UL, // exp(-1.0E-9)
			0x3FE62E42FEFA39EFUL to 0x4000000000000000UL, // exp(ln 2) == 2.0 exactly
			0x4045800000000000UL to 0x43D0672A3C9EB871UL, // exp(43.0)
			0xC045800000000000UL to 0x3C0F36BD37F42F3EUL, // exp(-43.0)
		)
		for ((inBits, outBits) in golden) {
			val x = Double.fromBits(inBits.toLong())
			assertThat(bits(PortableMath.exp(x))).isEqualTo(outBits)
		}
	}

	@Test
	fun lnSpecialCases() {
		assertThat(PortableMath.ln(0.0)).isEqualTo(Double.NEGATIVE_INFINITY)
		assertThat(PortableMath.ln(-0.0)).isEqualTo(Double.NEGATIVE_INFINITY)
		assertThat(PortableMath.ln(1.0)).isEqualTo(0.0)
		assertThat(PortableMath.ln(Double.POSITIVE_INFINITY)).isEqualTo(Double.POSITIVE_INFINITY)
		assertThat(PortableMath.ln(-1.0).isNaN()).isTrue()
		assertThat(PortableMath.ln(Double.NEGATIVE_INFINITY).isNaN()).isTrue()
		assertThat(PortableMath.ln(Double.NaN).isNaN()).isTrue()
	}

	@Test
	fun expSpecialCases() {
		assertThat(PortableMath.exp(0.0)).isEqualTo(1.0)
		assertThat(PortableMath.exp(-0.0)).isEqualTo(1.0)
		assertThat(PortableMath.exp(Double.POSITIVE_INFINITY)).isEqualTo(Double.POSITIVE_INFINITY)
		assertThat(PortableMath.exp(Double.NEGATIVE_INFINITY)).isEqualTo(0.0)
		assertThat(PortableMath.exp(Double.NaN).isNaN()).isTrue()
		// overflow / underflow thresholds
		assertThat(PortableMath.exp(710.0)).isEqualTo(Double.POSITIVE_INFINITY)
		assertThat(PortableMath.exp(-746.0)).isEqualTo(0.0)
	}

	@Test
	fun lnAgreesWithPlatformMathWithinOneUlp() {
		// The port must still be an accurate natural log (≤ 1 ulp of the platform
		// implementation, which is itself ≤ 1 ulp of the exact value). Compared as a
		// value-based |actual - expected| <= expected.ulp check rather than a raw-bits
		// diff, so the metric stays valid across sign changes and the subnormal/
		// normal boundary. Inputs stay finite and normal-magnitude (ln of
		// [1.0e-300, 1.0e300] -> [-690, 690]), so expected.ulp is well defined.
		val r = Random(2024L)
		repeat(10_000) {
			val x = r.uniform(1.0e-300, 1.0) * r.uniform(1.0, 1.0e300)
			val expected = kotlin.math.ln(x)
			val actual = PortableMath.ln(x)
			assertThat(abs(actual - expected)).isLessThanOrEqualTo(expected.ulp)
		}
	}

	@Test
	fun expAgreesWithPlatformMathWithinOneUlp() {
		// Same value-based ≤ 1-ulp check as the ln test above. exp([-700, 700])
		// yields finite, normal-magnitude results, so expected.ulp is well defined.
		val r = Random(2025L)
		repeat(10_000) {
			val x = r.uniform(-700.0, 700.0)
			val expected = kotlin.math.exp(x)
			val actual = PortableMath.exp(x)
			assertThat(abs(actual - expected)).isLessThanOrEqualTo(expected.ulp)
		}
	}

	@Test
	fun lnExpRoundTripIsAccurate() {
		val r = Random(31337L)
		repeat(10_000) {
			val x = r.uniform(-500.0, 500.0)
			val roundTrip = PortableMath.ln(PortableMath.exp(x))
			assertThat(abs(roundTrip - x)).isLessThan(1.0e-12 * maxOf(1.0, abs(x)))
		}
	}

	@Test
	fun lnIsMonotonicAcrossReductionBoundaries() {
		// sqrt(2)/2 and sqrt(2) are fdlibm's argument-reduction breakpoints
		val breakpoints = doubleArrayOf(0.7071067811865476, 1.0, 1.4142135623730951, 2.0)
		for (b in breakpoints) {
			var prev = PortableMath.ln(b * (1.0 - 1.0e-13))
			var x = b * (1.0 - 1.0e-13)
			repeat(100) {
				x = Double.fromBits(x.toRawBits() + 20_000_000L) // step upward in ulps
				val cur = PortableMath.ln(x)
				assertThat(cur >= prev).isTrue()
				prev = cur
			}
		}
	}
}
