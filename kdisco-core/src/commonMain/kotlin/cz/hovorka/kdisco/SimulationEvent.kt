package cz.hovorka.kdisco

/**
 * Base type for all simulation-time events visible to subscribers.
 */
sealed class SimulationEvent(
    /** Simulation time at which the event occurred. */
    val time: Double,
    /** Process involved, if any. */
    val process: Process? = null,
    /** Resource involved, if any. */
    val resource: Resource? = null
) {
    class ProcessActivated(time: Double, process: Process) : SimulationEvent(time, process)
    class ProcessPassivated(time: Double, process: Process) : SimulationEvent(time, process)
    class ProcessReactivated(time: Double, process: Process) : SimulationEvent(time, process)
    class ProcessTerminated(time: Double, process: Process) : SimulationEvent(time, process)
    class ProcessHeld(time: Double, process: Process, val duration: Double) : SimulationEvent(time, process)

    class ResourceReserved(time: Double, process: Process, resource: Resource, val amount: Int = 1)
        : SimulationEvent(time, process, resource)

    class ResourceReleased(time: Double, process: Process, resource: Resource, val amount: Int = 1)
        : SimulationEvent(time, process, resource)

    class VariableChanged(time: Double, val variable: Variable, val oldState: Double, val newState: Double)
        : SimulationEvent(time)

    class Custom(time: Double, val payload: Any?) : SimulationEvent(time)
}
