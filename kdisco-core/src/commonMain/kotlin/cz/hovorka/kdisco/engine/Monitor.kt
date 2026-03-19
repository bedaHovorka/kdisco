package cz.hovorka.kdisco.engine

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
                // Save old states and reset rates for this step
                var v = context.firstVar
                while (v != null) {
                    v._oldState = v.state
                    v.rate = 0.0
                    v = v._suc
                }
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
                // Stop integration early if a wait-notice was satisfied so that the scheduler
                // can process the newly-scheduled event with variable states that match currentTime.
                val noticesBefore = context.waitNotices.size
                context.checkWaitNotices()
                if (context.waitNotices.size < noticesBefore) break
            }
            dtNext = dtNextLocal
        } finally {
            context.monitorActive = false
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
