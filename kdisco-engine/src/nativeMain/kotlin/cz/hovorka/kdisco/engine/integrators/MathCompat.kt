package cz.hovorka.kdisco.engine

import kotlin.math.pow

// Native: delegates to the platform C pow() — reproducible within a given build/toolchain,
// but not guaranteed bit-identical to the JVM (StrictMath/fdlibm) result
internal actual fun strictPow(base: Double, exp: Double): Double = base.pow(exp)
