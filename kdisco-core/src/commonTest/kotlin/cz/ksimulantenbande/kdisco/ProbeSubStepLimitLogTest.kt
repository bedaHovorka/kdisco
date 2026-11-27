// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameInstanceAs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * kdisco#88: [ContinuousMonitor.probeStateAt] reports
 * [SimulationEvent.Diagnostic.ProbeSubStepLimitReached] when it stops at its sub-step limit with the
 * clock still short of the probe target, and stays silent otherwise.
 */
class ProbeSubStepLimitLogTest {

    /**
     * Euler integrator whose first [slowCalls] calls after the main step advance the clock by only
     * [SLOW_ADVANCE] (modelling an integrator that keeps shrinking the requested sub-step); every
     * other call advances the full [dtNow]. The very first call is the main step and is never slow.
     */
    private class SlowProbeIntegrator(private val slowCalls: Int) : Integrator {
        private var calls = 0

        override fun integrate(
            monitor: ContinuousMonitor,
            context: SimulationContext,
            dtNow: Double,
            targetTime: Double,
        ): Double {
            val dt = if (calls in 1..slowCalls) minOf(SLOW_ADVANCE, dtNow) else dtNow
            calls++
            var v = context.firstVar
            while (v != null) {
                v.state += v.rate * dt
                v = v._suc
            }
            context.currentTime += dt
            return dtNow
        }
    }

    private class Waiter(private val x: Variable, private val motion: Continuous) : Process() {
        var resumedAt = Double.NaN

        override suspend fun actions() {
            x.start()
            motion.start()
            waitCrossing { THRESHOLD - x.state }
            resumedAt = time()
            motion.stop()
        }
    }

    @Test
    fun limitHitReportsExactlyOneDiagnosticWithVariableAndInterval() = runTest {
        val x = Variable(0.0)
        val diagnostics = mutableListOf<SimulationEvent.Diagnostic>()
        val sim = build(x, SlowProbeIntegrator(MAX_PROBE_SUB_STEPS), diagnostics)
        sim.run(END_TIME)

        assertThat(diagnostics).hasSize(1)
        val d = diagnostics.single()
        assertThat(d).isInstanceOf<SimulationEvent.Diagnostic.ProbeSubStepLimitReached>()
        d as SimulationEvent.Diagnostic.ProbeSubStepLimitReached
        assertThat(d.variables).hasSize(1)
        assertThat(d.variables.single()).isSameInstanceAs(x)
        assertThat(d.from).isEqualTo(0.0)
        // The first probe is the bisection midpoint of the step [0, END_TIME].
        assertThat(d.to).isEqualTo(END_TIME / 2)
        // The event time is the committed step start, not the speculative probe clock ...
        assertThat(d.time).isEqualTo(0.0)
        // ... which is carried in reached.
        assertThat(d.reached).isCloseTo(MAX_PROBE_SUB_STEPS * SLOW_ADVANCE, 1e-12)
        assertThat(d.message).contains("1000")
        assertThat(d.message).contains("0.0")
        assertThat(d.message).contains((END_TIME / 2).toString())
        assertThat(d.message).contains(d.reached.toString())
    }

    /**
     * Two crossings in one step, located in registration order: the later one (8.0) first — its
     * first probe stalls at the sub-step limit with the probe clock near 0.001 — then the earlier
     * one (0.0005), which resumes its waiter before that probe clock. The diagnostic must not carry
     * the probe clock as its time, or it would precede an event at an earlier time.
     */
    @Test
    fun diagnosticKeepsOnEventTimesNonDecreasingAcrossTwoCrossings() = runTest {
        val x = Variable(0.0)
        val motion = object : Continuous() {
            override fun derivatives() {
                x.rate = 1.0
            }
        }
        val events = mutableListOf<SimulationEvent>()
        val resumed = mutableListOf<Pair<String, Double>>()

        fun waiter(name: String, level: Double) = object : Process() {
            override suspend fun actions() {
                waitCrossing { level - x.state }
                resumed += name to time()
                emitCustom(name)
            }
        }

        val sim = simulation {
            dtMax = END_TIME
            integrator = SlowProbeIntegrator(MAX_PROBE_SUB_STEPS)
            onEvent { events += it }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start()
                    motion.start()
                }
            })
            Process.activate(waiter("late", LATE_CROSSING))
            Process.activate(waiter("early", EARLY_CROSSING))
        }
        sim.run(END_TIME)

        val diagnostics = events.filterIsInstance<SimulationEvent.Diagnostic.ProbeSubStepLimitReached>()
        assertThat(diagnostics).hasSize(1)
        assertThat(diagnostics.single().reached).isGreaterThan(EARLY_CROSSING)
        assertThat(resumed.map { it.first }).containsExactly("early", "late")
        assertThat(resumed[0].second).isCloseTo(EARLY_CROSSING, 1e-6)
        assertThat(resumed[1].second).isCloseTo(LATE_CROSSING, 1e-6)
        val times = events.map { it.time }
        assertThat(times).isEqualTo(times.sorted())
    }

    @Test
    fun probeReachingTargetOnLastSubStepReportsNothing() = runTest {
        val x = Variable(0.0)
        val diagnostics = mutableListOf<SimulationEvent.Diagnostic>()
        // One call fewer than the limit is slow, so the 1000th call jumps to the target.
        build(x, SlowProbeIntegrator(MAX_PROBE_SUB_STEPS - 1), diagnostics).run(END_TIME)
        assertThat(diagnostics).isEmpty()
    }

    @Test
    fun normalRunReportsNothing() = runTest {
        val x = Variable(0.0)
        val diagnostics = mutableListOf<SimulationEvent.Diagnostic>()
        build(x, null, diagnostics).run(END_TIME)
        assertThat(diagnostics).isEmpty()
    }

    private fun build(
        x: Variable,
        fake: Integrator?,
        diagnostics: MutableList<SimulationEvent.Diagnostic>,
    ): Simulation {
        val motion = object : Continuous() {
            override fun derivatives() {
                x.rate = 1.0
            }
        }
        return simulation {
            dtMax = END_TIME
            if (fake != null) integrator = fake
            onEvent { if (it is SimulationEvent.Diagnostic) diagnostics += it }
            Process.activate(Waiter(x, motion))
        }
    }

    private companion object {
        const val MAX_PROBE_SUB_STEPS = 1_000
        const val SLOW_ADVANCE = 1e-6
        const val THRESHOLD = 3.0
        const val LATE_CROSSING = 8.0
        const val EARLY_CROSSING = 0.0005
        const val END_TIME = 10.0
    }
}
