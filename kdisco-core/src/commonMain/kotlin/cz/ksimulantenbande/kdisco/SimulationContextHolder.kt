// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

/**
 * Holds the [SimulationContext] of the simulation running on the caller's execution context
 * (see [Process.activeContext]).
 *
 * - JVM: a `ThreadLocal` — one active context per thread, so concurrent simulations on separate
 *   threads do not see each other; a thread that did not call [Simulation.run] sees none.
 * - js and native: a single global — one active context per process, so at most one simulation
 *   may be running at a time; a second concurrent [Simulation.run] would overwrite it.
 */
internal expect object SimulationContextHolder {
    var context: SimulationContext?
}
