// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages the simulation clock, event scheduling, and execution control.
 */
class Simulation internal constructor() {
    internal val context = SimulationContext()
    private var _hasRun = false

    // --- Continuous integration parameters ---

    /** Minimum integration step size. Must be > 0 and <= [dtMax]. */
    var dtMin: Double
        get() = context.dtMin
        set(value) {
            require(value > 0.0) { "dtMin must be positive, got $value" }
            require(value <= context.dtMax) { "dtMin ($value) must be <= dtMax (${context.dtMax})" }
            context.dtMin = value
        }

    /** Maximum integration step size. Must be >= [dtMin]. */
    var dtMax: Double
        get() = context.dtMax
        set(value) {
            require(value > 0.0) { "dtMax must be positive, got $value" }
            require(value >= context.dtMin) { "dtMax ($value) must be >= dtMin (${context.dtMin})" }
            context.dtMax = value
        }

    /** Maximum absolute integration error per step (used by RKF45). Must be non-negative. */
    var maxAbsError: Double
        get() = context.maxAbsError
        set(value) {
            require(value >= 0.0) { "maxAbsError must be non-negative, got $value" }
            context.maxAbsError = value
        }

    /** Maximum relative integration error per step (used by RKF45). Must be non-negative. */
    var maxRelError: Double
        get() = context.maxRelError
        set(value) {
            require(value >= 0.0) { "maxRelError must be non-negative, got $value" }
            context.maxRelError = value
        }

    /** The numerical integrator used for continuous variable integration. Defaults to [RKF45Integrator]. */
    internal var integrator: Integrator
        get() = context.monitor.integrator
        set(value) { context.monitor.integrator = value }

    /** The random generator used by this simulation. Processes should use this for reproducible draws. */
    val random: Random get() = context.random

    /** True when this simulation was created with an explicit seed for reproducibility. */
    var deterministic: Boolean = false
        internal set

    /**
     * Executes the simulation until [endTime] or [stop] is called.
     *
     * Process coroutines are launched into a dedicated [CoroutineScope] that is
     * independent of the caller's scope. This prevents [kotlinx.coroutines.test.runTest]
     * from waiting on suspended process coroutines after the simulation ends.
     *
     * [Dispatchers.Unconfined] ensures coroutines start and resume synchronously on
     * the calling thread, giving deterministic single-threaded execution.
     *
     * When active [Continuous] processes are present, the [ContinuousMonitor] integrates
     * all active [Variable]s up to the time of the next discrete event before processing it.
     *
     * @param beforeEvent Optional suspend hook invoked once per event-loop iteration,
     *   before the next event is processed. Can be used to implement pause, throttle,
     *   or step-mode control. Called with the simulation clock at the time of the
     *   *previously* processed event (i.e. before the clock advances to the next event).
     * @return `true` if the event queue drained naturally (no more events to process),
     *   `false` if the simulation was stopped early via [stop] or the next event was
     *   scheduled beyond [endTime].
     */
    suspend fun run(endTime: Double, beforeEvent: (suspend () -> Unit)? = null): Boolean {
        check(!_hasRun) { "Simulation has already run; create a new Simulation instance" }
        _hasRun = true
        require(endTime >= 0.0) { "End time must be non-negative, got $endTime" }

        val previousContext = Process.activeContext
        Process.activeContext = context
        context.isRunning = true
        context.stopRequested = false

        // Dedicated scope for process coroutines — NOT a child of the caller's scope.
        // SupervisorJob so one process failure doesn't cancel others.
        val simJob = SupervisorJob()
        val simScope = CoroutineScope(Dispatchers.Unconfined + simJob)

        var queueDrained = false
        try {
            // Move pending activations into the event queue
            val activations = context.pendingActivations.toList()
            context.pendingActivations.clear()
            for (pending in activations) {
                context.eventQueue.schedule(pending.process, pending.delay)
            }

            // Main scheduler loop.
            // Dispatchers.Unconfined guarantees: launch{} and resumeWith() both run the
            // process synchronously on this thread until the process suspends
            // (hold/passivate) or terminates, then control returns here for the next event.
            while (!context.stopRequested) {
                currentCoroutineContext().ensureActive()
                beforeEvent?.invoke()

                // Peek at the next event without removing it yet.
                val next = context.eventQueue.peek()

                // Determine integration boundary: next event or endTime if queue is empty.
                // When the queue is empty but continuous processes are still active, we must
                // integrate all the way to endTime rather than exiting the loop immediately.
                val integrateTo = if (next != null) minOf(next.time, endTime) else endTime

                // Integrate continuous processes up to the next event boundary (or endTime).
                if (context.firstCont != null) {
                    context.monitor.integrateUntil(integrateTo)
                }

                // If no more discrete events: check if integrateUntil added events via
                // checkWaitNotices (e.g. a waitUntil condition became true). If so,
                // loop back to process them; otherwise we are truly done.
                if (next == null) {
                    if (!context.eventQueue.isEmpty()) continue
                    queueDrained = true
                    break
                }

                // Now pop and process the event.
                val event = context.eventQueue.removeFirst() ?: break
                if (event.time > endTime) break

                context.currentTime = event.time
                val process = event.process
                context.currentProcess = process

                val cont = process.continuation
                if (cont != null) {
                    // Resume existing coroutine (returning from hold/passivate)
                    process.continuation = null
                    process._state = ProcessState.RUNNING
                    cont.resumeWith(Result.success(Unit))
                } else {
                    // First activation — launch new coroutine for process.actions().
                    // Guard against re-launching if the process was terminated and then
                    // erroneously rescheduled (e.g. by a reactivate() call that predates
                    // this guard being in place).
                    if (!process._terminated) {
                        process._state = ProcessState.RUNNING
                        emit(SimulationEvent.ProcessActivated(context.currentTime, process))
                        simScope.launch {
                            try {
                                process.actions()
                            } catch (_: ProcessTerminatedException) {
                                // Process called terminate() — expected, not an error
                            } finally {
                                if (!process._terminated) {
                                    process._state = ProcessState.TERMINATED
                                    process._terminated = true
                                    emit(SimulationEvent.ProcessTerminated(context.currentTime, process))
                                }
                            }
                        }
                    }
                    // With Unconfined, the launched coroutine runs synchronously until
                    // the process calls hold()/passivate()/terminate(), then control
                    // returns here for the next event.
                }
                context.checkWaitNotices()
                context.checkLevelCrossings()
            }
        } finally {
            context.isRunning = false
            context.currentProcess = null
            Process.activeContext = previousContext
            // Cancel any remaining suspended coroutines (passivated processes that
            // were never reactivated, or processes whose hold() time is past endTime).
            simScope.cancel()
            withContext(NonCancellable) { simJob.join() }
        }
        return queueDrained
    }

    /**
     * Runs the simulation under an external [SimulationController].
     *
     * The controller's [SimulationController.beforeEvent] hook is invoked once per
     * event-loop iteration before the next event is processed.
     *
     * @return `true` if the event queue drained naturally, `false` if stopped early.
     * @see run
     */
    suspend fun run(endTime: Double, controller: SimulationController): Boolean =
        run(endTime) { controller.beforeEvent(this) }

    /**
     * Runs the simulation under an external [SimulationController].
     *
     * This is a convenience overload equivalent to [run] with a controller argument.
     *
     * @return `true` if the event queue drained naturally, `false` if stopped early.
     * @see run
     */
    suspend fun runControlled(controller: SimulationController, endTime: Double): Boolean = run(endTime, controller)

    /** Returns the current simulation clock time. */
    fun time(): Double = context.currentTime

    /**
     * Register a listener that receives every [SimulationEvent] in simulation-time order.
     *
     * Listeners are additive — each call appends to the list. All registered listeners
     * receive every event in registration order. Zero-overhead when no listeners registered.
     */
    fun onEvent(listener: (SimulationEvent) -> Unit) {
        context.eventListeners += listener
    }

    /** Emit a [SimulationEvent] to all registered listeners, in registration order. */
    internal fun emit(event: SimulationEvent) {
        if (context.eventListeners.isEmpty()) return
        context.eventListeners.forEach { it(event) }
    }

    /**
     * Returns the scheduled time of the next pending event, or [Double.MAX_VALUE] if no
     * events are queued. May be called from the [run] [beforeEvent] hook to implement
     * step-event control.
     */
    fun nextEventTime(): Double = context.eventQueue.peek()?.time ?: Double.MAX_VALUE

    /** Number of events currently waiting in the event queue. */
    fun scheduledEventCount(): Int = context.eventQueue.size()

    /** Number of processes that are running, scheduled, or pending activation. Passivated processes are not counted. */
    fun activeProcessCount(): Int = context.pendingActivations.size + context.eventQueue.size() +
        context.crossingNotices.size +
        (if (context.currentProcess != null) 1 else 0)

    /** Requests the simulation to stop after the current event. */
    fun stop() {
        context.stopRequested = true
    }

    /** `true` while the simulation is executing [run]. */
    val isRunning: Boolean get() = context.isRunning

    /** `true` if the simulation has been requested to stop. */
    fun isStopRequested(): Boolean = context.stopRequested

    companion object {
        /**
         * Creates a new [Simulation] and runs [setup] with it as the receiver.
         * Processes activated during [setup] are queued for execution when [run] is called.
         *
         * @param seed Optional seed for the simulation's random generator. When provided,
         *   the run is deterministic and [Simulation.deterministic] is set to true.
         */
        fun create(seed: Long? = null, setup: Simulation.() -> Unit): Simulation {
            val simulation = Simulation()
            if (seed != null) {
                simulation.context.random = Random(seed)
                simulation.deterministic = true
            }
            val previousContext = Process.activeContext
            Process.activeContext = simulation.context
            try {
                simulation.setup()
            } finally {
                Process.activeContext = previousContext
            }
            return simulation
        }
    }
}
