package cz.hovorka.kdisco.engine

/**
 * Per-simulation-run state. Replaces jDisco's static globals.
 */
internal class SimulationContext {
    val eventQueue: EventQueue = EventQueue()
    var currentTime: Double = 0.0
    var currentProcess: Process? = null
    var isRunning: Boolean = false
    var stopRequested: Boolean = false
    val pendingActivations = mutableListOf<PendingActivation>()
}
