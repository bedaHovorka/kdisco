package cz.hovorka.kdisco.engine

internal actual fun strictPow(base: Double, exp: Double): Double =
    java.lang.StrictMath.pow(base, exp)
