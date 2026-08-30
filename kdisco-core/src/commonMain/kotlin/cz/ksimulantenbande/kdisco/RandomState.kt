// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

/**
 * Opaque, serializable snapshot of a [Random] generator's complete internal state,
 * including the LCG seed and the Marsaglia polar-method Gaussian cache.
 *
 * Created by [Random.captureState] and consumed by [Random.restoreState].
 * Encoding is platform-independent: `data[0]` is the 48-bit scrambled seed,
 * `data[1]` is the cached Gaussian value (as [Double.toBits]), and `data[2]`
 * is `1L` when a cached value is present, `0L` otherwise.
 */
data class RandomState(val data: LongArray) {
    override fun equals(other: Any?): Boolean = other is RandomState && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()
}
