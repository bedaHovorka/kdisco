// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * External control surface for a running [Simulation].
 *
 * Pass an instance to [Simulation.run]:
 *
 * ```kotlin
 * val controller = SimulationController()
 * simulation { ... }.run(endTime, controller)
 * controller.pause()
 * controller.step()
 * controller.resume()
 * ```
 */
class SimulationController {

    private val pauseChannel = Channel<Unit>(Channel.CONFLATED)

    // Written by pause()/resume()/step() (control thread), read by beforeEvent()
    // (simulation thread) — same cross-thread pattern as throttleFactor below.
    @Volatile
    private var paused: Boolean = false
    @Volatile
    private var stepsRequested: Int = 0

    // Written by setThrottle() (control thread), read by applyThrottle() (simulation thread).
    @Volatile
    private var throttleFactor: Double = 0.0

    private var wallClockStart: TimeMark = TimeSource.Monotonic.markNow()
    private var simTimeAtStart: Double = 0.0

    /** True when the simulation is currently paused. */
    fun isPaused(): Boolean = paused && stepsRequested == 0

    /** Pause before processing the next event. */
    fun pause() {
        paused = true
        stepsRequested = 0
        // Drain any stale signal left by a step()/resume() that fired while not paused,
        // so it cannot cause an unexpected immediate unpause on the next beforeEvent().
        drainStaleSignal()
    }

    /** Resume normal execution. */
    fun resume() {
        paused = false
        stepsRequested = 0
        pauseChannel.trySend(Unit)
    }

    /**
     * Advance exactly one event and then pause again.
     * Call only while paused or from outside the simulation.
     */
    fun step() {
        paused = true
        stepsRequested++
        pauseChannel.trySend(Unit)
    }

    /**
     * Set target real-time factor. 0.0 disables throttling.
     * A factor of 1.0 means simulation time advances at wall-clock speed.
     */
    fun setThrottle(realTimeFactor: Double) {
        require(realTimeFactor >= 0.0) { "Throttle factor must be non-negative, got $realTimeFactor" }
        throttleFactor = realTimeFactor
    }

    /**
     * Hook invoked by [Simulation.run] before each event.
     */
    suspend fun beforeEvent(simulation: Simulation) {
        if (paused && stepsRequested == 0) {
            pauseChannel.receive()
        }

        if (stepsRequested > 0) {
            stepsRequested--
            if (stepsRequested == 0) {
                paused = true
                // Drain the token placed by step() that was never consumed by receive()
                // (the step branch skips receive()), so it can't bleed into the next pause.
                drainStaleSignal()
            }
        }

        applyThrottle(simulation.time())
    }

    // pauseChannel is CONFLATED (capacity 1), so at most one stale token can ever be
    // buffered — a single tryReceive() is sufficient to drain it.
    private fun drainStaleSignal() {
        pauseChannel.tryReceive()
    }

    private suspend fun applyThrottle(simTime: Double) {
        if (throttleFactor == 0.0) return
        if (simTimeAtStart == 0.0 && simTime > 0.0) {
            // First event after start: record baseline.
            wallClockStart = TimeSource.Monotonic.markNow()
            simTimeAtStart = simTime
        }
        val wallElapsed = wallClockStart.elapsedNow().inWholeMilliseconds / 1000.0
        val simElapsed = simTime - simTimeAtStart
        val targetWall = simElapsed / throttleFactor
        val lagMs = (targetWall - wallElapsed) * 1000
        if (lagMs > 5) delay(lagMs.toLong())
    }
}
