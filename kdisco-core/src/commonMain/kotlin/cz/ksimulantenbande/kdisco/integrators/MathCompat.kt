// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

/**
 * Platform power function with reproducible results.
 * JVM: delegates to [java.lang.StrictMath.pow] (fdlibm-based, bit-for-bit reproducible across JVM builds).
 * JS/Native: delegates to the platform default `pow` (reproducible within a given engine/build,
 * but not guaranteed bit-identical to the JVM result).
 */
internal expect fun strictPow(base: Double, exp: Double): Double
