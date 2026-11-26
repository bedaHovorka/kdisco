// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

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

    /** [process]'s actions threw [failure]; emitted before the process is marked terminated. */
    class ProcessFailed(time: Double, process: Process, val failure: Throwable) : SimulationEvent(time, process)

    /**
     * Engine diagnostics: conditions an embedder should log (kDisco has no logger).
     *
     * A diagnostic may be delivered from inside the continuous integrator —
     * [ProbeSubStepLimitReached] is emitted while the monitor phase is active. A listener should
     * therefore only record or log it. Calling engine APIs from such a listener — starting or
     * stopping a [Variable] or a [Continuous], [Resource.reserve] or [Resource.release],
     * [Process.activate] — is illegal or invalidates the integration step.
     *
     * The [time] of a probe diagnostic is the probe clock, so it can be later than the time of
     * events delivered after it.
     */
    sealed class Diagnostic(time: Double, process: Process? = null) : SimulationEvent(time, process) {
        /** One-line English description suitable for a WARN log line. */
        abstract val message: String

        /**
         * Continuous integration restarted [retries] times in a row for one event boundary —
         * [Simulation.integrationRetryWarnThreshold] was reached. Emitted at most once per boundary.
         * [process] is the mutating source: the process whose turn was last queued or dropped by the
         * aborted step, or `null` if it is not known. [kind] is the kind of the last restart.
         */
        class IntegrationRetryThresholdCrossed(
            time: Double,
            process: Process?,
            val retries: Int,
            val kind: IntegrationRestartKind,
        ) : Diagnostic(time, process) {
            override val message: String
                get() = integrationRetryMessage(time, retries, kind, process)
        }

        /**
         * A state-event probe re-integrating [from]..[to] stopped at its sub-step limit (1000) still short
         * of [to]: the clock was left at [time], so a crossing located from this probe is only a best
         * effort. [variables] are the active variables that were being integrated.
         */
        class ProbeSubStepLimitReached(time: Double, val variables: List<Variable>, val from: Double, val to: Double) :
            Diagnostic(time) {
            override val message: String
                get() = "Probe sub-step limit (${ContinuousMonitor.MAX_PROBE_SUB_STEPS}) reached " +
                    "integrating $variables over [$from, $to]; clock left at t=$time"
        }
    }
}
