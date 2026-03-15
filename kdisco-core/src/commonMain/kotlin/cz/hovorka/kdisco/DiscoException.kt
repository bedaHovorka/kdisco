package cz.hovorka.kdisco

/**
 * Exception thrown by the jDisco simulation engine whenever it detects an error.
 *
 * This exception is thrown by the simulation framework (not user code) when it
 * encounters illegal simulation state, such as:
 * - Calling `hold()` from within a continuous phase (`monitor.active == true`)
 * - The simulation event queue becoming empty unexpectedly
 * - Invalid integration parameters
 *
 * ### Usage
 * ```kotlin
 * try {
 *     sim.run(100.0)
 * } catch (e: DiscoException) {
 *     logger.error { "Simulation error: ${e.message}" }
 * }
 * ```
 *
 * @since 0.2.0
 */
expect class DiscoException : RuntimeException
