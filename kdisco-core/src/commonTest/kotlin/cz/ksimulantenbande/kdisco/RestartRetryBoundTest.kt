// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isCloseTo
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * kdisco#85: the inner integration retry loop of [Simulation.run] is bounded by
 * [Simulation.integrationRetryLimit], reports [SimulationEvent.Diagnostic.IntegrationRetryThresholdCrossed]
 * once per boundary at [Simulation.integrationRetryWarnThreshold], and fails with
 * [IntegrationRetryLimitException] when the limit is exceeded.
 *
 * Both restart kinds are driven by user code that queues a fresh, far-future turn on every retry:
 * a [Continuous.derivatives] override for [IntegrationRestartKind.MUTATION] (the step unwinds, the
 * clock never moves), and a [Process.waitUntil] condition for [IntegrationRestartKind.NOTICE_RELEASE]
 * (the step-end state stands, the clock creeps forward but never reaches the boundary).
 */
class RestartRetryBoundTest {

    /** A process whose only purpose is to be queued far in the future by user code. */
    private class Pinger : Process() {
        override suspend fun actions() = Unit
    }

    private class Starter(private val x: Variable, private val motion: Continuous) : Process() {
        override suspend fun actions() {
            x.start()
            motion.start()
        }
    }

    /**
     * Continuous whose every derivatives call queues a fresh [Pinger] far ahead, so every step is
     * an [IntegrationRestartKind.MUTATION] restart and the clock never leaves t=0.
     */
    private class MutatingMotion(private val x: Variable) : Continuous() {
        val pingers = mutableListOf<Pinger>()

        override fun derivatives() {
            x.rate = 1.0
            val pinger = Pinger()
            pingers += pinger
            Process.activate(pinger, delay = FAR)
        }
    }

    private class PlainMotion(private val x: Variable) : Continuous() {
        override fun derivatives() {
            x.rate = 1.0
        }
    }

    /**
     * Parks on a never-satisfied condition that queues a fresh far-future [Pinger] each time it is
     * tested at an integration step end, while [budget] lasts. The condition is tested exactly once
     * per accepted step (discrete events all happen at t=0 and are skipped), so each spent unit of
     * budget is exactly one [IntegrationRestartKind.NOTICE_RELEASE] restart.
     */
    private class MutatingWaiter(private var budget: Int) : Process() {
        val pingers = mutableListOf<Pinger>()

        override suspend fun actions() {
            waitUntil {
                if (time() > 0.0 && budget > 0) {
                    budget--
                    val pinger = Pinger()
                    pingers += pinger
                    Process.activate(pinger, delay = FAR)
                }
                false
            }
        }
    }

    @Test
    fun defaultsAreGenerous() {
        val sim = simulation {}
        assertThat(sim.integrationRetryLimit).isEqualTo(10_000)
        assertThat(sim.integrationRetryWarnThreshold).isEqualTo(100)
    }

    @Test
    fun retryLimitMustBePositive() {
        val sim = simulation {}
        assertFailure { sim.integrationRetryLimit = 0 }.isInstanceOf<IllegalArgumentException>()
        assertFailure { sim.integrationRetryLimit = -1 }.isInstanceOf<IllegalArgumentException>()
        sim.integrationRetryLimit = 1
        assertThat(sim.integrationRetryLimit).isEqualTo(1)
    }

    @Test
    fun warnThresholdMustBePositive() {
        val sim = simulation {}
        assertFailure { sim.integrationRetryWarnThreshold = 0 }.isInstanceOf<IllegalArgumentException>()
        assertFailure { sim.integrationRetryWarnThreshold = -1 }.isInstanceOf<IllegalArgumentException>()
        sim.integrationRetryWarnThreshold = 1
        assertThat(sim.integrationRetryWarnThreshold).isEqualTo(1)
    }

    @Test
    fun mutationRestartOnEveryRetryFailsAfterTheLimit() = runTest {
        val x = Variable(0.0)
        val motion = MutatingMotion(x)
        val diagnostics = mutableListOf<SimulationEvent.Diagnostic>()
        val sourceAtDiagnostic = mutableListOf<Process?>()
        val failures = mutableListOf<Process>()

        val sim = simulation {
            integrationRetryLimit = LIMIT
            integrationRetryWarnThreshold = THRESHOLD
            onEvent { event ->
                if (event is SimulationEvent.Diagnostic) {
                    diagnostics += event
                    sourceAtDiagnostic += motion.pingers.lastOrNull()
                }
            }
            onProcessFailure { process, _ -> failures += process }
            Process.activate(Starter(x, motion))
        }

        val failure = assertFailure { sim.run(END_TIME) }
            .isInstanceOf<IntegrationRetryLimitException>()
        failure.transform { it.retries }.isEqualTo(LIMIT + 1)
        failure.transform { it.kind }.isEqualTo(IntegrationRestartKind.MUTATION)
        // A mutation restart unwinds the step, so the clock never left the boundary's start.
        failure.transform { it.time }.isEqualTo(0.0)
        failure.transform { it.source }.isSameInstanceAs(motion.pingers.last())
        failure.isInstanceOf<DiscoException>()

        assertThat(diagnostics).hasSize(1)
        val crossed = diagnostics.single()
        assertThat(crossed).isInstanceOf<SimulationEvent.Diagnostic.IntegrationRetryThresholdCrossed>()
        crossed as SimulationEvent.Diagnostic.IntegrationRetryThresholdCrossed
        assertThat(crossed.retries).isEqualTo(THRESHOLD)
        assertThat(crossed.kind).isEqualTo(IntegrationRestartKind.MUTATION)
        assertThat(crossed.time).isEqualTo(0.0)
        assertThat(crossed.process).isNotNull().isSameInstanceAs(sourceAtDiagnostic.single()!!)
        assertThat(crossed.message).contains("MUTATION")
        assertThat(crossed.message).contains("$THRESHOLD")
        assertThat(crossed.message).contains(crossed.process.toString())

        // Not a process failure.
        assertThat(failures).isEmpty()
        // Teardown ran: the queue is cleared and the run is over.
        assertThat(sim.scheduledEventCount()).isEqualTo(0)
        assertThat(sim.isRunning).isFalse()
    }

    @Test
    fun noticeReleaseRestartOnEveryRetryFailsAfterTheLimit() = runTest {
        val x = Variable(0.0)
        val motion = PlainMotion(x)
        val pingers = mutableListOf<Pinger>()
        val diagnostics = mutableListOf<SimulationEvent.Diagnostic>()
        val sourceAtDiagnostic = mutableListOf<Process?>()
        val failures = mutableListOf<Process>()

        val waiter = object : Process() {
            override suspend fun actions() {
                waitUntil {
                    // Never satisfied, but queues a fresh far-future turn every time it is tested.
                    val pinger = Pinger()
                    pingers += pinger
                    Process.activate(pinger, delay = FAR)
                    false
                }
            }
        }

        val sim = simulation {
            integrationRetryLimit = LIMIT
            integrationRetryWarnThreshold = THRESHOLD
            onEvent { event ->
                if (event is SimulationEvent.Diagnostic) {
                    diagnostics += event
                    sourceAtDiagnostic += pingers.lastOrNull()
                }
            }
            onProcessFailure { process, _ -> failures += process }
            Process.activate(Starter(x, motion))
            Process.activate(waiter)
        }

        val failure = assertFailure { sim.run(END_TIME) }
            .isInstanceOf<IntegrationRetryLimitException>()
        failure.transform { it.retries }.isEqualTo(LIMIT + 1)
        failure.transform { it.kind }.isEqualTo(IntegrationRestartKind.NOTICE_RELEASE)
        // The notice-release restart keeps the accepted step-end state, so the clock has moved.
        failure.transform { it.time }.isGreaterThan(0.0)
        failure.transform { it.source }.isSameInstanceAs(pingers.last())

        assertThat(diagnostics).hasSize(1)
        val crossed = diagnostics.single() as SimulationEvent.Diagnostic.IntegrationRetryThresholdCrossed
        assertThat(crossed.retries).isEqualTo(THRESHOLD)
        assertThat(crossed.kind).isEqualTo(IntegrationRestartKind.NOTICE_RELEASE)
        assertThat(crossed.process).isNotNull().isSameInstanceAs(sourceAtDiagnostic.single()!!)
        assertThat(crossed.message).contains("NOTICE_RELEASE")

        assertThat(failures).isEmpty()
        assertThat(sim.scheduledEventCount()).isEqualTo(0)
        assertThat(sim.isRunning).isFalse()
    }

    @Test
    fun crossingTheThresholdBelowTheLimitWarnsOnceAndCompletes() = runTest {
        val x = Variable(0.0)
        val waiter = MutatingWaiter(budget = THRESHOLD + 1)
        val diagnostics = mutableListOf<SimulationEvent.Diagnostic>()

        val sim = simulation {
            integrationRetryLimit = LIMIT
            integrationRetryWarnThreshold = THRESHOLD
            onEvent { if (it is SimulationEvent.Diagnostic) diagnostics += it }
            Process.activate(Starter(x, PlainMotion(x)))
            Process.activate(waiter)
        }

        assertThat(sim.run(END_TIME)).isTrue()
        assertThat(waiter.pingers).hasSize(THRESHOLD + 1)
        assertThat(diagnostics).hasSize(1)
        val crossed = diagnostics.single() as SimulationEvent.Diagnostic.IntegrationRetryThresholdCrossed
        assertThat(crossed.retries).isEqualTo(THRESHOLD)
        assertThat(crossed.process).isNotNull().isSameInstanceAs(waiter.pingers[THRESHOLD - 1])
        assertThat(x.state).isCloseTo(END_TIME, 1e-9)
    }

    @Test
    fun runUnderTheThresholdEmitsNothingAndIsUnchanged() = runTest {
        suspend fun runOnce(configure: Simulation.() -> Unit): Pair<List<String>, Double> {
            val log = mutableListOf<String>()
            val x = Variable(0.0)
            val waiter = MutatingWaiter(budget = THRESHOLD - 1)
            val sim = simulation {
                configure()
                onEvent { event -> log += "${event::class.simpleName}@${event.time}" }
                Process.activate(Starter(x, PlainMotion(x)))
                Process.activate(waiter)
            }
            assertThat(sim.run(END_TIME)).isTrue()
            assertThat(waiter.pingers).hasSize(THRESHOLD - 1)
            return log to x.state
        }

        val bounded = runOnce {
            integrationRetryLimit = LIMIT
            integrationRetryWarnThreshold = THRESHOLD
        }
        val defaults = runOnce {}

        assertThat(bounded.first.filter { it.startsWith("IntegrationRetry") }).isEmpty()
        assertThat(bounded).isEqualTo(defaults)
        assertThat(bounded.second).isCloseTo(END_TIME, 1e-9)
    }

    @Test
    fun diagnosticMessageNamesAnUnknownSourceWhenThereIsNone() {
        val diagnostic = SimulationEvent.Diagnostic.IntegrationRetryThresholdCrossed(
            time = 2.5,
            process = null,
            retries = 7,
            kind = IntegrationRestartKind.MUTATION,
        )
        assertThat(diagnostic.message).contains("unknown")
        assertThat(diagnostic.message).contains("2.5")
        assertThat(diagnostic.message).contains("7")
        val exception = IntegrationRetryLimitException(2.5, 7, IntegrationRestartKind.MUTATION, null)
        assertThat(exception.message).isEqualTo(diagnostic.message)
    }

    private companion object {
        const val LIMIT = 5
        const val THRESHOLD = 3
        const val END_TIME = 10.0
        const val FAR = 1_000.0
    }
}
