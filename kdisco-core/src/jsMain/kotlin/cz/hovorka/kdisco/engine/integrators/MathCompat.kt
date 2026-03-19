package cz.hovorka.kdisco.engine

import kotlin.math.pow

// JS: uses platform Math.pow (reproducible within a given engine/build,
// but ECMAScript does not guarantee bit-identical transcendental results across engines)
internal actual fun strictPow(base: Double, exp: Double): Double = base.pow(exp)
