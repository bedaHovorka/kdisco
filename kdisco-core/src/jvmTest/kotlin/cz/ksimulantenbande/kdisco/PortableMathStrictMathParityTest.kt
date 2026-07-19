// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlin.test.Test

/**
 * JVM-only verification that [PortableMath.ln] / [PortableMath.exp] are bit-for-bit
 * identical to `java.lang.StrictMath.log` / `java.lang.StrictMath.exp` (the fdlibm
 * reference implementations the ports are based on). Since [PortableMath] produces
 * the same bits on every platform (verified by the commonTest golden-value tests),
 * this transitively pins the cross-platform behavior to fdlibm.
 */
class PortableMathStrictMathParityTest {

	@Test
	fun lnMatchesStrictMathOnRandomUniformDoubles() {
		val r = java.util.Random(12345L)
		repeat(200_000) {
			val x = r.nextDouble()
			if (x == 0.0) return@repeat
			assertThat(PortableMath.ln(x).toRawBits()).isEqualTo(StrictMath.log(x).toRawBits())
		}
	}

	@Test
	fun lnMatchesStrictMathAcrossFullExponentRange() {
		val r = java.util.Random(6789L)
		repeat(200_000) {
			// Random finite positive doubles across the full range, incl. subnormals
			val bits = r.nextLong() and 0x7FFFFFFFFFFFFFFFL
			val x = Double.fromBits(bits)
			if (!x.isFinite()) return@repeat
			assertThat(PortableMath.ln(x).toRawBits()).isEqualTo(StrictMath.log(x).toRawBits())
		}
	}

	@Test
	fun expMatchesStrictMathOnRandomArguments() {
		val r = java.util.Random(4242L)
		repeat(200_000) {
			val x = (r.nextDouble() - 0.5) * 1600.0
			assertThat(PortableMath.exp(x).toRawBits()).isEqualTo(StrictMath.exp(x).toRawBits())
		}
	}

	@Test
	fun expMatchesStrictMathNearZeroAndReductionBoundaries() {
		val r = java.util.Random(777L)
		repeat(200_000) {
			val x = (r.nextDouble() - 0.5) * 4.0
			assertThat(PortableMath.exp(x).toRawBits()).isEqualTo(StrictMath.exp(x).toRawBits())
		}
		repeat(100_000) {
			val x = (r.nextDouble() - 0.5) * 1.0e-7
			assertThat(PortableMath.exp(x).toRawBits()).isEqualTo(StrictMath.exp(x).toRawBits())
		}
	}

	@Test
	fun specialCasesMatchStrictMath() {
		val lnInputs = doubleArrayOf(
			0.0, -0.0, 1.0, Double.MIN_VALUE, Double.MAX_VALUE,
			Double.POSITIVE_INFINITY, 2.0, 0.5, 1.0 + 1.0e-15, 4.9e-324,
		)
		for (x in lnInputs) {
			assertThat(PortableMath.ln(x).toRawBits()).isEqualTo(StrictMath.log(x).toRawBits())
		}
		assertThat(PortableMath.ln(Double.NaN).isNaN()).isTrue()
		assertThat(PortableMath.ln(-1.0).isNaN()).isTrue()
		assertThat(PortableMath.ln(Double.NEGATIVE_INFINITY).isNaN()).isTrue()

		val expInputs = doubleArrayOf(
			0.0, -0.0, 1.0, -1.0, 709.0, 710.0, -745.0, -746.0,
			Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
			709.78271289338397, -744.44007192138122, 1.0e-30, -1.0e-30,
		)
		for (x in expInputs) {
			assertThat(PortableMath.exp(x).toRawBits()).isEqualTo(StrictMath.exp(x).toRawBits())
		}
		assertThat(PortableMath.exp(Double.NaN).isNaN()).isTrue()
	}

	@Test
	fun jvmNormalSequenceUnchangedFromJavaUtilRandomNextGaussian() {
		// The kDisco Random must keep producing the exact same normal() sequence
		// as java.util.Random.nextGaussian() did before the PortableMath switch.
		val kd = Random(42L)
		val ju = java.util.Random(42L)
		repeat(10_000) {
			assertThat(kd.normal(0.0, 1.0).toRawBits()).isEqualTo(ju.nextGaussian().toRawBits())
		}
	}
}
