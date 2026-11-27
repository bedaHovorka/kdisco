// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

/**
 * How a [Simulation.run] that returned normally ended.
 *
 * The failures are not `RunEnd` values — they throw: [ProcessFailureException] under
 * [Simulation.stopOnProcessFailure], and cancellation of the coroutine running the simulation.
 * Cancellation wins over a pending process failure. A caller that needs only a yes/no answer
 * can test `!= RunEnd.STOPPED`.
 */
enum class RunEnd {
    /**
     * No event was left to process before `endTime`: no discrete event queued and no continuous
     * process active. In a model that should run to the horizon this is how a deadlock looks.
     */
    QUEUE_DRAINED,

    /**
     * The next event lies beyond `endTime`: the normal end of a run. Processes still holding or
     * queued are the state at the cut-off, not a failure.
     */
    END_TIME_REACHED,

    /**
     * [Simulation.stop] was called — from any source: a process, an [Simulation.onEvent]
     * listener, or the `beforeEvent` hook.
     */
    STOPPED,
}
