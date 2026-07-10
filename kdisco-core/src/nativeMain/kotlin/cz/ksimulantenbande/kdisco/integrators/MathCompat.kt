// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlin.math.pow

// Native: delegates to the platform C pow() — reproducible within a given build/toolchain,
// but not guaranteed bit-identical to the JVM (StrictMath/fdlibm) result
internal actual fun strictPow(base: Double, exp: Double): Double = base.pow(exp)
