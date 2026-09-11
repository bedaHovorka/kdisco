// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

/**
 * Tests for state-event (zero-crossing) detection with root-finding.
 *
 * The engine locates a guard sign-change *within* an accepted integration step, so hybrid
 * models can leave `dtMax` at its natural value instead of forcing a tiny step to keep the
 * boundary overshoot negligible.
 */
class StateEventTest {

    /**
     * Linear motion x(t) = v*t reaches the boundary at t = boundary/v exactly.
     * With a large dtMax the plain `waitUntil` would only resolve the crossing to within one
     * whole step; `waitCrossing` must locate it to high precision.
     */
    @Test
    fun locatesLinearCrossingPrecisely() = runTest {
        val x = Variable(0.0)
        val v = 10.0
        val boundary = 100.0
        var crossTime = Double.NaN
        var crossState = Double.NaN

        val motion = object : Continuous() {
            override fun derivatives() { x.rate = v }
        }

        runSimulation(endTime = 100.0) {
            dtMax = 1.0  // natural step size — no tiny-dtMax workaround
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    waitCrossing { boundary - x.state }
                    crossTime = time()
                    crossState = x.state
                    motion.stop(); x.stop()
                }
            })
        }

        // Exact crossing is t = 10.0, x = 100.0.
        assertThat(abs(crossTime - 10.0)).isLessThan(1e-6)
        assertThat(abs(crossState - boundary)).isLessThan(1e-4)
    }

    /**
     * Projectile y(t) = v0*t - 0.5*g*t^2 lands (y crosses 0) at t = 2*v0/g.
     * Nonlinear guard, still located accurately with a large step.
     */
    @Test
    fun locatesNonlinearCrossingPrecisely() = runTest {
        val y = Variable(0.0)
        val vy = Variable(20.0)
        val g = 9.81
        var landTime = Double.NaN

        val ballistics = object : Continuous() {
            override fun derivatives() {
                y.rate = vy.state
                vy.rate = -g
            }
        }

        runSimulation(endTime = 10.0) {
            dtMax = 1.0
            maxAbsError = 1e-8
            maxRelError = 1e-8
            Process.activate(object : Process() {
                override suspend fun actions() {
                    y.start(); vy.start(); ballistics.start()
                    hold(0.1)  // let it leave the launch point (y=0) first
                    waitCrossing { y.state }
                    landTime = time()
                    ballistics.stop(); y.stop(); vy.stop()
                }
            })
        }

        val expected = 2.0 * 20.0 / g  // ≈ 4.077...
        assertThat(abs(landTime - expected)).isLessThan(1e-4)
    }

    /**
     * Crossing detection with a large dtMax must use dramatically fewer derivative
     * evaluations than the tiny-dtMax workaround, while landing on the same crossing time.
     */
    @Test
    fun crossingUsesFarFewerDerivativeEvaluationsThanTinyDtMax() = runTest {
        val boundary = 100.0
        val v = 10.0

        // Reference: the "tiny dtMax" workaround, resolving the crossing by hold-stepping.
        suspend fun runTinyDtMax(): Pair<Double, Int> {
            val x = Variable(0.0)
            var evals = 0
            var crossTime = Double.NaN
            val motion = object : Continuous() {
                override fun derivatives() { evals++; x.rate = v }
            }
            runSimulation(endTime = 100.0) {
                dtMax = 1e-3
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        x.start(); motion.start()
                        waitUntil { x.state >= boundary }
                        crossTime = time()
                        motion.stop(); x.stop()
                    }
                })
            }
            return crossTime to evals
        }

        // Crossing detection: large natural step + root-finding.
        suspend fun runCrossing(): Pair<Double, Int> {
            val x = Variable(0.0)
            var evals = 0
            var crossTime = Double.NaN
            val motion = object : Continuous() {
                override fun derivatives() { evals++; x.rate = v }
            }
            runSimulation(endTime = 100.0) {
                dtMax = 1.0
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        x.start(); motion.start()
                        waitCrossing { boundary - x.state }
                        crossTime = time()
                        motion.stop(); x.stop()
                    }
                })
            }
            return crossTime to evals
        }

        val (tinyTime, tinyEvals) = runTinyDtMax()
        val (crossingTime, crossingEvals) = runCrossing()

        // Both land on the true crossing time t = 10.0.
        assertThat(abs(crossingTime - 10.0)).isLessThan(1e-6)
        // The crossing approach costs far fewer derivative evaluations.
        assertThat(crossingEvals).isLessThan(tinyEvals / 10)
        // Sanity: they agree on the crossing time.
        assertThat(abs(crossingTime - tinyTime)).isLessThan(1e-2)
    }

    /**
     * A process can wait for successive crossings (e.g. reaching consecutive block boundaries).
     */
    @Test
    fun handlesSuccessiveCrossings() = runTest {
        // x(t) = t; cross levels 3.0 then 7.0.
        val x = Variable(0.0)
        val times = mutableListOf<Double>()
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 1.0 }
        }

        runSimulation(endTime = 20.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    waitCrossing { 3.0 - x.state }
                    times.add(time())
                    waitCrossing { 7.0 - x.state }
                    times.add(time())
                    motion.stop(); x.stop()
                }
            })
        }

        assertThat(times.size).isEqualTo(2)
        assertThat(abs(times[0] - 3.0)).isLessThan(1e-6)
        assertThat(abs(times[1] - 7.0)).isLessThan(1e-6)
    }

    /**
     * A guard that never changes sign leaves the process suspended (no spurious firing).
     */
    @Test
    fun noCrossingLeavesProcessSuspended() = runTest {
        val x = Variable(0.0)
        var fired = false
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 1.0 }
        }

        runSimulation(endTime = 5.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    // Boundary at 1000 is never reached within endTime=5.
                    waitCrossing { 1000.0 - x.state }
                    fired = true
                }
            })
        }

        assertThat(fired).isFalse()
        // Integration still advanced normally.
        assertThat(x.state).isGreaterThanOrEqualTo(4.9)
    }

    /**
     * terminate() must clear the terminated process's crossing notice, mirroring what
     * reactivate() already does. Otherwise a later genuine crossing resurrects the
     * terminated process's coroutine past its waitCrossing() suspension point.
     */
    @Test
    fun terminateWhileWaitingOnCrossingRemovesCrossingNotice() = runTest {
        val x = Variable(0.0)
        var resumedAfterTerminate = false
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 1.0 }
        }

        lateinit var waiter: Process
        runSimulation(endTime = 20.0) {
            dtMax = 1.0
            waiter = object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    waitCrossing { 5.0 - x.state }
                    resumedAfterTerminate = true  // must NOT run: waiter was terminated at t=1
                }
            }
            Process.activate(waiter)
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                    waiter.terminate()
                }
            })
        }

        assertThat(waiter.isTerminated()).isTrue()
        assertThat(resumedAfterTerminate).isFalse()
    }

    /**
     * A process suspended in waitCrossing() must be counted by activeProcessCount() — it is
     * neither in the event queue nor passivated, but it is genuinely still active work.
     */
    @Test
    fun activeProcessCountIncludesProcessesWaitingOnCrossing() = runTest {
        val x = Variable(0.0)
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 1.0 }
        }
        var countWhileWaiting = -1
        lateinit var sim: Simulation

        sim = Simulation.create {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    waitCrossing { 5.0 - x.state }
                    motion.stop(); x.stop()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                    countWhileWaiting = sim.activeProcessCount()
                }
            })
        }
        sim.run(10.0)

        // The waiting process (in crossingNotices) + this checking process itself (currentProcess).
        assertThat(countWhileWaiting).isEqualTo(2)
    }

    /**
     * If a Continuous.derivatives() override reactivates another process with its own pending
     * crossing notice, context.crossingNotices shrinks mid-step. Guard "before" values must
     * stay attached to their owning notice (not a list position) so an unrelated notice's
     * crossing is still located correctly and at the right time.
     */
    @Test
    fun midStepMutationOfCrossingNoticesDoesNotCorruptOtherNotice() = runTest {
        val x = Variable(0.0)
        var derivCalls = 0
        lateinit var a: Process
        lateinit var b: Process
        var bResumedTime = Double.NaN

        val motion = object : Continuous() {
            override fun derivatives() {
                x.rate = 1.0
                derivCalls++
                // Mid-step: reactivate `a`, removing its crossing notice from the registry
                // while `b`'s notice is still pending in the same step.
                if (derivCalls == 2) {
                    Process.reactivate(a)
                }
            }
        }

        runSimulation(endTime = 20.0) {
            dtMax = 1.0
            a = object : Process() {
                override suspend fun actions() {
                    waitCrossing { 1000.0 - x.state }  // never crosses; reactivated instead
                }
            }
            b = object : Process() {
                override suspend fun actions() {
                    waitCrossing { 5.0 - x.state }
                    bResumedTime = time()
                }
            }
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    Process.activate(a)
                    Process.activate(b)
                    hold(20.0)
                }
            })
        }

        assertThat(abs(bResumedTime - 5.0)).isLessThan(1e-4)
    }

    /**
     * Regression guard for the crossing channel of Issue #73: an independent `activate` on a
     * process parked in `waitCrossing` must neither end the wait early nor strand its notice.
     * `waitCrossing` is edge-triggered and has no condition to re-test, so an extra wake-up used to
     * return from it at the wrong time and leave a live notice behind to resume the process again
     * later, from a completely different suspension point.
     */
    @Test
    fun activateWhileWaitingOnCrossingDoesNotEndTheWaitEarly() = runTest {
        val x = Variable(0.0)
        val motion = object : Continuous() {
            override fun derivatives() {
                x.rate = 1.0
            }
        }
        var resumeCount = 0
        var resumeTime = Double.NaN

        val waiter = object : Process() {
            override suspend fun actions() {
                x.start()
                motion.start()
                waitCrossing { 5.0 - x.state }
                resumeCount++
                resumeTime = time()
                motion.stop()
                x.stop()
            }
        }
        val sim = Simulation.create {
            dtMax = 1.0
            Process.activate(waiter)
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(1.0)
                    Process.activate(waiter) // parked in waitCrossing — must be absorbed
                }
            })
        }
        sim.run(20.0)

        assertThat(resumeCount).isEqualTo(1)
        assertThat(abs(resumeTime - 5.0)).isLessThan(1e-6)
        // Nothing stranded: the absorbed turn left no notice and no queued event behind.
        assertThat(sim.activeProcessCount()).isEqualTo(0)
    }

    /**
     * A crossing notice cancelled *during* root-finding must not still be scheduled.
     *
     * `locateCrossings` checks the live registry before root-finding, but everything after that
     * check — every bisection probe, and the final rollback — re-runs [Continuous.derivatives],
     * and a derivatives override is allowed to call [Process.reactivate]. Here it does, on the very
     * process whose crossing is being located: the notice is dropped and reactivate queues its own
     * turn. Scheduling the crossing regardless hands the waiter a second, stale wake-up, which
     * lands at its next suspension point — the [Process.passivate] below.
     *
     * The guard arms the latch when it first goes non-positive, which is the first-pass evaluation
     * at the step end, so the reactivate lands inside the bisection that follows.
     */
    @Test
    fun crossingCancelledByDerivativesDuringRootFindingIsNotStillScheduled() = runTest {
        val x = Variable(0.0)
        val resumes = mutableListOf<Double>()
        var stateOnResume = Double.NaN
        var crossed = false
        var fired = false
        lateinit var waiter: Process

        val motion = object : Continuous() {
            override fun derivatives() {
                x.rate = 10.0
                if (crossed && !fired) {
                    fired = true
                    Process.reactivate(waiter)
                }
            }
        }
        waiter = object : Process() {
            override suspend fun actions() {
                x.start()
                motion.start()
                waitCrossing {
                    val g = 100.0 - x.state
                    if (g <= 0.0) crossed = true
                    g
                }
                resumes.add(time())
                stateOnResume = x.state
                passivate()
                resumes.add(time()) // only a stale second wake-up can get here
            }
        }

        runSimulation(endTime = 60.0) {
            dtMax = 1.0
            Process.activate(waiter)
        }

        assertThat(fired).isTrue() // the reactivate really did run inside root-finding
        // Resumed once, by the reactivate — not a second time by the cancelled crossing.
        assertThat(resumes).hasSize(1)
        // The clock and the variable agree on resume. The reactivate queued its turn at the step
        // start (t=9, x=90), so leaving x at the abandoned crossing near t=10 would resume the
        // process at t=9 holding a t=10 state.
        assertThat(abs(resumes[0] - 9.0)).isLessThan(1e-9)
        assertThat(abs(stateOnResume - 90.0)).isLessThan(1e-6)
    }

    /**
     * The same cancellation, swept across *every* point it can happen.
     *
     * Root-finding runs user [Continuous.derivatives] code many times — once per bisection probe,
     * and once more in the final rollback to the located crossing — and a `reactivate` from any of
     * them cancels the notice. The final rollback is the awkward one: a liveness test taken before
     * it leaves that replay's own derivatives calls outside the window, so the notice can be
     * cancelled after the test and still get scheduled.
     *
     * Rather than reach inside the engine to name that call, this arms the cancellation at the
     * *n*-th derivatives call **after the guard first reads non-positive** — which is the
     * first-pass evaluation at the step end, so every n lands inside crossing location — and
     * sweeps n. The two invariants asserted must hold wherever it lands: the waiter resumes
     * exactly once, and the state it observes matches the time it observes. x(t) = 10t exactly
     * for this model, so the second is a direct equality.
     *
     * The small n fall in bisection, the larger ones in the final rollback, and the largest never
     * fire at all — the crossing then happens normally at t=10. All three are legitimate, and all
     * three must satisfy the invariants.
     *
     * The sweep upper bound is deliberately past the number of derivatives calls this model makes
     * during location (245 on JVM at the time of writing): the count depends on how many bisection
     * steps the guard needs, which is floating-point dependent and so not identical across targets,
     * and an n past the end simply never fires. Too low a bound is the failure mode that matters —
     * it stops before the final rollback and the sweep silently covers only bisection.
     */
    @Test
    fun cancellationAtAnyPointDuringCrossingLocationLeavesOneConsistentResume() = runTest {
        // 280 is comfortably past the ~245 derivatives calls one crossing location makes here.
        for (armAt in 1..280) {
            val x = Variable(0.0)
            val resumes = mutableListOf<Double>()
            val states = mutableListOf<Double>()
            var crossed = false
            var callsAfterCrossed = 0
            var fired = false
            lateinit var waiter: Process

            val motion = object : Continuous() {
                override fun derivatives() {
                    x.rate = 10.0
                    if (!crossed) return
                    callsAfterCrossed++
                    if (callsAfterCrossed >= armAt && !fired) {
                        fired = true
                        Process.reactivate(waiter)
                    }
                }
            }
            waiter = object : Process() {
                override suspend fun actions() {
                    x.start()
                    motion.start()
                    waitCrossing {
                        val g = 100.0 - x.state
                        if (g <= 0.0) crossed = true
                        g
                    }
                    resumes.add(time())
                    states.add(x.state)
                    // Stop integration the moment the wait ends: derivatives called after crossing
                    // location has finished are outside what this sweep is about, and a reactivate
                    // from one of them would legitimately resume the passivated process again.
                    motion.stop()
                    x.stop()
                    passivate()
                    resumes.add(time()) // only a stale second wake-up can get here
                    states.add(x.state)
                }
            }

            runSimulation(endTime = 60.0) {
                dtMax = 1.0
                Process.activate(waiter)
            }

            assertThat(resumes).hasSize(1)
            // The clock and the variable agree: x(t) = 10t for this model.
            assertThat(abs(states[0] - 10.0 * resumes[0])).isLessThan(1e-6)
        }
    }

    /**
     * Cancelling a *bystander* notice during location invalidates the crossing just as much.
     *
     * Two guards cross inside the same step — `first` at t=10.02, `second` at t=10.05 — so `first`
     * wins and its crossing is the one located. A `derivatives` call during that location
     * reactivates `second`, which drops `second`'s notice and queues its turn at a speculative
     * probe time, earlier than the crossing being located. `first`'s notice is untouched, so a
     * liveness test that only looks at the winner sees nothing wrong: it schedules `first` at
     * t=10.02 and leaves the variables there, and the scheduler then takes `second`'s earlier turn,
     * running the clock backwards over state from the future.
     *
     * Swept across arming points like
     * [cancellationAtAnyPointDuringCrossingLocationLeavesOneConsistentResume], asserting the same
     * invariant for both processes: x(t) = 10t must hold at whatever time each one resumes.
     */
    @Test
    fun cancellingABystanderNoticeDuringLocationLeavesEveryResumeConsistent() = runTest {
        for (armAt in 1..60) {
            val x = Variable(0.0)
            val observed = mutableListOf<Pair<Double, Double>>() // time to state
            var crossed = false
            var callsAfterCrossed = 0
            var fired = false
            lateinit var second: Process

            val motion = object : Continuous() {
                override fun derivatives() {
                    x.rate = 10.0
                    if (!crossed) return
                    callsAfterCrossed++
                    if (callsAfterCrossed >= armAt && !fired) {
                        fired = true
                        Process.reactivate(second)
                    }
                }
            }
            val first = object : Process() {
                override suspend fun actions() {
                    waitCrossing {
                        val g = 100.2 - x.state
                        if (g <= 0.0) crossed = true
                        g
                    }
                    observed.add(time() to x.state)
                }
            }
            second = object : Process() {
                override suspend fun actions() {
                    waitCrossing { 100.5 - x.state }
                    observed.add(time() to x.state)
                }
            }

            runSimulation(endTime = 60.0) {
                dtMax = 1.0
                Process.activate(
                    object : Process() {
                        override suspend fun actions() {
                            x.start()
                            motion.start()
                        }
                    },
                )
                Process.activate(first)
                Process.activate(second)
            }

            assertThat(observed).hasSize(2) // both waits ended exactly once
            for ((t, state) in observed) {
                assertThat(abs(state - 10.0 * t)).isLessThan(1e-6)
            }
        }
    }

    /**
     * A guard that cancels its own wait during the endpoint evaluation must not still be located.
     *
     * The first pass evaluates every crossed guard at the step end, and a guard is user code: it
     * can call [Process.reactivate]. Doing so drops its own notice *after* the pass has already
     * checked the registry for it and queues the turn at the step end, yet the notice is still in
     * the crossing list the second pass works from — so the crossing was root-found and scheduled
     * anyway, on top of the reactivate's turn. Measured: resumes at t=10.02 (the stale crossing)
     * and t=11.0, where only t=11.0 is owed.
     *
     * This is why the invalidation baseline is taken at the very top of the pass, before the first
     * guard runs, rather than between the two passes.
     */
    @Test
    fun aGuardCancellingItsOwnWaitAtEndpointEvaluationIsNotStillScheduled() = runTest {
        val x = Variable(0.0)
        val resumes = mutableListOf<Double>()
        val states = mutableListOf<Double>()
        var fired = false
        lateinit var waiter: Process

        val motion = object : Continuous() {
            override fun derivatives() {
                x.rate = 10.0
            }
        }
        waiter = object : Process() {
            override suspend fun actions() {
                waitCrossing {
                    val g = 100.2 - x.state
                    if (g <= 0.0 && !fired) {
                        fired = true
                        Process.reactivate(waiter)
                    }
                    g
                }
                resumes.add(time())
                states.add(x.state)
                passivate()
                resumes.add(time()) // only a stale second wake-up can get here
                states.add(x.state)
            }
        }

        runSimulation(endTime = 60.0) {
            dtMax = 1.0
            Process.activate(
                object : Process() {
                    override suspend fun actions() {
                        x.start()
                        motion.start()
                    }
                },
            )
            Process.activate(waiter)
        }

        assertThat(fired).isTrue()
        assertThat(resumes).hasSize(1)
        // Resumed by the reactivate at the step end, with the state to match.
        assertThat(abs(states[0] - 10.0 * resumes[0])).isLessThan(1e-6)
    }

    /**
     * The process woken during location need not own a crossing notice at all.
     *
     * A `derivatives` probe can reactivate a passivated helper, queueing its turn at a speculative
     * probe time without touching the crossing registry. The located crossing is then scheduled
     * and the variables left at it, and the scheduler takes the helper's earlier turn holding state
     * from the future — measured at t=10.0 with x=100.2, the value at t=10.02.
     *
     * This is the finding that moved the invalidation test off the notice registry and onto the
     * event queue's mutation count. It also needs the scheduler to recompute its event boundary
     * after an aborted pass: the `ticker` below keeps a future event queued so the run enters
     * integration with one already peeked, which is the case where the boundary goes stale.
     */
    @Test
    fun reactivatingAnUnrelatedProcessDuringLocationLeavesEveryResumeConsistent() = runTest {
        for (armAt in 1..60) {
            val x = Variable(0.0)
            val observed = mutableListOf<Pair<Double, Double>>() // time to state
            var crossed = false
            var callsAfterCrossed = 0
            var fired = false
            lateinit var helper: Process

            val motion = object : Continuous() {
                override fun derivatives() {
                    x.rate = 10.0
                    if (!crossed) return
                    callsAfterCrossed++
                    if (callsAfterCrossed >= armAt && !fired) {
                        fired = true
                        Process.reactivate(helper)
                    }
                }
            }
            helper = object : Process() {
                override suspend fun actions() {
                    passivate()
                    observed.add(time() to x.state)
                }
            }
            val waiter = object : Process() {
                override suspend fun actions() {
                    waitCrossing {
                        val g = 100.2 - x.state
                        if (g <= 0.0) crossed = true
                        g
                    }
                    observed.add(time() to x.state)
                }
            }
            // Keeps a future event queued, so run() enters integration having already peeked one.
            val ticker = object : Process() {
                override suspend fun actions() {
                    hold(50.0)
                }
            }

            runSimulation(endTime = 60.0) {
                dtMax = 1.0
                Process.activate(
                    object : Process() {
                        override suspend fun actions() {
                            x.start()
                            motion.start()
                        }
                    },
                )
                Process.activate(helper)
                Process.activate(waiter)
                Process.activate(ticker)
            }

            // Without these the loop below is vacuous: an armAt that never triggers leaves a
            // single consistent observation (or none) and the sweep would pass regardless.
            assertThat(fired).isTrue()
            assertThat(observed).hasSize(2) // the helper resumed, and so did the waiter
            for ((t, state) in observed) {
                assertThat(abs(state - 10.0 * t)).isLessThan(1e-6)
            }
        }
    }

    /**
     * The scheduling side effect need not happen during crossing location at all.
     *
     * `derivatives()` runs on the initial rate computation and on every RK stage of the accepted
     * step, long before any guard is evaluated. A `reactivate` from one of those queues a turn at a
     * speculative stage time, and integration used to carry on to its original target — the
     * `ticker`'s event at t=50 — so the helper was eventually resumed holding x=500.0, the state at
     * t=50, at a clock of t=0.0. This is why the mutation baseline covers the whole step rather
     * than just the location pass.
     */
    @Test
    fun reactivatingDuringAnIntegrationStageLeavesTheResumeConsistent() = runTest {
        for (armAt in 1..40) {
            val x = Variable(0.0)
            val observed = mutableListOf<Pair<Double, Double>>()
            var calls = 0
            var fired = false
            lateinit var helper: Process

            val motion = object : Continuous() {
                override fun derivatives() {
                    x.rate = 10.0
                    calls++
                    if (calls >= armAt && !fired) {
                        fired = true
                        Process.reactivate(helper)
                    }
                }
            }
            helper = object : Process() {
                override suspend fun actions() {
                    passivate()
                    observed.add(time() to x.state)
                }
            }
            // Gives integration a far-away target to run to, which is what made the stale state
            // observable: without it the step ends at endTime and there is nothing early to pop.
            val ticker = object : Process() {
                override suspend fun actions() {
                    hold(50.0)
                }
            }

            runSimulation(endTime = 60.0) {
                dtMax = 1.0
                Process.activate(
                    object : Process() {
                        override suspend fun actions() {
                            x.start()
                            motion.start()
                        }
                    },
                )
                Process.activate(helper)
                Process.activate(ticker)
            }

            assertThat(fired).isTrue()
            assertThat(observed).hasSize(1)
            val (t, state) = observed[0]
            assertThat(abs(state - 10.0 * t)).isLessThan(1e-6)
        }
    }

    /**
     * A guard that does not cross is still user code, and a step with no crossing still has to be
     * invalidated when it schedules something.
     *
     * The guard here never reaches zero within the run — `collectCrossed` always returns empty — so
     * the location pass returned "nothing crossed" and integration continued to its original
     * target, leaving the helper's turn to be popped at t=0.0 holding x=500.0. The mutation test
     * now runs even when nothing crossed.
     */
    @Test
    fun aNonCrossingGuardThatSchedulesStillInvalidatesTheStep() = runTest {
        for (armAt in 1..40) {
            val x = Variable(0.0)
            val observed = mutableListOf<Pair<Double, Double>>()
            var guardCalls = 0
            var fired = false
            lateinit var helper: Process

            val motion = object : Continuous() {
                override fun derivatives() {
                    x.rate = 10.0
                }
            }
            helper = object : Process() {
                override suspend fun actions() {
                    passivate()
                    observed.add(time() to x.state)
                }
            }
            val waiter = object : Process() {
                override suspend fun actions() {
                    waitCrossing {
                        guardCalls++
                        if (guardCalls >= armAt && !fired) {
                            fired = true
                            Process.reactivate(helper)
                        }
                        10_000.0 - x.state // never reached in this run
                    }
                }
            }
            val ticker = object : Process() {
                override suspend fun actions() {
                    hold(50.0)
                }
            }

            runSimulation(endTime = 60.0) {
                dtMax = 1.0
                Process.activate(
                    object : Process() {
                        override suspend fun actions() {
                            x.start()
                            motion.start()
                        }
                    },
                )
                Process.activate(helper)
                Process.activate(waiter)
                Process.activate(ticker)
            }

            assertThat(fired).isTrue()
            assertThat(observed).hasSize(1)
            val (t, state) = observed[0]
            assertThat(abs(state - 10.0 * t)).isLessThan(1e-6)
        }
    }

    /**
     * The post-step notice checks run user code as well, and an *unsatisfied* condition can still
     * schedule.
     *
     * The [Process.waitUntil] condition here never becomes true, so neither notice registry changes
     * size and `postStepNoticesFired()` reports nothing fired — while the `reactivate` it performed
     * has put a turn in the queue at the accepted step end. Integration used to carry on to the
     * `ticker`'s event at t=50, so the helper resumed at t=1.0 holding x=500.0.
     *
     * This is the one place the step stops without unwinding: these checks run at the step end, so
     * anything they queue is at the current time or later and the variables already match it.
     */
    @Test
    fun anUnsatisfiedWaitConditionThatSchedulesStopsIntegration() = runTest {
        for (armAt in 1..20) {
            val x = Variable(0.0)
            val observed = mutableListOf<Pair<Double, Double>>()
            var checks = 0
            var fired = false
            lateinit var helper: Process

            val motion = object : Continuous() {
                override fun derivatives() {
                    x.rate = 10.0
                }
            }
            helper = object : Process() {
                override suspend fun actions() {
                    passivate()
                    observed.add(time() to x.state)
                }
            }
            val waiter = object : Process() {
                override suspend fun actions() {
                    waitUntil {
                        checks++
                        if (checks >= armAt && !fired) {
                            fired = true
                            Process.reactivate(helper)
                        }
                        false // never satisfied, so waitNotices never shrinks
                    }
                }
            }
            val ticker = object : Process() {
                override suspend fun actions() {
                    hold(50.0)
                }
            }

            runSimulation(endTime = 60.0) {
                dtMax = 1.0
                Process.activate(
                    object : Process() {
                        override suspend fun actions() {
                            x.start()
                            motion.start()
                        }
                    },
                )
                Process.activate(helper)
                Process.activate(waiter)
                Process.activate(ticker)
            }

            assertThat(fired).isTrue()
            assertThat(observed).hasSize(1)
            val (t, state) = observed[0]
            assertThat(abs(state - 10.0 * t)).isLessThan(1e-6)
        }
    }

    /**
     * Stopping integration is not enough when the post-step mutation does not queue at the current
     * time — the scheduler has to recompute its boundary too.
     *
     * The unsatisfied condition here activates a helper with a *delay*, so its event lands after
     * the step end but before the `ticker`'s event at t=50, which is the boundary `run` peeked
     * before integrating. Merely stopping integration left that stale boundary in place: the loop
     * popped the helper's event and jumped the clock forward to it without integrating the
     * variables from the step end. Measured at t=6.0 with x=10.0, the state at t=1.
     *
     * No unwind is involved here — the accepted step-end state is valid — only the boundary is
     * stale, which is why [StepOutcome.RESTART] covers both and the notice path does not rewind.
     */
    @Test
    fun aDelayedActivateFromAnUnsatisfiedConditionRecomputesTheBoundary() = runTest {
        for (armAt in 1..20) {
            val x = Variable(0.0)
            val observed = mutableListOf<Pair<Double, Double>>()
            var checks = 0
            var fired = false

            val motion = object : Continuous() {
                override fun derivatives() {
                    x.rate = 10.0
                }
            }
            val helper = object : Process() {
                override suspend fun actions() {
                    observed.add(time() to x.state)
                }
            }
            val waiter = object : Process() {
                override suspend fun actions() {
                    waitUntil {
                        checks++
                        if (checks >= armAt && !fired) {
                            fired = true
                            Process.activate(helper, delay = 5.0)
                        }
                        false // never satisfied: the engine schedules nothing of its own here
                    }
                }
            }
            val ticker = object : Process() {
                override suspend fun actions() {
                    hold(50.0)
                }
            }

            runSimulation(endTime = 60.0) {
                dtMax = 1.0
                Process.activate(
                    object : Process() {
                        override suspend fun actions() {
                            x.start()
                            motion.start()
                        }
                    },
                )
                Process.activate(waiter)
                Process.activate(ticker)
            }

            assertThat(fired).isTrue()
            assertThat(observed).hasSize(1)
            val (t, state) = observed[0]
            assertThat(abs(state - 10.0 * t)).isLessThan(1e-6)
        }
    }
}
