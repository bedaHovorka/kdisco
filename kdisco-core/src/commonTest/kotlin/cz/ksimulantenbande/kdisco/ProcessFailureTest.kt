// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ProcessFailureTest {

    private class Boom(message: String) : IllegalStateException(message)

    private class Failing(private val at: Double, private val failure: Throwable) : Process() {
        override suspend fun actions() {
            hold(at)
            throw failure
        }
    }

    @Test
    fun hookReceivesFailedProcessAndThrowableExactlyOnce() = runTest {
        val boom = Boom("track still held")
        val failing = Failing(3.0, boom)
        val failures = mutableListOf<Triple<Process, Throwable, Double>>()
        var terminatedWhenNotified: Boolean? = null
        val survivorLog = mutableListOf<Double>()

        val sim = simulation {
            onProcessFailure { process, failure ->
                failures += Triple(process, failure, time())
                terminatedWhenNotified = process.isTerminated()
            }
            Process.activate(failing)
            Process.activate(object : Process() {
                override suspend fun actions() {
                    repeat(5) {
                        hold(2.0)
                        survivorLog += time()
                    }
                }
            })
        }
        assertThat(sim.run(100.0)).isTrue()

        assertThat(failures).hasSize(1)
        assertThat(failures[0].first).isSameInstanceAs(failing)
        assertThat(failures[0].second).isSameInstanceAs(boom)
        assertThat(failures[0].third).isEqualTo(3.0)
        assertThat(terminatedWhenNotified).isEqualTo(false)
        assertThat(failing.isTerminated()).isTrue()
        // The failure does not cancel the other processes (SupervisorJob default).
        assertThat(survivorLog).isEqualTo(listOf(2.0, 4.0, 6.0, 8.0, 10.0))
        assertThat(sim.time()).isEqualTo(10.0)
    }

    @Test
    fun processFailedEventPrecedesProcessTerminated() = runTest {
        val boom = Boom("first activation")
        val failing = object : Process() {
            override suspend fun actions(): Unit = throw boom
        }
        val events = mutableListOf<SimulationEvent>()
        runSimulation(endTime = 10.0) {
            onEvent { if (it.process === failing) events += it }
            onProcessFailure { _, _ -> }
            Process.activate(failing)
        }

        assertThat(events.map { it::class }).isEqualTo(
            listOf(
                SimulationEvent.ProcessActivated::class,
                SimulationEvent.ProcessFailed::class,
                SimulationEvent.ProcessTerminated::class,
            ),
        )
        val failed = events[1] as SimulationEvent.ProcessFailed
        assertThat(failed.failure).isSameInstanceAs(boom)
        assertThat(failed.time).isEqualTo(0.0)
    }

    @Test
    fun stopOnProcessFailureEndsRunAtFailureTimeAndThrows() = runTest {
        val boom = Boom("fatal")
        val failing = Failing(3.0, boom)
        val hookCalls = mutableListOf<Process>()
        var laterRan = false
        val later = object : Process() {
            override suspend fun actions() {
                laterRan = true
            }
        }
        val sim = simulation {
            stopOnProcessFailure = true
            onProcessFailure { process, _ -> hookCalls += process }
            Process.activate(failing)
            Process.activate(later, delay = 5.0)
        }

        val thrown = runCatching { sim.run(100.0) }.exceptionOrNull()
        assertThat(thrown).isNotNull().isInstanceOf(ProcessFailureException::class)
        val failure = thrown as ProcessFailureException
        assertThat(failure.process).isSameInstanceAs(failing)
        assertThat(failure.time).isEqualTo(3.0)
        assertThat(failure.cause).isSameInstanceAs(boom)
        assertThat(hookCalls).containsExactly(failing)
        assertThat(sim.time()).isEqualTo(3.0)
        assertThat(sim.isRunning).isFalse()
        assertThat(laterRan).isFalse()
        assertThat(later.isTerminated()).isTrue()
        assertThat(sim.scheduledEventCount()).isEqualTo(0)
    }

    @Test
    fun stopOnProcessFailureWithoutHookStillStopsAndThrows() = runTest {
        val boom = Boom("unobserved")
        val sim = simulation {
            stopOnProcessFailure = true
            Process.activate(Failing(1.5, boom))
        }
        val thrown = runCatching { sim.run(100.0) }.exceptionOrNull()
        assertThat(thrown).isNotNull().isInstanceOf(ProcessFailureException::class)
        assertThat(thrown?.cause).isSameInstanceAs(boom)
        assertThat(sim.time()).isEqualTo(1.5)
    }

    @Test
    fun terminateAndEndOfRunCancellationAreNotFailures() = runTest {
        val failures = mutableListOf<Throwable>()
        val sim = simulation {
            stopOnProcessFailure = true
            onProcessFailure { _, failure -> failures += failure }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                    terminate()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    passivate()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(50.0)
                }
            })
        }
        assertThat(sim.run(10.0)).isTrue()
        assertThat(failures).isEmpty()
    }

    @Test
    fun throwingFailureListenerDoesNotDisableStopOnProcessFailure() = runTest {
        val boom = Boom("fatal")
        val listenerBoom = Boom("listener broke")
        val failing = Failing(3.0, boom)
        var laterRan = false
        val sim = simulation {
            stopOnProcessFailure = true
            onProcessFailure { _, _ -> throw listenerBoom }
            Process.activate(failing)
            Process.activate(
                object : Process() {
                    override suspend fun actions() {
                        laterRan = true
                    }
                },
                delay = 5.0,
            )
        }

        val thrown = runCatching { sim.run(100.0) }.exceptionOrNull()
        assertThat(thrown).isNotNull().isInstanceOf(ProcessFailureException::class)
        val failure = thrown as ProcessFailureException
        assertThat(failure.process).isSameInstanceAs(failing)
        assertThat(failure.time).isEqualTo(3.0)
        assertThat(failure.cause).isSameInstanceAs(boom)
        assertThat(failure.suppressedExceptions).containsExactly(listenerBoom)
        assertThat(sim.time()).isEqualTo(3.0)
        assertThat(sim.isStopRequested()).isTrue()
        assertThat(laterRan).isFalse()
    }

    @Test
    fun throwingProcessFailedEventListenerDoesNotDisableStopOnProcessFailure() = runTest {
        val boom = Boom("fatal")
        val listenerBoom = Boom("event listener broke")
        val sim = simulation {
            stopOnProcessFailure = true
            onEvent { if (it is SimulationEvent.ProcessFailed) throw listenerBoom }
            Process.activate(Failing(2.0, boom))
        }

        val thrown = runCatching { sim.run(100.0) }.exceptionOrNull()
        assertThat(thrown).isNotNull().isInstanceOf(ProcessFailureException::class)
        assertThat(thrown?.cause).isSameInstanceAs(boom)
        assertThat((thrown as ProcessFailureException).suppressedExceptions).containsExactly(listenerBoom)
        assertThat(sim.time()).isEqualTo(2.0)
    }

    @Test
    fun callerCancellationFromFailureListenerWinsOverRecordedFailure() = runTest {
        val boom = Boom("fatal")
        lateinit var runner: Job
        var listenerCalls = 0
        val sim = simulation {
            stopOnProcessFailure = true
            onProcessFailure { _, _ ->
                listenerCalls++
                runner.cancel()
            }
            Process.activate(Failing(2.0, boom))
        }
        var thrown: Throwable? = null
        runner = launch { thrown = runCatching { sim.run(100.0) }.exceptionOrNull() }
        runner.join()

        assertThat(listenerCalls).isEqualTo(1)
        assertThat(thrown).isNotNull().isInstanceOf(CancellationException::class)
        assertThat(sim.isRunning).isFalse()
        assertThat(sim.time()).isEqualTo(2.0)
    }

    @Test
    fun throwingProcessFailedEventListenerStillLetsFailureCallbacksRun() = runTest {
        val boom = Boom("fatal")
        val listenerBoom = Boom("event listener broke")
        val seen = mutableListOf<Throwable>()
        val sim = simulation {
            stopOnProcessFailure = true
            onEvent { if (it is SimulationEvent.ProcessFailed) throw listenerBoom }
            onProcessFailure { _, failure -> seen += failure }
            Process.activate(Failing(2.0, boom))
        }

        val thrown = runCatching { sim.run(100.0) }.exceptionOrNull()
        assertThat(seen).containsExactly(boom)
        assertThat(thrown).isNotNull().isInstanceOf(ProcessFailureException::class)
        assertThat((thrown as ProcessFailureException).suppressedExceptions).containsExactly(listenerBoom)
    }

    @Test
    fun throwingFailureCallbackDoesNotSkipLaterCallbacksUnderStop() = runTest {
        val boom = Boom("fatal")
        val firstBoom = Boom("first callback broke")
        val secondBoom = Boom("second callback broke")
        val calls = mutableListOf<String>()
        val sim = simulation {
            stopOnProcessFailure = true
            onProcessFailure { _, _ ->
                calls += "first"
                throw firstBoom
            }
            onProcessFailure { _, _ ->
                calls += "second"
                throw secondBoom
            }
            Process.activate(Failing(2.0, boom))
        }

        val thrown = runCatching { sim.run(100.0) }.exceptionOrNull()
        assertThat(calls).containsExactly("first", "second")
        assertThat(thrown).isNotNull().isInstanceOf(ProcessFailureException::class)
        assertThat(thrown?.cause).isSameInstanceAs(boom)
        assertThat((thrown as ProcessFailureException).suppressedExceptions).containsExactly(firstBoom, secondBoom)
    }
}
