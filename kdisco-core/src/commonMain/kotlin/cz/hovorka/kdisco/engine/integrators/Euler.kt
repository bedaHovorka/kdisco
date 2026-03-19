package cz.hovorka.kdisco.engine

/**
 * Euler first-order fixed-step integration method.
 *
 * The step size remains constant at [SimulationContext.dtMax] unless the remaining
 * time to the next event is smaller. No error control is performed.
 *
 * Algorithm:
 * ```
 * state = oldState + dtNow * rate
 * advance time by dtNow
 * recompute derivatives
 * ```
 *
 * Returns [SimulationContext.dtMax] as the suggested next step size.
 */
internal class EulerIntegrator : Integrator {

    override fun integrate(
        monitor: ContinuousMonitor,
        context: SimulationContext,
        dtNow: Double,
        targetTime: Double
    ): Double {
        // Apply Euler step: state = oldState + dtNow * rate
        var v = context.firstVar
        while (v != null) {
            v.ds = dtNow * v.rate
            v.k1 = v.ds
            v.state = v._oldState + v.ds
            v = v._suc
        }

        // Advance time
        context.currentTime += dtNow

        // Recompute derivatives at new state/time
        var v2 = context.firstVar
        while (v2 != null) {
            v2.rate = 0.0
            v2 = v2._suc
        }
        monitor.computeDerivatives()

        return context.dtMax
    }
}
