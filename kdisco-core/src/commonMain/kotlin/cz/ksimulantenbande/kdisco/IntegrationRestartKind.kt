// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

/**
 * Why a continuous integration step made [Simulation.run] recompute its event boundary and
 * integrate again. Reported by [SimulationEvent.Diagnostic.IntegrationRetryThresholdCrossed] and
 * [IntegrationRetryLimitException].
 */
enum class IntegrationRestartKind {
    /**
     * User code queued or dropped a turn *during* the step (a guard, [Continuous.derivatives], a
     * root-finding probe), at a speculative time. The variables and the clock were unwound to the
     * step start, so simulated time did not advance.
     */
    MUTATION,

    /**
     * User code queued or dropped a turn while the wait notices and level crossings were re-tested
     * at the accepted step end. The step-end state stands; only the boundary was stale.
     */
    NOTICE_RELEASE,
}
