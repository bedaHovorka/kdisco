// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ProcessTest {

    @Test
    fun processActionsExecute() = runTest {
        var executed = false
        val p = object : Process() {
            override suspend fun actions() {
                executed = true
            }
        }
        runSimulation(endTime = 10.0) {
            Process.activate(p)
        }
        assertThat(executed).isTrue()
    }

    @Test
    fun holdAdvancesTime() = runTest {
        val times = mutableListOf<Double>()
        val p = object : Process() {
            override suspend fun actions() {
                times.add(time())
                hold(5.0)
                times.add(time())
                hold(3.0)
                times.add(time())
            }
        }
        runSimulation(endTime = 100.0) {
            Process.activate(p)
        }
        assertThat(times).isEqualTo(listOf(0.0, 5.0, 8.0))
    }

    @Test
    fun processTerminatesAfterActionsComplete() = runTest {
        val p = object : Process() {
            override suspend fun actions() {
                hold(1.0)
            }
        }
        runSimulation(endTime = 10.0) {
            Process.activate(p)
        }
        assertThat(p.terminated()).isTrue()
    }

    @Test
    fun multipleProcessesExecuteInTimeOrder() = runTest {
        // Collect (name, time) pairs separately to avoid JS Double-to-String formatting
        // differences (JS renders 0.0 as "0", JVM as "0.0").
        val log = mutableListOf<Pair<String, Double>>()
        class Logger(private val name: String, private val holdTime: Double) : Process() {
            override suspend fun actions() {
                log.add(name to time())
                hold(holdTime)
                log.add(name to time())
            }
        }
        runSimulation(endTime = 100.0) {
            Process.activate(Logger("A", 10.0))
            Process.activate(Logger("B", 5.0), delay = 2.0)
        }
        assertThat(log).isEqualTo(listOf("A" to 0.0, "B" to 2.0, "B" to 7.0, "A" to 10.0))
    }

    @Test
    fun passivateAndReactivate() = runTest {
        // Collect (label, time) pairs separately to avoid JS Double-to-String formatting
        // differences (JS renders 0.0 as "0", JVM as "0.0").
        val log = mutableListOf<Pair<String, Double>>()
        lateinit var waiter: Process
        val reactivator = object : Process() {
            override suspend fun actions() {
                hold(5.0)
                log.add("reactivating" to time())
                Process.reactivate(waiter)
            }
        }
        waiter = object : Process() {
            override suspend fun actions() {
                log.add("passivating" to time())
                passivate()
                log.add("resumed" to time())
            }
        }
        runSimulation(endTime = 100.0) {
            Process.activate(waiter)
            Process.activate(reactivator)
        }
        assertThat(log).isEqualTo(listOf("passivating" to 0.0, "reactivating" to 5.0, "resumed" to 5.0))
    }

    @Test
    fun terminateStopsProcess() = runTest {
        val log = mutableListOf<String>()
        val p = object : Process() {
            override suspend fun actions() {
                log.add("before")
                terminate()
                log.add("after")  // should NOT execute
            }
        }
        runSimulation(endTime = 10.0) {
            Process.activate(p)
        }
        assertThat(log).isEqualTo(listOf("before"))
        assertThat(p.terminated()).isTrue()
    }

    @Test
    fun activateWithDelay() = runTest {
        val log = mutableListOf<Double>()
        val p = object : Process() {
            override suspend fun actions() {
                log.add(time())
            }
        }
        runSimulation(endTime = 100.0) {
            Process.activate(p, delay = 7.0)
        }
        assertThat(log).isEqualTo(listOf(7.0))
    }

    @Test
    fun processWaitJoinsQueueAndPassivates() = runTest {
        val queue = Head()
        // Collect (label, time) pairs separately to avoid JS Double-to-String formatting
        // differences (JS renders 0.0 as "0", JVM as "0.0").
        val log = mutableListOf<Pair<String, Double>>()
        lateinit var customer: Process
        customer = object : Process() {
            override suspend fun actions() {
                log.add("joining" to time())
                Process.wait(queue)
                log.add("served" to time())
            }
        }
        val server = object : Process() {
            override suspend fun actions() {
                hold(3.0)
                val next = queue.first() as? Process
                next?.out()
                if (next != null) Process.reactivate(next)
            }
        }
        runSimulation(endTime = 100.0) {
            Process.activate(customer)
            Process.activate(server)
        }
        assertThat(log).isEqualTo(listOf("joining" to 0.0, "served" to 3.0))
        assertThat(queue.cardinal()).isEqualTo(0)
    }

    @Test
    fun simulationStopsAtEndTime() = runTest {
        var lastTime = 0.0
        val p = object : Process() {
            override suspend fun actions() {
                while (true) {
                    lastTime = time()
                    hold(1.0)
                }
            }
        }
        runSimulation(endTime = 5.0) {
            Process.activate(p)
        }
        assertThat(lastTime).isLessThanOrEqualTo(5.0)
    }

    @Test
    fun simulationStopMethodHaltsEarly() = runTest {
        var lastTime = 0.0
        runSimulation(endTime = 100.0) {
            val sim = this
            Process.activate(object : Process() {
                override suspend fun actions() {
                    while (true) {
                        lastTime = time()
                        if (time() >= 3.0) {
                            sim.stop()
                            return
                        }
                        hold(1.0)
                    }
                }
            })
        }
        assertThat(lastTime).isEqualTo(3.0)
    }

    @Test
    fun holdRejectsNegativeDuration() = runTest {
        var caught = false
        val p = object : Process() {
            override suspend fun actions() {
                try {
                    hold(-1.0)
                } catch (e: IllegalArgumentException) {
                    caught = true
                }
            }
        }
        runSimulation(endTime = 10.0) {
            Process.activate(p)
        }
        assertThat(caught).isTrue()
    }

    @Test
    fun fifoOrderForSameTimeActivations() = runTest {
        val log = mutableListOf<String>()
        runSimulation(endTime = 10.0) {
            Process.activate(object : Process() {
                override suspend fun actions() { log.add("A") }
            })
            Process.activate(object : Process() {
                override suspend fun actions() { log.add("B") }
            })
            Process.activate(object : Process() {
                override suspend fun actions() { log.add("C") }
            })
        }
        assertThat(log).isEqualTo(listOf("A", "B", "C"))
    }

    @Test
    fun reactivateTerminatedProcessIsNoOp() = runTest {
        var actionsRunCount = 0
        val sim = Simulation.create {
            val p = object : Process() {
                override suspend fun actions() {
                    actionsRunCount++
                    terminate()
                }
            }
            Process.activate(p)
            val reactivator = object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                    Process.reactivate(p)   // should be no-op: p is terminated
                }
            }
            Process.activate(reactivator)
        }
        sim.run(10.0)
        assertThat(actionsRunCount).isEqualTo(1)   // actions() must not run twice
    }

    @Test
    fun reactivateAlreadyScheduledProcessNoDuplicate() = runTest {
        var resumeCount = 0
        val sim = Simulation.create {
            val p = object : Process() {
                override suspend fun actions() {
                    hold(5.0)
                    resumeCount++
                }
            }
            Process.activate(p)
            val reactivator = object : Process() {
                override suspend fun actions() {
                    // p is scheduled to resume at t=5; reactivate it at t=0
                    Process.reactivate(p)
                }
            }
            Process.activate(reactivator)
        }
        sim.run(10.0)
        assertThat(resumeCount).isEqualTo(1)       // resumed exactly once
    }

    @Test
    fun reactivateWhileInWaitUntilNoDuplicateScheduling() = runTest {
        var resumeCount = 0
        var flag = false
        val sim = Simulation.create {
            val waiter = object : Process() {
                override suspend fun actions() {
                    waitUntil { flag }
                    resumeCount++
                }
            }
            Process.activate(waiter)
            // reactivator explicitly reactivates waiter before the flag is set,
            // then sets the flag — waiter should execute actions() body exactly once
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                    Process.reactivate(waiter)  // reactivate mid-waitUntil; clears stale notice
                    flag = true                 // condition now true
                }
            })
        }
        sim.run(10.0)
        assertThat(resumeCount).isEqualTo(1)       // must not execute twice
    }

    @Test
    fun lifecycleActiveHoldReactivateTerminate() = runTest {
        lateinit var p: Process
        val log = mutableListOf<String>()
        p = object : Process() {
            override suspend fun actions() {
                log.add("start-${isActive()}-${isPassivated()}-${isTerminated()}")
                hold(5.0)
                log.add("afterHold-${isActive()}-${isPassivated()}-${isTerminated()}")
                passivate()
            }
        }
        val reactivator = object : Process() {
            override suspend fun actions() {
                hold(10.0)
                log.add("reactivating-p-${p.isActive()}-${p.isPassivated()}-${p.isTerminated()}")
                Process.reactivate(p)
                hold(1.0)
                log.add("afterReactivate-p-${p.isActive()}-${p.isPassivated()}-${p.isTerminated()}")
            }
        }
        val terminator = object : Process() {
            override suspend fun actions() {
                hold(12.0)
                log.add("terminating-p-${p.isActive()}-${p.isPassivated()}-${p.isTerminated()}")
                p.terminate()
            }
        }
        runSimulation(endTime = 100.0) {
            Process.activate(p)
            Process.activate(reactivator)
            Process.activate(terminator)
        }
        assertThat(log).containsExactly(
            "start-true-false-false",
            "afterHold-true-false-false",
            "reactivating-p-false-true-false",
            "afterReactivate-p-false-false-true",
            "terminating-p-false-false-true"
        )
        assertThat(p.isTerminated()).isTrue()
    }

    @Test
    fun terminatedProcessIsNotActiveOrPassivated() = runTest {
        val p = object : Process() {
            override suspend fun actions() {
                terminate()
            }
        }
        runSimulation(endTime = 10.0) {
            Process.activate(p)
        }
        assertThat(p.isTerminated()).isTrue()
        assertThat(p.isActive()).isFalse()
        assertThat(p.isPassivated()).isFalse()
    }

    @Test
    fun passivatedProcessIsNotActive() = runTest {
        lateinit var p: Process
        var observed = ""
        p = object : Process() {
            override suspend fun actions() {
                passivate()
            }
        }
        runSimulation(endTime = 10.0) {
            Process.activate(p)
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                    observed = "${p.isPassivated()}-${p.isActive()}-${p.isTerminated()}"
                }
            })
        }
        assertThat(observed).isEqualTo("true-false-false")
    }

    @Test
    fun processInHoldIsTerminatedAfterSimulationEnd() = runTest {
        val p = object : Process() {
            override suspend fun actions() {
                hold(100.0)
            }
        }
        runSimulation(endTime = 10.0) {
            Process.activate(p)
        }
        assertThat(p.isTerminated()).isTrue()
        assertThat(p.terminated()).isTrue()
        assertThat(p.isActive()).isFalse()
        assertThat(p.isPassivated()).isFalse()
    }

    @Test
    fun processInWaitUntilIsTerminatedAfterSimulationEnd() = runTest {
        val p = object : Process() {
            override suspend fun actions() {
                waitUntil { false }
            }
        }
        runSimulation(endTime = 10.0) {
            Process.activate(p)
        }
        assertThat(p.isTerminated()).isTrue()
        assertThat(p.terminated()).isTrue()
        assertThat(p.isActive()).isFalse()
        assertThat(p.isPassivated()).isFalse()
    }

    @Test
    fun passivatedProcessIsTerminatedAfterSimulationEnd() = runTest {
        val p = object : Process() {
            override suspend fun actions() {
                passivate()
            }
        }
        runSimulation(endTime = 10.0) {
            Process.activate(p)
        }
        assertThat(p.isTerminated()).isTrue()
        assertThat(p.terminated()).isTrue()
        assertThat(p.isActive()).isFalse()
        assertThat(p.isPassivated()).isFalse()
    }

    @Test
    fun reactivateIsNoOpOnProcessCancelledBySimulationEnd() = runTest {
        val p = object : Process() {
            override suspend fun actions() {
                hold(100.0)
            }
        }
        runSimulation(endTime = 10.0) {
            Process.activate(p)
        }
        assertThat(p.terminated()).isTrue()
        Process.reactivate(p)
        assertThat(p.isTerminated()).isTrue()
        assertThat(p.isActive()).isFalse()
    }

    /**
     * `Process.wait(queue)` parks the *current* process, so there must be one. The
     * `beforeEvent` hook runs at the top of the scheduler loop, before the next event's
     * process becomes current, which is exactly that state.
     */
    @Test
    fun waitWithoutCurrentProcessThrows() = runTest {
        val queue = Head()
        var thrownException: Throwable? = null

        val sim = simulation {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                }
            })
        }
        sim.run(10.0) {
            if (thrownException == null) {
                try {
                    Process.wait(queue)
                } catch (e: DiscoException) {
                    thrownException = e
                }
            }
        }

        assertThat(thrownException).isNotNull()
        assertThat(thrownException!!.message).isNotNull()
        assertThat(thrownException.message!!).contains("No current process")
    }

    @Test
    fun duplicateActivateBeforeRunSchedulesOnlyOnce() = runTest {
        var executions = 0
        val p = object : Process() {
            override suspend fun actions() {
                executions++
            }
        }
        runSimulation(endTime = 10.0) {
            Process.activate(p)
            Process.activate(p)  // duplicate — must be a no-op
        }
        assertThat(executions).isEqualTo(1)
    }

    @Test
    fun duplicateActivateAtSameInstantResumesPassivatedProcessOnce() = runTest {
        var resumes = 0
        val worker = object : Process() {
            override suspend fun actions() {
                passivate()
                resumes++
                hold(1.0)
            }
        }
        val resumer = object : Process() {
            override suspend fun actions() {
                hold(2.0)
                // Two resume paths firing at the same instant
                Process.activate(worker)
                Process.activate(worker)
            }
        }
        runSimulation(endTime = 10.0) {
            Process.activate(worker)
            Process.activate(resumer)
        }
        assertThat(resumes).isEqualTo(1)
        assertThat(worker.isTerminated()).isTrue()
    }

    @Test
    fun activateOnProcessMidHoldIsNoOp() = runTest {
        val times = mutableListOf<Double>()
        val worker = object : Process() {
            override suspend fun actions() {
                hold(10.0)
                times.add(time())
            }
        }
        val meddler = object : Process() {
            override suspend fun actions() {
                hold(2.0)
                Process.activate(worker, delay = 1.0)  // worker already scheduled — no-op
            }
        }
        runSimulation(endTime = 100.0) {
            Process.activate(worker)
            Process.activate(meddler)
        }
        assertThat(times).isEqualTo(listOf(10.0))
    }

    @Test
    fun activateOnRunningProcessIsNoOp() = runTest {
        var executions = 0
        val p = object : Process() {
            override suspend fun actions() {
                executions++
                Process.activate(this)  // self-activation while running — no-op
                hold(1.0)
            }
        }
        runSimulation(endTime = 10.0) {
            Process.activate(p)
        }
        assertThat(executions).isEqualTo(1)
        assertThat(p.isTerminated()).isTrue()
    }
}
