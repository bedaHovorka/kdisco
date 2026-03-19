package cz.hovorka.kdisco.engine

import kotlin.math.pow

// JS Math.pow is already IEEE 754 strict
internal actual fun strictPow(base: Double, exp: Double): Double = base.pow(exp)
