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
 * Tests for [Process.waitUntilCrossing] — the *level-triggered*, root-found threshold wait.
 *
 * Unlike the edge-triggered [Process.waitCrossing] (which fires only on a strict sign change
 * observed at accepted integration-step endpoints), `waitUntilCrossing` resumes as soon as the
 * predicate `guard() <= 0` holds — re-tested like a wait notice after every discrete event and
 * every accepted step — while still locating a within-step transition by root-finding and
 * rolling the state back to the crossing time.
 */
class WaitUntilCrossingTest {

    // --- Semantics point 1: already satisfied at registration → return immediately ---

    /** A guard that is already negative at the call returns immediately, without waiting. */
    @Test
    fun alreadySatisfiedGuardReturnsImmediately() = runTest {
        var resumeTime = Double.NaN
        runSimulation(endTime = 10.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    waitUntilCrossing { -1.0 }  // satisfied from the start
                    resumeTime = time()
                }
            })
        }
        assertThat(resumeTime).isEqualTo(0.0)
    }

    /** A guard that is exactly zero at the call is satisfied (guard() <= 0) and returns at once. */
    @Test
    fun guardExactlyZeroAtRegistrationReturnsImmediately() = runTest {
        var resumeTime = Double.NaN
        runSimulation(endTime = 10.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(2.0)
                    waitUntilCrossing { 0.0 }  // exactly on the boundary
                    resumeTime = time()
                }
            })
        }
        assertThat(resumeTime).isEqualTo(2.0)
    }

    /**
     * A variable that has *already* moved past the threshold before the process starts waiting
     * releases the process immediately — the case where waitCrossing would wait forever for a
     * future sign change that never comes.
     */
    @Test
    fun variableAlreadyPastThresholdReturnsImmediately() = runTest {
        val x = Variable(0.0)
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 1.0 }
        }
        var resumeTime = Double.NaN
        runSimulation(endTime = 20.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    hold(5.0)  // x has advanced well past 3.0 by now
                    waitUntilCrossing { 3.0 - x.state }
                    resumeTime = time()
                    motion.stop(); x.stop()
                }
            })
        }
        assertThat(resumeTime).isEqualTo(5.0)
    }

    // --- Semantics point 2: satisfied between steps / by a discrete event → resume ---

    /**
     * Purely discrete model: the guard flips satisfied by a discrete event. No Continuous
     * process is active at all — waitCrossing would never fire (it needs integration), but
     * the level-triggered wait is re-tested after every discrete event.
     */
    @Test
    fun discreteEventSatisfiesGuardWithoutAnyContinuousProcess() = runTest {
        var level = 1.0
        var resumeTime = Double.NaN
        runSimulation(endTime = 10.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    waitUntilCrossing { level }
                    resumeTime = time()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(3.0)
                    level = -1.0  // guard becomes satisfied by a discrete state change
                }
            })
        }
        assertThat(resumeTime).isEqualTo(3.0)
    }

    /**
     * A discrete event jumps a variable's state past the threshold (no integration step ever
     * shows a sign change at its endpoints). The level-triggered wait still resumes.
     */
    @Test
    fun discreteJumpOfVariableStatePastThresholdResumes() = runTest {
        val x = Variable(0.0)
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 0.0 }  // variable at rest
        }
        var resumeTime = Double.NaN
        runSimulation(endTime = 20.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    waitUntilCrossing { 5.0 - x.state }
                    resumeTime = time()
                    motion.stop(); x.stop()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(4.0)
                    x.state = 10.0  // discrete jump past the threshold
                }
            })
        }
        assertThat(resumeTime).isEqualTo(4.0)
    }

    /**
     * The interlockSim hazard, reduced: a variable asymptotes towards a stop point and comes
     * to rest *short of* the wait threshold. The process is parked (correct — the predicate
     * does not hold). A later discrete event lowers the threshold below the resting state:
     * the level-triggered wait releases the process even though the state variable never
     * changes again, whereas the edge-triggered waitCrossing would park it forever.
     */
    @Test
    fun stalledVariableIsReleasedWhenDiscreteEventSatisfiesGuard() = runTest {
        // x' = k * (target - x): x asymptotes to target = 4.999 and effectively stops.
        val x = Variable(0.0)
        val target = 4.999
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 2.0 * (target - x.state) }
        }
        var threshold = 5.0  // above the asymptote: never reached by integration
        var resumeTime = Double.NaN
        var stateAtResume = Double.NaN

        runSimulation(endTime = 30.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    waitUntilCrossing { threshold - x.state }
                    resumeTime = time()
                    stateAtResume = x.state
                    motion.stop(); x.stop()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(10.0)  // by now x ≈ 4.999 and stalled short of 5.0
                    threshold = 4.9  // condition clears: guard = 4.9 - x < 0
                }
            })
        }

        assertThat(resumeTime).isEqualTo(10.0)
        assertThat(stateAtResume).isGreaterThan(4.9)
    }

    /**
     * A/B counterpart of the stalled-variable scenario: the edge-triggered waitCrossing
     * stays parked forever in the identical model, documenting exactly why the
     * level-triggered primitive is needed.
     */
    @Test
    fun stalledVariableStaysParkedForeverWithEdgeTriggeredWaitCrossing() = runTest {
        val x = Variable(0.0)
        val target = 4.999
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 2.0 * (target - x.state) }
        }
        var threshold = 5.0
        var fired = false

        runSimulation(endTime = 30.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    waitCrossing { threshold - x.state }
                    fired = true  // must NOT run: no sign change is ever observed at step endpoints
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(10.0)
                    threshold = 4.9  // guard is now negative, but it flipped between steps —
                                     // the endpoint comparison never sees a sign change
                }
            })
        }

        assertThat(fired).isFalse()
    }

    /**
     * The concrete interlockSim failure shape: braking law a = -v²/(2s) with s the remaining
     * distance, so v → 0 as s → 0 and the train comes to rest a hair short of the boundary
     * but *past* the gate threshold (boundary - dtMin). The level-triggered wait must release
     * the gate; whether the transition was seen at a step endpoint or not is irrelevant.
     */
    @Test
    fun brakingTrainAsymptotingToBoundaryReleasesGateThreshold() = runTest {
        val boundary = 100.0
        val position = Variable(0.0)
        val v = Variable(10.0)
        val train = object : Continuous() {
            override fun derivatives() {
                position.rate = v.state
                val s = boundary - position.state
                v.rate = if (s > 1e-12 && v.state > 0.0) -v.state * v.state / (2.0 * s) else 0.0
            }
        }
        var gateTime = Double.NaN
        var gatePosition = Double.NaN

        runSimulation(endTime = 200.0) {
            dtMax = 1.0
            dtMin = 1e-5
            Process.activate(object : Process() {
                override suspend fun actions() {
                    position.start(); v.start(); train.start()
                    val gateThreshold = boundary - 1e-5  // boundary - dtMin
                    waitUntilCrossing { gateThreshold - position.state }
                    gateTime = time()
                    gatePosition = position.state
                    train.stop(); v.stop(); position.stop()
                }
            })
        }

        // The gate must open (no permanent park), with the position at/past the threshold
        // within tolerance.
        assertThat(gateTime.isNaN()).isFalse()
        assertThat(gatePosition).isGreaterThan(boundary - 1e-4)
    }

    // --- Semantics point 3: satisfied within a step → root-find and roll back ---

    /**
     * Linear motion x(t) = v*t reaches the boundary at t = 10 exactly. With a natural dtMax
     * the crossing occurs inside a step and must be located by root-finding, matching
     * waitCrossing's precision.
     */
    @Test
    fun locatesLinearWithinStepCrossingPrecisely() = runTest {
        val x = Variable(0.0)
        var crossTime = Double.NaN
        var crossState = Double.NaN
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 10.0 }
        }

        runSimulation(endTime = 100.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    waitUntilCrossing { 100.0 - x.state }
                    crossTime = time()
                    crossState = x.state
                    motion.stop(); x.stop()
                }
            })
        }

        assertThat(abs(crossTime - 10.0)).isLessThan(1e-6)
        assertThat(abs(crossState - 100.0)).isLessThan(1e-4)
    }

    /**
     * Projectile y(t) = v0*t - 0.5*g*t² lands (y crosses 0 downward) at t = 2*v0/g.
     * Nonlinear guard, still located accurately with a large step.
     */
    @Test
    fun locatesNonlinearWithinStepCrossingPrecisely() = runTest {
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
                    hold(0.1)  // leave the launch point (y=0) first, so the wait is genuine
                    waitUntilCrossing { y.state }
                    landTime = time()
                    ballistics.stop(); y.stop(); vy.stop()
                }
            })
        }

        val expected = 2.0 * 20.0 / g
        assertThat(abs(landTime - expected)).isLessThan(1e-4)
    }

    /** A time-dependent guard (g = T - t) is root-found within a step as well. */
    @Test
    fun timeDependentGuardIsRootFound() = runTest {
        val x = Variable(0.0)
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 1.0 }
        }
        var resumeTime = Double.NaN

        runSimulation(endTime = 10.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    waitUntilCrossing { 3.5 - time() }
                    resumeTime = time()
                    motion.stop(); x.stop()
                }
            })
        }

        assertThat(abs(resumeTime - 3.5)).isLessThan(1e-6)
    }

    /** A process can wait for successive thresholds (consecutive block boundaries). */
    @Test
    fun handlesSuccessiveThresholds() = runTest {
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
                    waitUntilCrossing { 3.0 - x.state }
                    times.add(time())
                    waitUntilCrossing { 7.0 - x.state }
                    times.add(time())
                    motion.stop(); x.stop()
                }
            })
        }

        assertThat(times.size).isEqualTo(2)
        assertThat(abs(times[0] - 3.0)).isLessThan(1e-6)
        assertThat(abs(times[1] - 7.0)).isLessThan(1e-6)
    }

    // --- No upward triggering / no spurious firing ---

    /** A guard that stays positive throughout leaves the process suspended. */
    @Test
    fun guardNeverSatisfiedLeavesProcessSuspended() = runTest {
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
                    waitUntilCrossing { 1000.0 - x.state }
                    fired = true
                }
            })
        }

        assertThat(fired).isFalse()
        assertThat(x.state).isGreaterThanOrEqualTo(4.9)
    }

    /**
     * Unlike waitCrossing, an *upward* transition (guard leaving the satisfied region) never
     * fires a level-triggered wait: only guard() <= 0 resumes. Register with the guard
     * positive and strictly increasing — no downward transition ever occurs, so the process
     * stays waiting.
     */
    @Test
    fun upwardSignChangeDoesNotFireLevelTriggeredWait() = runTest {
        // Guard g = x - 3: starts negative → the level-triggered wait would return
        // immediately. To isolate the upward case, register with g > 0 and let it
        // *increase* — no downward transition, no firing.
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
                    waitUntilCrossing { 1.0 + x.state }  // positive and increasing
                    fired = true
                }
            })
        }

        assertThat(fired).isFalse()
    }

    /**
     * Contrast test: waitCrossing (edge-triggered) fires on an upward crossing of the guard,
     * while waitUntilCrossing with the same rising guard returns immediately at registration
     * (guard already <= 0). Documents the intentional semantic difference.
     */
    @Test
    fun risingGuardReturnsImmediatelyForLevelButWaitsForEdge() = runTest {
        val x = Variable(0.0)
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 1.0 }
        }
        var levelResume = Double.NaN
        var edgeResume = Double.NaN

        runSimulation(endTime = 20.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    waitUntilCrossing { x.state - 3.0 }  // guard <= 0 now → immediate
                    levelResume = time()
                    waitCrossing { x.state - 3.0 }       // edge: waits for the sign change at t=3
                    edgeResume = time()
                    motion.stop(); x.stop()
                }
            })
        }

        assertThat(levelResume).isEqualTo(0.0)
        assertThat(abs(edgeResume - 3.0)).isLessThan(1e-6)
    }

    // --- Interaction with other engine features ---

    /** Edge- and level-triggered notices pending simultaneously fire independently and correctly. */
    @Test
    fun mixedEdgeAndLevelNoticesFireIndependently() = runTest {
        val x = Variable(0.0)
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 1.0 }
        }
        var levelTime = Double.NaN
        var edgeTime = Double.NaN

        runSimulation(endTime = 20.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    hold(20.0)
                    motion.stop(); x.stop()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    waitUntilCrossing { 3.0 - x.state }
                    levelTime = time()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    waitCrossing { 7.0 - x.state }
                    edgeTime = time()
                }
            })
        }

        assertThat(abs(levelTime - 3.0)).isLessThan(1e-6)
        assertThat(abs(edgeTime - 7.0)).isLessThan(1e-6)
    }

    /** Two level-triggered waiters with different thresholds each resume at their own crossing. */
    @Test
    fun multipleLevelWaitersResumeAtTheirOwnThresholds() = runTest {
        val x = Variable(0.0)
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 1.0 }
        }
        var timeA = Double.NaN
        var timeB = Double.NaN

        runSimulation(endTime = 20.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    hold(20.0)
                    motion.stop(); x.stop()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    waitUntilCrossing { 2.5 - x.state }
                    timeA = time()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    waitUntilCrossing { 6.5 - x.state }
                    timeB = time()
                }
            })
        }

        assertThat(abs(timeA - 2.5)).isLessThan(1e-6)
        assertThat(abs(timeB - 6.5)).isLessThan(1e-6)
    }

    /** terminate() clears the terminated process's level crossing notice. */
    @Test
    fun terminateWhileWaitingRemovesLevelCrossingNotice() = runTest {
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
                    waitUntilCrossing { 5.0 - x.state }
                    resumedAfterTerminate = true  // must NOT run
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

    /** reactivate() clears the level crossing notice and resumes the process immediately. */
    @Test
    fun reactivateClearsLevelCrossingNoticeAndResumes() = runTest {
        val x = Variable(0.0)
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 1.0 }
        }
        var resumeTime = Double.NaN
        var resumeCount = 0

        lateinit var waiter: Process
        runSimulation(endTime = 20.0) {
            dtMax = 1.0
            waiter = object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    waitUntilCrossing { 1000.0 - x.state }  // never satisfied on its own
                    resumeTime = time()
                    resumeCount++
                }
            }
            Process.activate(waiter)
            Process.activate(object : Process() {
                override suspend fun actions() {
                    hold(2.0)
                    Process.reactivate(waiter)
                }
            })
        }

        assertThat(resumeTime).isEqualTo(2.0)
        assertThat(resumeCount).isEqualTo(1)
    }

    /** A process suspended in waitUntilCrossing() is counted by activeProcessCount(). */
    @Test
    fun activeProcessCountIncludesLevelCrossingWaiters() = runTest {
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
                    waitUntilCrossing { 5.0 - x.state }
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

        // The waiting process (in crossingNotices) + the checking process itself (currentProcess).
        assertThat(countWhileWaiting).isEqualTo(2)
    }

    /** Negative tolerance is rejected. */
    @Test
    fun negativeToleranceThrows() = runTest {
        var thrown: Throwable? = null
        runSimulation(endTime = 5.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    try {
                        waitUntilCrossing(tolerance = -1e-9) { 1.0 }
                    } catch (e: IllegalArgumentException) {
                        thrown = e
                    }
                }
            })
        }
        assertThat(thrown).isNotNull()
    }

    /**
     * A within-step crossing must roll the variable state back to the located crossing time:
     * on resume, `x.state` is consistent with `time()` (x = rate * t), not with the step end.
     */
    @Test
    fun resumeStateIsConsistentWithResumeTime() = runTest {
        val x = Variable(0.0)
        val motion = object : Continuous() {
            override fun derivatives() { x.rate = 2.0 }
        }
        var resumeTime = Double.NaN
        var stateAtResume = Double.NaN

        runSimulation(endTime = 20.0) {
            dtMax = 1.0
            Process.activate(object : Process() {
                override suspend fun actions() {
                    x.start(); motion.start()
                    waitUntilCrossing { 8.0 - x.state }  // crossing at t = 4.0
                    resumeTime = time()
                    stateAtResume = x.state
                    motion.stop(); x.stop()
                }
            })
        }

        assertThat(abs(resumeTime - 4.0)).isLessThan(1e-6)
        // State rolled back to the crossing time: x(4.0) = 8.0.
        assertThat(abs(stateAtResume - 2.0 * resumeTime)).isLessThan(1e-4)
    }

    /**
     * Root-finding precision parity: the level-triggered wait must land on the same crossing
     * time as the edge-triggered waitCrossing for a genuine within-step crossing.
     */
    @Test
    fun levelAndEdgeAgreeOnWithinStepCrossingTime() = runTest {
        suspend fun run(level: Boolean): Double {
            val x = Variable(0.0)
            var crossTime = Double.NaN
            val motion = object : Continuous() {
                override fun derivatives() { x.rate = 10.0 }
            }
            runSimulation(endTime = 100.0) {
                dtMax = 1.0
                Process.activate(object : Process() {
                    override suspend fun actions() {
                        x.start(); motion.start()
                        if (level) waitUntilCrossing { 100.0 - x.state }
                        else waitCrossing { 100.0 - x.state }
                        crossTime = time()
                        motion.stop(); x.stop()
                    }
                })
            }
            return crossTime
        }

        val levelTime = run(level = true)
        val edgeTime = run(level = false)
        assertThat(abs(levelTime - edgeTime)).isLessThan(1e-9)
        assertThat(abs(levelTime - 10.0)).isLessThan(1e-6)
    }

    /**
     * Guard satisfied at the same simulation time as registration, but by a *later* event at
     * that time: the waiter registers first (guard > 0), then another same-time event makes
     * the guard non-positive — the post-event check must release the waiter at that same time.
     */
    @Test
    fun sameTimeLaterEventSatisfyingGuardResumesWaiter() = runTest {
        var level = 1.0
        var resumeTime = Double.NaN
        runSimulation(endTime = 10.0) {
            Process.activate(object : Process() {
                override suspend fun actions() {
                    waitUntilCrossing { level }
                    resumeTime = time()
                }
            })
            Process.activate(object : Process() {
                override suspend fun actions() {
                    level = -1.0  // same simulation time t=0, processed after the waiter
                }
            })
        }
        assertThat(resumeTime).isEqualTo(0.0)
    }
}
