// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
//
// The ln/exp implementations below are pure-Kotlin ports of fdlibm 5.3
// (e_log.c / e_exp.c), developed at SunSoft, a Sun Microsystems, Inc. business.
// "Permission to use, copy, modify, and distribute this software is freely
// granted, provided that this notice is preserved."
package cz.ksimulantenbande.kdisco

/**
 * Portable, bit-for-bit reproducible transcendental functions used by [Random].
 *
 * `kotlin.math.ln` / `kotlin.math.exp` delegate to different implementations per
 * platform (`java.lang.Math` on JVM, the platform C `libm` on Native, the JS engine's
 * `Math` on JS). None of these are required to be *correctly rounded* — only accurate
 * to within ~1 ulp — so conforming implementations may legally return last-bit-different
 * results for the identical input double. In a discrete-event simulation such a 1-ulp
 * difference in a sampled `exp()` delay perturbs event times and is amplified over a
 * long run into materially different results per platform (see issue #69).
 *
 * [PortableMath.ln] and [PortableMath.exp] are faithful pure-Kotlin ports of the
 * fdlibm algorithms (`e_log.c` / `e_exp.c`), the same algorithms used by
 * `java.lang.StrictMath`. All arithmetic is plain IEEE-754 double operations plus
 * bit manipulation, so the results are bit-identical on JVM, JS and all Native targets
 * (Kotlin `Double` is IEEE-754 binary64 everywhere), and match `StrictMath.log` /
 * `StrictMath.exp` on the JVM.
 */
internal object PortableMath {

    private const val TWO54 = 1.80143985094819840000e+16 // 0x1.0p54

    /** High 32 bits of the IEEE-754 representation of [x]. */
    private fun hi(x: Double): Int = (x.toRawBits() ushr 32).toInt()

    /** Low 32 bits of the IEEE-754 representation of [x]. */
    private fun lo(x: Double): Int = x.toRawBits().toInt()

    /** [x] with its high 32 IEEE-754 bits replaced by [high]. */
    private fun setHi(x: Double, high: Int): Double =
        Double.fromBits((high.toLong() shl 32) or (x.toRawBits() and 0xFFFFFFFFL))

    // ---------------------------------------------------------------------
    // ln — fdlibm e_log.c
    // ---------------------------------------------------------------------

    private const val LN2_HI = 6.93147180369123816490e-01 // 0x1.62e42feep-1
    private const val LN2_LO = 1.90821492927058770002e-10 // 0x1.a39ef35793c76p-33

    private const val LG1 = 6.666666666666735130e-01 // 0x1.5555555555593p-1
    private const val LG2 = 3.999999999940941908e-01 // 0x1.999999997fa04p-2
    private const val LG3 = 2.857142874366239149e-01 // 0x1.2492494229359p-2
    private const val LG4 = 2.222219843214978396e-01 // 0x1.c71c51d8e78afp-3
    private const val LG5 = 1.818357216161805012e-01 // 0x1.7466496cb03dep-3
    private const val LG6 = 1.531383769920937332e-01 // 0x1.39a09d078c69fp-3
    private const val LG7 = 1.479819860511658591e-01 // 0x1.2f112df3e5244p-3

    /**
     * Natural logarithm of [x], bit-identical across platforms.
     *
     * Special cases (same as `StrictMath.log`):
     * `ln(NaN) = NaN`, `ln(x < 0) = NaN`, `ln(+Inf) = +Inf`, `ln(±0) = -Inf`.
     */
    fun ln(x: Double): Double {
        var xv = x
        var hx = hi(xv)
        val lx = lo(xv)

        var k = 0
        if (hx < 0x00100000) { // x < 2^-1022
            if (((hx and 0x7FFFFFFF) or lx) == 0) {
                return Double.NEGATIVE_INFINITY // log(+-0) = -inf
            }
            if (hx < 0) {
                return Double.NaN // log(-#) = NaN
            }
            k -= 54
            xv *= TWO54 // subnormal number, scale up x
            hx = hi(xv)
        }
        if (hx >= 0x7FF00000) {
            return xv + xv // +Inf or NaN
        }
        k += (hx shr 20) - 1023
        hx = hx and 0x000FFFFF
        var i = (hx + 0x95F64) and 0x100000
        xv = setHi(xv, hx or (i xor 0x3FF00000)) // normalize x or x/2
        k += i shr 20
        val f = xv - 1.0
        if ((0x000FFFFF and (2 + hx)) < 3) { // |f| < 2^-20
            if (f == 0.0) {
                if (k == 0) return 0.0
                val dk = k.toDouble()
                return dk * LN2_HI + dk * LN2_LO
            }
            val r = f * f * (0.5 - 0.33333333333333333 * f)
            if (k == 0) return f - r
            val dk = k.toDouble()
            return dk * LN2_HI - ((r - dk * LN2_LO) - f)
        }
        val s = f / (2.0 + f)
        val dk = k.toDouble()
        val z = s * s
        i = hx - 0x6147A
        val w = z * z
        val j = 0x6B851 - hx
        val t1 = w * (LG2 + w * (LG4 + w * LG6))
        val t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)))
        i = i or j
        val r = t2 + t1
        return if (i > 0) {
            val hfsq = 0.5 * f * f
            if (k == 0) {
                f - (hfsq - s * (hfsq + r))
            } else {
                dk * LN2_HI - ((hfsq - (s * (hfsq + r) + dk * LN2_LO)) - f)
            }
        } else {
            if (k == 0) {
                f - s * (f - r)
            } else {
                dk * LN2_HI - ((s * (f - r) - dk * LN2_LO) - f)
            }
        }
    }

    // ---------------------------------------------------------------------
    // exp — fdlibm e_exp.c
    // ---------------------------------------------------------------------

    private val HALF = doubleArrayOf(0.5, -0.5)
    private const val HUGE = 1.0e+300
    private const val TWOM1000 = 9.33263618503218878990e-302 // 0x1.0p-1000
    private const val O_THRESHOLD = 7.09782712893383973096e+02 // 0x1.62e42fefa39efp9
    private const val U_THRESHOLD = -7.45133219101941108420e+02 // -0x1.74910d52d3051p9
    private val LN2_HI_ARR = doubleArrayOf(LN2_HI, -LN2_HI)
    private val LN2_LO_ARR = doubleArrayOf(LN2_LO, -LN2_LO)
    private const val INV_LN2 = 1.44269504088896338700e+00 // 0x1.71547652b82fep0

    private const val P1 = 1.66666666666666019037e-01 // 0x1.555555555553ep-3
    private const val P2 = -2.77777777770155933842e-03 // -0x1.6c16c16bebd93p-9
    private const val P3 = 6.61375632143793436117e-05 // 0x1.1566aaf25de2cp-14
    private const val P4 = -1.65339022054652515390e-06 // -0x1.bbd41c5d26bf1p-20
    private const val P5 = 4.13813679705723846039e-08 // 0x1.6376972bea4d0p-25

    /**
     * The exponential function e^[x], bit-identical across platforms.
     *
     * Special cases (same as `StrictMath.exp`):
     * `exp(NaN) = NaN`, `exp(+Inf) = +Inf`, `exp(-Inf) = 0`,
     * overflow to `+Inf` above ~709.78, underflow to `0` below ~-745.13.
     */
    fun exp(x: Double): Double {
        var xv = x
        var hiPart = 0.0
        var loPart = 0.0
        var k = 0

        var hx = hi(xv) // high word of x
        val xsb = (hx shr 31) and 1 // sign bit of x
        hx = hx and 0x7FFFFFFF // high word of |x|

        // filter out non-finite argument
        if (hx >= 0x40862E42) { // if |x| >= 709.78...
            if (hx >= 0x7FF00000) {
                if (((hx and 0xFFFFF) or lo(xv)) != 0) {
                    return xv + xv // NaN
                }
                return if (xsb == 0) xv else 0.0 // exp(+-inf) = {inf, 0}
            }
            if (xv > O_THRESHOLD) return HUGE * HUGE // overflow
            if (xv < U_THRESHOLD) return TWOM1000 * TWOM1000 // underflow
        }

        // argument reduction
        if (hx > 0x3FD62E42) { // if |x| > 0.5 ln2
            if (hx < 0x3FF0A2B2) { // and |x| < 1.5 ln2
                hiPart = xv - LN2_HI_ARR[xsb]
                loPart = LN2_LO_ARR[xsb]
                k = 1 - xsb - xsb
            } else {
                k = (INV_LN2 * xv + HALF[xsb]).toInt()
                val t = k.toDouble()
                hiPart = xv - t * LN2_HI_ARR[0] // t*ln2HI is exact here
                loPart = t * LN2_LO_ARR[0]
            }
            xv = hiPart - loPart
        } else if (hx < 0x3E300000) { // when |x| < 2^-28
            if (HUGE + xv > 1.0) return 1.0 + xv // trigger inexact
        } else {
            k = 0
        }

        // x is now in primary range
        val t = xv * xv
        val c = xv - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))))
        if (k == 0) {
            return 1.0 - ((xv * c) / (c - 2.0) - xv)
        }
        var y = 1.0 - ((loPart - (xv * c) / (2.0 - c)) - hiPart)
        return if (k >= -1021) {
            setHi(y, hi(y) + (k shl 20)) // add k to y's exponent
        } else {
            y = setHi(y, hi(y) + ((k + 1000) shl 20))
            y * TWOM1000
        }
    }
}
