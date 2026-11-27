// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ProcessFailureUncaughtTest {

    @Test
    fun unhandledFailureStillReachesUncaughtExceptionHandlerAndRunContinues() {
        val boom = IllegalStateException("nobody listens")
        val events = mutableListOf<SimulationEvent>()
        var survivorFinishedAt = -1.0
        var completed = false
        val sim = simulation {
            onEvent { if (it is SimulationEvent.ProcessFailed) events += it }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                    throw boom
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(4.0)
                    survivorFinishedAt = time()
                }
            })
        }
        // With no onProcessFailure listener and stopOnProcessFailure off, the failure is not
        // swallowed: it goes to the uncaught-exception handler, which runTest turns into a failure.
        val outcome = runCatching { runTest { completed = sim.run(10.0) } }

        assertThat(outcome.exceptionOrNull()).isSameInstanceAs(boom)
        assertThat(completed).isTrue()
        assertThat(events).hasSize(1)
        assertThat(survivorFinishedAt).isEqualTo(4.0)
    }

    @Test
    fun throwingFailureListenerWithoutStopOnProcessFailurePropagatesAndRunContinues() {
        val boom = IllegalStateException("fatal")
        val listenerBoom = IllegalStateException("listener broke")
        var survivorFinishedAt = -1.0
        var completed = false
        val sim = simulation {
            onProcessFailure { _, _ -> throw listenerBoom }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                    throw boom
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(4.0)
                    survivorFinishedAt = time()
                }
            })
        }
        // Without stopOnProcessFailure the listener's exception is not swallowed: it ends the
        // failing process's coroutine and reaches the uncaught-exception handler.
        val outcome = runCatching { runTest { completed = sim.run(10.0) } }

        assertThat(outcome.exceptionOrNull()).isSameInstanceAs(listenerBoom)
        assertThat(completed).isTrue()
        assertThat(survivorFinishedAt).isEqualTo(4.0)
    }

    @Test
    fun releaseInCleanupOfNormallyEndedRunIsNotAProcessFailure() {
        val failures = mutableListOf<Throwable>()
        val failedEvents = mutableListOf<SimulationEvent>()
        var completed = false
        val resource = Resource()
        val sim = simulation {
            stopOnProcessFailure = true
            onProcessFailure { _, failure -> failures += failure }
            onEvent { if (it is SimulationEvent.ProcessFailed) failedEvents += it }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    resource.reserve()
                    try {
                        hold(100.0)
                    } finally {
                        resource.release()
                    }
                }
            })
        }
        // The run reaches endTime normally. The release in the process's finally runs while run
        // tears down — after the event loop — where engine calls are not supported; that is not a
        // failure of the process, so the run neither throws nor notifies. The throwable goes to the
        // uncaught-exception handler exactly as it did before failures were reported.
        val outcome = runCatching { runTest { completed = sim.run(10.0) } }

        assertThat(completed).isTrue()
        assertThat(outcome.exceptionOrNull()).isNotNull().isInstanceOf(DiscoException::class)
        assertThat(failures).isEmpty()
        assertThat(failedEvents).isEmpty()
        assertThat(sim.isStopRequested()).isFalse()
    }

    @Test
    fun failureDuringEndOfRunCleanupIsNotAProcessFailureAndKeepsTheFirst() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")
        val seen = mutableListOf<Throwable>()
        var thrown: Throwable? = null
        val sim = simulation {
            stopOnProcessFailure = true
            onProcessFailure { _, failure -> seen += failure }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    try {
                        passivate()
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // Cleanup of the cancelled run fails as well.
                        throw second
                    }
                }
            })
            Process.activate(
                object : Process() {
                    override suspend fun actions(): Unit = throw first
                },
                delay = 1.0,
            )
        }
        val outcome = runCatching { runTest { thrown = runCatching { sim.run(100.0) }.exceptionOrNull() } }

        assertThat(thrown).isNotNull().isInstanceOf(ProcessFailureException::class)
        assertThat(thrown?.cause).isSameInstanceAs(first)
        assertThat(thrown?.suppressedExceptions.orEmpty()).isEmpty()
        assertThat(seen).containsExactly(first)
        assertThat(outcome.exceptionOrNull()).isSameInstanceAs(second)
    }

    @Test
    fun teardownFailureWithoutStopOnProcessFailureSkipsListeners() {
        val late = IllegalStateException("late")
        val seen = mutableListOf<Throwable>()
        var completed = false
        val sim = simulation {
            onProcessFailure { _, failure -> seen += failure }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    try {
                        passivate()
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        throw late
                    }
                }
            })
        }
        val outcome = runCatching { runTest { completed = sim.run(5.0) } }

        assertThat(completed).isTrue()
        assertThat(seen).isEmpty()
        assertThat(outcome.exceptionOrNull()).isSameInstanceAs(late)
    }

    @Test
    fun throwingFailureCallbackWithoutStopDoesNotSkipLaterCallbacks() {
        val boom = IllegalStateException("fatal")
        val firstBoom = IllegalStateException("first callback broke")
        val secondBoom = IllegalStateException("second callback broke")
        val calls = mutableListOf<String>()
        var survivorFinishedAt = -1.0
        var completed = false
        val sim = simulation {
            onProcessFailure { _, _ ->
                calls += "first"
                throw firstBoom
            }
            onProcessFailure { _, _ ->
                calls += "second"
                throw secondBoom
            }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                    throw boom
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(4.0)
                    survivorFinishedAt = time()
                }
            })
        }
        // Every callback is attempted; the first exception thrown ends the failing process's
        // coroutine with the later ones suppressed on it.
        val outcome = runCatching { runTest { completed = sim.run(10.0) } }

        assertThat(calls).containsExactly("first", "second")
        assertThat(outcome.exceptionOrNull()).isSameInstanceAs(firstBoom)
        assertThat(firstBoom.suppressedExceptions).containsExactly(secondBoom)
        assertThat(completed).isTrue()
        assertThat(survivorFinishedAt).isEqualTo(4.0)
    }

    @Test
    fun throwingProcessFailedEventListenerWithoutStopStillLetsCallbacksRun() {
        val boom = IllegalStateException("fatal")
        val listenerBoom = IllegalStateException("event listener broke")
        val seen = mutableListOf<Throwable>()
        var completed = false
        val sim = simulation {
            onEvent { if (it is SimulationEvent.ProcessFailed) throw listenerBoom }
            onProcessFailure { _, failure -> seen += failure }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                    throw boom
                }
            })
        }
        val outcome = runCatching { runTest { completed = sim.run(10.0) } }

        assertThat(seen).containsExactly(boom)
        assertThat(outcome.exceptionOrNull()).isSameInstanceAs(listenerBoom)
        assertThat(listenerBoom.suppressedExceptions).isEmpty()
        assertThat(completed).isTrue()
    }
}
