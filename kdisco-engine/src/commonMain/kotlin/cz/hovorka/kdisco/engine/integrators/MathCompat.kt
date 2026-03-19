package cz.hovorka.kdisco.engine

/** Platform-strict power function — guarantees IEEE 754 bit-identical results across JVMs. */
internal expect fun strictPow(base: Double, exp: Double): Double
