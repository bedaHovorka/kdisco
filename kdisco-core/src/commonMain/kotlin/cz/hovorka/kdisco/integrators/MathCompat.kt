package cz.hovorka.kdisco

/**
 * Platform power function with reproducible results.
 * JVM: delegates to [java.lang.StrictMath.pow] (fdlibm-based, bit-for-bit reproducible across JVM builds).
 * JS/Native: delegates to the platform default `pow` (reproducible within a given engine/build,
 * but not guaranteed bit-identical to the JVM result).
 */
internal expect fun strictPow(base: Double, exp: Double): Double
