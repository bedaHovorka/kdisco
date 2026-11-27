// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

/**
 * Exception thrown by the simulation engine when it detects an error.
 */
class DiscoException(message: String) : RuntimeException(message)

/**
 * Thrown by [Simulation.run] when [Simulation.stopOnProcessFailure] is set and [process]'s
 * actions threw [cause] at simulation time [time].
 */
class ProcessFailureException(val process: Process, val time: Double, cause: Throwable) :
    RuntimeException("Process $process failed at t=$time: $cause", cause)
