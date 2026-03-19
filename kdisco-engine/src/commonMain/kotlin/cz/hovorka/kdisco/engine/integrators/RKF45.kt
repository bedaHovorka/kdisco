package cz.hovorka.kdisco.engine

import kotlin.math.abs

/**
 * Runge-Kutta-Fehlberg 4th/5th-order adaptive integration method.
 *
 * The step size is adjusted so that the integration error per step stays below
 * [SimulationContext.maxAbsError] + [SimulationContext.maxRelError] * |state|.
 * When the error is too large and the current step exceeds [SimulationContext.dtMin],
 * the step is halved and retried. Otherwise a fixed-step result is accepted.
 *
 * Reference: Fehlberg, E.: "Klassische Runge-Kutta-Formeln vierter und niedriger Ordnung
 * mit Schrittweiten-Kontrolle", Computing Vol. 6, 1970.
 */
internal class RKF45Integrator : Integrator {

    companion object {
        // Stage weights (intermediate state contributions)
        private const val A21 = 1.0 / 4.0
        private const val A31 = 3.0 / 32.0
        private const val A32 = 9.0 / 32.0
        private const val A41 = 1932.0 / 2197.0
        private const val A42 = -7200.0 / 2197.0
        private const val A43 = 7296.0 / 2197.0
        private const val A51 = 439.0 / 216.0
        private const val A52 = -8.0
        private const val A53 = 3680.0 / 513.0
        private const val A54 = -845.0 / 4104.0
        private const val A61 = -8.0 / 27.0
        private const val A62 = 2.0
        private const val A63 = -3544.0 / 2565.0
        private const val A64 = 1859.0 / 4104.0
        private const val A65 = -11.0 / 40.0

        // 5th-order solution weights (b coefficients)
        private const val B1 = 16.0 / 135.0
        private const val B3 = 6656.0 / 12825.0
        private const val B4 = 28561.0 / 56430.0
        private const val B5 = -9.0 / 50.0
        private const val B6 = 2.0 / 55.0

        // Time fractions for intermediate stages
        private const val C2 = 1.0 / 4.0
        private const val C3 = 3.0 / 8.0
        private const val C4 = 12.0 / 13.0
        // C5 = 1.0 (full step)
        private const val C6 = 1.0 / 2.0

        // Error estimation: difference between 4th and 5th order solutions
        private const val E1 = 25.0 / 216.0 - B1
        private const val E3 = 1408.0 / 2565.0 - B3
        private const val E4 = 2197.0 / 4104.0 - B4
        private const val E5 = -1.0 / 5.0 - B5
        private const val E6 = 0.0 - B6
    }

    override fun integrate(
        monitor: ContinuousMonitor,
        context: SimulationContext,
        dtNow: Double,
        targetTime: Double
    ): Double {
        val lastTime = context.currentTime
        val dtFull = dtNow
        var h = dtNow
        val maxAbsError = abs(context.maxAbsError)
        val maxRelError = abs(context.maxRelError)

        // k1 = h * rate (rates already computed by ContinuousMonitor before this call)
        var v = context.firstVar
        while (v != null) {
            v.k1 = h * v.rate
            v = v._suc
        }

        var errorRatio: Double
        do {
            // Stage 2: state = oldState + a21*k1, time = lastTime + c2*h
            v = context.firstVar
            while (v != null) {
                v.state = v._oldState + A21 * v.k1
                v = v._suc
            }
            context.currentTime = lastTime + C2 * h
            resetRates(context)
            monitor.computeDerivatives()

            v = context.firstVar
            while (v != null) {
                v.k2 = h * v.rate
                v.state = v._oldState + (A31 * v.k1 + A32 * v.k2)
                v = v._suc
            }

            // Stage 3: time = lastTime + c3*h
            context.currentTime = lastTime + C3 * h
            resetRates(context)
            monitor.computeDerivatives()

            v = context.firstVar
            while (v != null) {
                v.k3 = h * v.rate
                v.state = v._oldState + (A41 * v.k1 + A42 * v.k2 + A43 * v.k3)
                v = v._suc
            }

            // Stage 4: time = lastTime + c4*h
            context.currentTime = lastTime + C4 * h
            resetRates(context)
            monitor.computeDerivatives()

            v = context.firstVar
            while (v != null) {
                v.k4 = h * v.rate
                v.state = v._oldState + (A51 * v.k1 + A52 * v.k2 + A53 * v.k3 + A54 * v.k4)
                v = v._suc
            }

            // Stage 5: time = lastTime + h (full step)
            context.currentTime = lastTime + h
            resetRates(context)
            monitor.computeDerivatives()

            v = context.firstVar
            while (v != null) {
                v.k5 = h * v.rate
                v.state = v._oldState + (A61 * v.k1 + A62 * v.k2 + A63 * v.k3 + A64 * v.k4 + A65 * v.k5)
                v = v._suc
            }

            // Stage 6: time = lastTime + c6*h
            context.currentTime = lastTime + C6 * h
            resetRates(context)
            monitor.computeDerivatives()

            // Compute 5th-order solution and error estimate
            v = context.firstVar
            while (v != null) {
                v.k6 = h * v.rate
                v.ds = B1 * v.k1 + B3 * v.k3 + B4 * v.k4 + B5 * v.k5 + B6 * v.k6
                v.state = v._oldState + v.ds
                v = v._suc
            }

            // Compute error ratio (highest error across all variables)
            errorRatio = 64.0
            v = context.firstVar
            while (v != null) {
                val err = abs(E1 * v.k1 + E3 * v.k3 + E4 * v.k4 + E5 * v.k5 + E6 * v.k6)
                val tol = maxAbsError + 0.5 * maxRelError * (abs(v._oldState) + abs(v.state))
                if (errorRatio * err > tol) {
                    errorRatio = tol / err
                }
                if (errorRatio < 1.0) break
                v = v._suc
            }

            if (errorRatio < 1.0) {
                // Step rejected: halve the step size and retry
                if (h <= context.dtMin) {
                    // Cannot reduce further — accept step at minimum size
                    // (mirrors jDisco: error(...) but we accept rather than crash in pure-Kotlin)
                    break
                }
                val newH: Double
                val f: Double
                if (h * 0.5 < context.dtMin) {
                    f = context.dtMin / h
                    newH = context.dtMin
                } else {
                    f = 0.5
                    newH = h * 0.5
                }
                h = newH
                // Scale k1 by f (k1 = h_new * rate_0, previously k1 = h_old * rate_0)
                v = context.firstVar
                while (v != null) {
                    v.k1 *= f
                    v = v._suc
                }
                // Reset states to oldState before retry
                v = context.firstVar
                while (v != null) {
                    v.state = v._oldState
                    v = v._suc
                }
            }
        } while (errorRatio < 1.0)

        // Accept the step: advance time to lastTime + h (5th-order solution already in state)
        context.currentTime = lastTime + h

        // Recompute derivatives at the accepted state/time
        resetRates(context)
        monitor.computeDerivatives()

        // Compute next suggested step size (only when we used the full requested step)
        return if (h == dtFull) {
            var dtNext = strictPow(0.5 * errorRatio, 1.0 / 5.0) * h
            if (dtNext > context.dtMax) dtNext = context.dtMax
            if (dtNext < context.dtMin) dtNext = context.dtMin
            dtNext
        } else {
            h  // return the reduced step size as suggestion
        }
    }

    private fun resetRates(context: SimulationContext) {
        var v = context.firstVar
        while (v != null) {
            v.rate = 0.0
            v = v._suc
        }
    }
}
