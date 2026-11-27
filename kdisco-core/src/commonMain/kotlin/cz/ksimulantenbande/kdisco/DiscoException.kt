// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

/**
 * Exception thrown by the simulation engine when it detects an error.
 */
open class DiscoException(message: String) : RuntimeException(message)

/**
 * Thrown by [Simulation.run] when continuous integration restarted more than
 * [Simulation.integrationRetryLimit] times in a row for one event boundary — a run that would
 * otherwise spin at (nearly) constant simulated time, because user code queues or drops a turn on
 * every retry.
 *
 * This is an engine failure, not a process failure: it is not reported through
 * [Simulation.onProcessFailure] and it is not a [ProcessFailureException]. [Simulation.run] has
 * already torn the run down (process scope cancelled, event queue cleared) when it is thrown.
 *
 * @property time simulated time at which the limit was exceeded.
 * @property retries the consecutive retry count that exceeded the limit, i.e.
 *   `integrationRetryLimit + 1`.
 * @property kind the kind of the last restart.
 * @property source the process whose turn was last queued or dropped by the aborted step — the
 *   mutating source — or `null` if it is not known.
 */
class IntegrationRetryLimitException(
    val time: Double,
    val retries: Int,
    val kind: IntegrationRestartKind,
    val source: Process?,
) : DiscoException(integrationRetryMessage(time, retries, kind, source))

/**
 * The one-line description shared by [IntegrationRetryLimitException] and
 * [SimulationEvent.Diagnostic.IntegrationRetryThresholdCrossed]; a `null` [source] is named
 * "unknown".
 */
internal fun integrationRetryMessage(
    time: Double,
    retries: Int,
    kind: IntegrationRestartKind,
    source: Process?,
): String = "Continuous integration restarted $retries times in a row at t=$time " +
    "(last restart: $kind, mutating source: ${source ?: "unknown"})"

/**
 * Thrown by [Simulation.run] when [Simulation.stopOnProcessFailure] is set and [process]'s
 * actions threw [cause] at simulation time [time].
 */
class ProcessFailureException(val process: Process, val time: Double, cause: Throwable) :
    RuntimeException("Process $process failed at t=$time: $cause", cause)
