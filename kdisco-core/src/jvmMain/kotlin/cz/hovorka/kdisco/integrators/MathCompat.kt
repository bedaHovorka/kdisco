package cz.hovorka.kdisco

internal actual fun strictPow(base: Double, exp: Double): Double =
    java.lang.StrictMath.pow(base, exp)
