// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlin.math.abs

/**
 * Strategy interface for numerical integration of continuous variables.
 *
 * An integrator performs one integration step of size [dtNow], advances
 * [SimulationContext.currentTime] by that amount, and returns a suggested
 * step size for the next call.
 */
internal interface Integrator {
    /**
     * Integrates all active [Variable]s for one step.
     *
     * @param monitor the monitor owning the active lists and derivative computation.
     * @param context the simulation context (currentTime will be advanced by dtNow).
     * @param dtNow the step size to use for this integration step.
     * @param targetTime the next event time (integration must not overshoot it).
     * @return suggested step size for the next step.
     */
    fun integrate(
        monitor: ContinuousMonitor,
        context: SimulationContext,
        dtNow: Double,
        targetTime: Double
    ): Double
}

/**
 * Drives continuous simulation between discrete events.
 *
 * Called by [Simulation.run] before each discrete event is processed. It integrates
 * all active [Variable]s according to the derivatives provided by active [Continuous]
 * processes, advancing [SimulationContext.currentTime] up to [targetTime].
 *
 * Thread safety: not thread-safe. All access is single-threaded via the simulation loop.
 */
internal class ContinuousMonitor(
    private val context: SimulationContext,
    internal var integrator: Integrator = RKF45Integrator()
) {
    private companion object {
        /** Upper bound on bisection iterations when locating a zero-crossing. */
        const val MAX_BISECTION_ITERATIONS = 100

        /** Upper bound on re-integration sub-steps when probing a specific target time. */
        const val MAX_PROBE_SUB_STEPS = 1_000
    }

    /** Suggested step size for the next integration step (preserved across event boundaries). */
    internal var dtNext: Double = 0.0

    /**
     * Integrates all active [Variable]s from [SimulationContext.currentTime] up to [targetTime].
     * Advances [SimulationContext.currentTime] to [targetTime] when complete.
     *
     * Does nothing if there are no active [Continuous] processes.
     */
    fun integrateUntil(targetTime: Double) {
        if (context.firstCont == null) return
        if (context.currentTime >= targetTime) return

        context.monitorActive = true
        try {
            var dtNextLocal = dtNext
            while (context.currentTime < targetTime) {
                val stepStart = context.currentTime
                // Save old states and reset rates for this step
                var v = context.firstVar
                while (v != null) {
                    v._oldState = v.state
                    v.rate = 0.0
                    v = v._suc
                }
                // Sample guard values at the start of the step (states are at stepStart).
                val guardsBefore = sampleGuards()

                // Compute initial derivatives (k1 in RKF45, or rate for Euler)
                computeDerivatives()

                // Determine step size
                val remaining = targetTime - context.currentTime
                var dtNow = when {
                    dtNextLocal <= 0.0 || dtNextLocal > context.dtMax -> context.dtMax
                    dtNextLocal < context.dtMin -> context.dtMin
                    else -> dtNextLocal
                }
                if (dtNow > remaining) dtNow = remaining

                dtNextLocal = integrator.integrate(this, context, dtNow, targetTime)

                // Locate any state event (zero-crossing) inside the accepted step. If one
                // fired, integration is rolled back to the crossing time and the waiting
                // process is scheduled there, so we stop this integration pass immediately.
                if (guardsBefore != null && locateCrossings(stepStart, guardsBefore)) break

                // Stop integration early if a wait-notice or a level-triggered crossing notice
                // was satisfied so that the scheduler can process the newly-scheduled event
                // with variable states that match currentTime.
                val noticesBefore = context.waitNotices.size
                context.checkWaitNotices()
                val levelFired = context.checkLevelCrossings()
                if (levelFired || context.waitNotices.size < noticesBefore) break
            }
            dtNext = dtNextLocal
        } finally {
            context.monitorActive = false
        }
    }

    /**
     * Samples every registered [CrossingNotice.guard] at the current state, paired with the
     * notice itself, or returns null when there are no crossing notices (zero-overhead fast
     * path). Pairing by object reference (rather than list position) keeps the "before" value
     * correctly attached to its notice even if [SimulationContext.crossingNotices] is mutated
     * later in the same step (e.g. a [Continuous.derivatives] override calling
     * [Process.reactivate] on another process with a pending crossing notice).
     */
    private fun sampleGuards(): List<Pair<CrossingNotice, Double>>? {
        val notices = context.crossingNotices
        if (notices.isEmpty()) return null
        return notices.map { it to it.guard() }
    }

    /**
     * Detects and locates state events within the step `[stepStart, currentTime]`.
     *
     * For every crossing notice whose guard changed sign relative to [guardsBefore], the
     * crossing time is located by [locateCrossingTime]. The earliest such crossing wins:
     * integration is rolled back to it, the notice is removed, and its process is scheduled
     * at the crossing time.
     *
     * @return true if a crossing fired (integration must stop), false otherwise.
     */
    private fun locateCrossings(stepStart: Double, guardsBefore: List<Pair<CrossingNotice, Double>>): Boolean {
        val stepEnd = context.currentTime
        // First pass: evaluate all guards at the accepted step end (states are at stepEnd)
        // before any root-finding probe mutates the state. Skip any notice no longer present
        // in the live registry — it was removed mid-step (reactivate/terminate/cancellation)
        // and must not be allowed to fire.
        var anyCrossed = false
        val crossed = mutableListOf<Pair<CrossingNotice, Double>>()
        for ((notice, g0) in guardsBefore) {
            if (!context.crossingNotices.contains(notice)) continue
            val g1 = notice.guard()
            // Edge-triggered ([Process.waitCrossing]): a crossing requires a strict sign change
            // from a non-zero start (reaching the boundary exactly, g1 == 0.0, counts). A guard
            // that is already zero at the step start is not treated as a crossing — it must
            // first depart from the boundary.
            // Level-triggered ([Process.waitUntilCrossing]): only the downward transition
            // (guard becomes satisfied, g1 <= 0) is a crossing; an upward transition would mean
            // the guard was already satisfied at the step start, which is handled by
            // [SimulationContext.checkLevelCrossings] instead.
            val fires = if (notice.levelTriggered) {
                g0 > 0.0 && g1 <= 0.0
            } else {
                (g0 > 0.0 && g1 <= 0.0) || (g0 < 0.0 && g1 >= 0.0)
            }
            if (fires) {
                crossed.add(notice to g0)
                anyCrossed = true
            }
        }
        if (!anyCrossed) return false

        // Second pass: locate the crossing time for each crossing notice and keep the earliest.
        var bestNotice: CrossingNotice? = null
        var bestTime = Double.MAX_VALUE
        for ((notice, g0) in crossed) {
            val tStar = locateCrossingTime(notice, stepStart, stepEnd, g0)
            if (tStar < bestTime) {
                bestTime = tStar
                bestNotice = notice
            }
        }
        val notice = bestNotice ?: return false

        // Roll variable states back to the located crossing time and schedule the process there.
        probeStateAt(stepStart, bestTime)
        // Scheduled unconditionally: a crossing notice and a queued event are two distinct resumes
        // owed to the same process (issue #73). Suppressing the crossing wake-up because an
        // independent Process.activate happened to queue an event would silently drop one of them.
        context.eventQueue.schedule(notice.process, bestTime)
        context.crossingNotices.remove(notice)
        return true
    }

    /**
     * Locates the time in `[stepStart, stepEnd]` at which [notice]'s guard crosses zero,
     * using bisection. Each probe re-integrates from the saved pre-step state
     * ([Variable._oldState]) to the candidate time via [probeStateAt].
     *
     * @param guardAtStart the guard value at [stepStart] (must be non-zero and opposite in
     *   sign to the guard at [stepEnd]).
     */
    private fun locateCrossingTime(
        notice: CrossingNotice,
        stepStart: Double,
        stepEnd: Double,
        guardAtStart: Double
    ): Double {
        var lo = stepStart
        var hi = stepEnd
        var gLo = guardAtStart
        var iter = 0
        while (iter < MAX_BISECTION_ITERATIONS) {
            // Numerically stable midpoint that always stays within [lo, hi].
            val mid = lo + 0.5 * (hi - lo)
            // Stop once the bracket collapses to floating-point resolution.
            if (mid <= lo || mid >= hi) break
            probeStateAt(stepStart, mid)
            val gMid = notice.guard()
            if (gMid == 0.0 || abs(gMid) <= notice.tolerance) return mid
            if (gLo * gMid < 0.0) {
                hi = mid
            } else {
                lo = mid
                gLo = gMid
            }
            iter++
        }
        return hi
    }

    /**
     * Restores all active [Variable]s to their pre-step values and integrates from
     * [stepStart] to [targetTime], leaving [SimulationContext.currentTime] at [targetTime]
     * and the variable states at their [targetTime] values.
     *
     * The requested sub-step is normally well within what the error controller already
     * accepted for the enclosing full step, but [Integrator.integrate] can still shrink it
     * further (e.g. adverse local error growth at the probed sub-interval), leaving
     * [SimulationContext.currentTime] short of [targetTime]. Loop, re-deriving and
     * re-integrating the remaining distance, until [targetTime] is actually reached.
     */
    private fun probeStateAt(stepStart: Double, targetTime: Double) {
        var v = context.firstVar
        while (v != null) {
            v.state = v._oldState
            v.rate = 0.0
            v = v._suc
        }
        context.currentTime = stepStart
        computeDerivatives()
        var iterations = 0
        while (context.currentTime < targetTime && iterations < MAX_PROBE_SUB_STEPS) {
            integrator.integrate(this, context, targetTime - context.currentTime, targetTime)
            iterations++
            if (context.currentTime < targetTime) computeDerivatives()
        }
    }

    /**
     * Calls [Continuous.derivatives] on each active continuous process in priority order.
     */
    internal fun computeDerivatives() {
        var c = context.firstCont
        while (c != null) {
            c.derivatives()
            c = c._suc
        }
    }
}
