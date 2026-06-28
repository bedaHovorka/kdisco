package cz.hovorka.kdisco

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
