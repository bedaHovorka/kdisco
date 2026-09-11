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
     *
     * @return true if a step was aborted and the variables unwound to its start (see
     *   [settleStep]). The caller's integration boundary is stale — the clock has moved backwards
     *   and the queue holds a turn queued during the aborted step — so it must restart its loop and
     *   recompute the boundary before popping anything.
     */
    fun integrateUntil(targetTime: Double): Boolean {
        if (context.firstCont == null) return false
        if (context.currentTime >= targetTime) return false
        var aborted = false

        context.monitorActive = true
        try {
            var dtNextLocal = dtNext
            while (context.currentTime < targetTime) {
                val stepStart = context.currentTime
                saveStepStartState()
                // Baseline for "did user code schedule or cancel anything during this step?".
                // Taken before the first guard sample, because every piece of user code the step
                // runs — guards, the initial derivatives call, each RK stage, each root-finding
                // probe — may call [Process.activate], [Process.reactivate] or [Process.terminate],
                // and each of those queues or drops a turn at whatever speculative time the engine
                // is sitting at. A mutation count, not a queue size: reactivate removes and adds,
                // leaving the size equal.
                val queueMutationsBefore = context.eventQueue.mutations
                // Sample guard values at the start of the step (states are at stepStart).
                val guardsBefore = sampleGuards()

                // Compute initial derivatives (k1 in RKF45, or rate for Euler)
                computeDerivatives()

                val dtNow = chooseStepSize(dtNextLocal, targetTime - context.currentTime)
                dtNextLocal = integrator.integrate(this, context, dtNow, targetTime)

                val outcome = settleStep(stepStart, guardsBefore, queueMutationsBefore)
                if (outcome != StepOutcome.CONTINUE) {
                    aborted = outcome == StepOutcome.ABORTED
                    break
                }
            }
            dtNext = dtNextLocal
        } finally {
            context.monitorActive = false
        }
        return aborted
    }

    /**
     * Decides what an accepted step settles to: whether a crossing was located inside it, whether
     * the step is void because user code scheduled something at a speculative time, or whether a
     * wait notice or level crossing became satisfied at its end.
     *
     * The queue-mutation test covers the whole step, not just crossing location, and runs even when
     * nothing crossed. Any user code the step ran can queue a turn — a guard that did not cross,
     * the initial derivatives call, an RK stage — and such a turn sits at a speculative time that
     * can be *earlier* than where integration has since reached. Continuing to the original target
     * would leave the scheduler to pop it holding state from the future.
     */
    private fun settleStep(
        stepStart: Double,
        guardsBefore: List<Pair<CrossingNotice, Double>>?,
        queueMutationsBefore: Long,
    ): StepOutcome {
        val located = if (guardsBefore != null) {
            locateCrossings(stepStart, guardsBefore, queueMutationsBefore)
        } else {
            StepOutcome.CONTINUE
        }
        if (located != StepOutcome.CONTINUE) return located
        if (context.eventQueue.mutations != queueMutationsBefore) {
            probeStateAt(stepStart, stepStart)
            return StepOutcome.ABORTED
        }
        // Stop integration if a wait notice or a level-triggered crossing notice was satisfied, so
        // the scheduler processes the newly-scheduled event with states that match currentTime.
        //
        // The conditions and guards those checks evaluate are user code too, and one that is *not*
        // satisfied can still schedule: an unsatisfied waitUntil condition reactivating a helper
        // leaves both notice registries the same size, so the check reports nothing fired while a
        // turn now sits in the queue at the current time. Integrating on past it would leave the
        // scheduler to resume that process holding state from the future.
        //
        // This is STOP, not ABORTED: these run at the accepted step end, so anything they queue is
        // at currentTime or later, and the variables already match currentTime. Nothing to unwind —
        // integration simply must not advance beyond it.
        val mutationsBeforeNotices = context.eventQueue.mutations
        val noticesFired = postStepNoticesFired()
        val noticesScheduled = context.eventQueue.mutations != mutationsBeforeNotices
        return if (noticesFired || noticesScheduled) StepOutcome.STOP else StepOutcome.CONTINUE
    }

    /** Saves each active [Variable]'s pre-step state and clears its rate for the coming step. */
    private fun saveStepStartState() {
        var v = context.firstVar
        while (v != null) {
            v._oldState = v.state
            v.rate = 0.0
            v = v._suc
        }
    }

    /**
     * Clamps the error controller's proposed step to `[dtMin, dtMax]` and to what is left before
     * the integration target.
     */
    private fun chooseStepSize(proposed: Double, remaining: Double): Double {
        val clamped = when {
            proposed <= 0.0 || proposed > context.dtMax -> context.dtMax
            proposed < context.dtMin -> context.dtMin
            else -> proposed
        }
        return if (clamped > remaining) remaining else clamped
    }

    /**
     * Re-tests the wait and level-crossing registries after an accepted step.
     *
     * @return true if anything fired, meaning integration must stop so the scheduler can take the
     *   newly-scheduled event with variable states that match [SimulationContext.currentTime].
     */
    private fun postStepNoticesFired(): Boolean {
        val noticesBefore = context.waitNotices.size
        context.checkWaitNotices()
        val levelFired = context.checkLevelCrossings()
        return levelFired || context.waitNotices.size < noticesBefore
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
     * at the crossing time — unless user code queued or dropped a turn while the guards and
     * root-finding probes were running, in which case nothing is scheduled and the variables are
     * unwound to the step start instead.
     *
     * @return [StepOutcome.STOP] if a crossing fired (integration must stop), [StepOutcome.ABORTED] if
     *   the pass was invalidated and the variables were unwound to [stepStart], or [StepOutcome.CONTINUE].
     */
    private fun locateCrossings(
        stepStart: Double,
        guardsBefore: List<Pair<CrossingNotice, Double>>,
        queueMutationsBefore: Long,
    ): StepOutcome {
        val stepEnd = context.currentTime
        val crossed = collectCrossed(guardsBefore)
        if (crossed.isEmpty()) return StepOutcome.CONTINUE

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
        val notice = bestNotice ?: return StepOutcome.CONTINUE

        // Roll variable states back to the located crossing time.
        probeStateAt(stepStart, bestTime)
        // Only now test the registry, with nothing between it and the schedule below: the rollback
        // immediately above runs derivatives too, so testing any earlier would leave its calls
        // outside the window.
        if (context.eventQueue.mutations != queueMutationsBefore) {
            // Something queued or dropped a turn while this pass was running user code, so the
            // crossing located here is not happening. Three shapes, one rule:
            //
            // - the *winner's own* notice was cancelled, so its crossing is void outright, and
            //   scheduling it would hand the process a stale second wake-up;
            // - a *bystander's* notice was cancelled, or an unrelated process was activated or
            //   reactivated, queueing a turn at whatever speculative probe time the cancelling
            //   call ran at — possibly earlier than the crossing located here. Scheduling now
            //   would leave the variables at the later crossing while the scheduler takes the
            //   earlier turn, running the clock backwards over state from the future.
            //
            // Unwind to the step start instead — the last state this engine committed, and no
            // later than any turn queued during the step — and leave every surviving notice
            // registered. [StepOutcome.ABORTED] makes the scheduler recompute its event boundary and
            // integrate forward to whatever event it actually takes, so the clock and the state
            // agree there, and this crossing is simply located again on a later step.
            probeStateAt(stepStart, stepStart)
            return StepOutcome.ABORTED
        }
        // Scheduled unconditionally, for the same reason as checkWaitNotices (issue #73): a live
        // notice's wake-up may not be spent on an independent activate's.
        context.crossingNotices.remove(notice)
        context.eventQueue.schedule(notice.process, bestTime)
        return StepOutcome.STOP
    }

    /**
     * First pass: evaluates every sampled guard at the accepted step end, before any root-finding
     * probe mutates the state, and returns the notices that crossed paired with their step-start
     * value.
     *
     * Notices no longer present in the live registry are skipped — removed mid-step by
     * reactivate/terminate/cancellation, they must not be allowed to fire.
     *
     * Edge-triggered ([Process.waitCrossing]): a crossing requires a strict sign change from a
     * non-zero start (reaching the boundary exactly, `g1 == 0.0`, counts). A guard already zero at
     * the step start is not a crossing — it must first depart from the boundary.
     *
     * Level-triggered ([Process.waitUntilCrossing]): only the downward transition (guard becomes
     * satisfied, `g1 <= 0`) is a crossing; an upward one would mean the guard was already satisfied
     * at the step start, which [SimulationContext.checkLevelCrossings] handles instead.
     */
    private fun collectCrossed(guardsBefore: List<Pair<CrossingNotice, Double>>): List<Pair<CrossingNotice, Double>> {
        var crossed: MutableList<Pair<CrossingNotice, Double>>? = null
        for ((notice, g0) in guardsBefore) {
            if (!context.crossingNotices.contains(notice)) continue
            val g1 = notice.guard()
            val fires = if (notice.levelTriggered) {
                g0 > 0.0 && g1 <= 0.0
            } else {
                (g0 > 0.0 && g1 <= 0.0) || (g0 < 0.0 && g1 >= 0.0)
            }
            if (fires) {
                if (crossed == null) crossed = mutableListOf()
                crossed.add(notice to g0)
            }
        }
        return crossed ?: emptyList()
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
        guardAtStart: Double,
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

/**
 * What an accepted integration step settled to.
 */
internal enum class StepOutcome {
    /** Nothing is owed at this time; integration continues towards its target. */
    CONTINUE,

    /**
     * Something was scheduled at or before the current time — a located crossing, a satisfied wait
     * notice or level crossing — so integration stops here and the scheduler takes it with variable
     * states that match the clock.
     */
    STOP,

    /**
     * User code queued or dropped a turn at a speculative time during the step, so the step is
     * void. The variables were unwound to the step start and nothing was scheduled; the caller
     * must restart the scheduler loop and recompute its event boundary before popping anything.
     */
    ABORTED,
}
