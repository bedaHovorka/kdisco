// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlin.math.pow

// JS: uses platform Math.pow (reproducible within a given engine/build,
// but ECMAScript does not guarantee bit-identical transcendental results across engines)
internal actual fun strictPow(base: Double, exp: Double): Double = base.pow(exp)
