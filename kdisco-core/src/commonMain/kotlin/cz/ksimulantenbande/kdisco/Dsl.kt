// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

/**
 * Creates a new [Simulation] with the given setup configuration.
 * Processes activated in [setup] are queued but not yet executed.
 *
 * @param seed Optional seed for the simulation's random generator for deterministic runs.
 */
fun simulation(seed: Long? = null, setup: Simulation.() -> Unit): Simulation =
    Simulation.create(seed, setup)

/**
 * Creates and immediately runs a simulation until [endTime].
 *
 * @param seed Optional seed for the simulation's random generator for deterministic runs.
 */
suspend fun runSimulation(
    endTime: Double = Double.MAX_VALUE,
    seed: Long? = null,
    setup: Simulation.() -> Unit
) {
    simulation(seed, setup).run(endTime)
}

/**
 * Creates and immediately runs a simulation until [endTime] under an external
 * [SimulationController].
 */
suspend fun runSimulation(
    endTime: Double = Double.MAX_VALUE,
    controller: SimulationController,
    setup: Simulation.() -> Unit
) {
    simulation(null, setup).run(endTime, controller)
}

/**
 * Emit a custom simulation event from any code running on the simulation thread.
 *
 * Unlike [Process.emitCustom], this top-level function does not require a [Process]
 * instance — any service or helper method called from within simulation-time execution
 * can use it. Uses [Process.activeContext] to reach the event bus.
 *
 * No-op when called outside a simulation run (activeContext is null) or when no
 * listeners are registered.
 */
fun emitCustom(payload: Any?) {
    val ctx = Process.activeContext ?: return
    if (ctx.eventListeners.isEmpty()) return
    val event = SimulationEvent.Custom(ctx.currentTime, payload)
    ctx.eventListeners.forEach { it(event) }
}
