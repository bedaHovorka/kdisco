package cz.hovorka.kdisco

/**
 * Creates a new [Simulation] with the given setup configuration.
 * Processes activated in [setup] are queued but not yet executed.
 */
fun simulation(setup: Simulation.() -> Unit): Simulation =
    Simulation.create(setup)

/**
 * Creates and immediately runs a simulation until [endTime].
 */
suspend fun runSimulation(endTime: Double = Double.MAX_VALUE, setup: Simulation.() -> Unit) {
    simulation(setup).run(endTime)
}
