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
     * Processes still suspended when this returns are cancelled and end [Process.isTerminated],
     * whichever primitive they were parked on. That cancellation is not a simulation event: no
     * [SimulationEvent.ProcessTerminated] is emitted for it.
     *
     * @param beforeEvent Optional suspend hook invoked once per event-loop iteration,
     *   before the next event is processed. Can be used to implement pause, throttle,
     *   or step-mode control. Called with the simulation clock at the time of the
     *   *previously* processed event (i.e. before the clock advances to the next event).
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

        try {
            // Move pending activations into the event queue.
            // pending.delay is a relative delay from currentTime (which may be non-zero for
            // resumed simulations), so we schedule at currentTime + pending.delay.
            val activations = context.pendingActivations.toList()
            context.pendingActivations.clear()
            for (pending in activations) {
                context.eventQueue.schedule(pending.process, context.currentTime + pending.delay)
            }

            // Main scheduler loop.
            // Dispatchers.Unconfined guarantees: launch{} and resumeWith() both run the
            // process synchronously on this thread until the process suspends
            // (hold/passivate) or terminates, then control returns here for the next event.
            while (!context.stopRequested) {
                currentCoroutineContext().ensureActive()
                beforeEvent?.invoke()

                // Peek at the next event without removing it yet. The integration boundary is
                // that event's time, or endTime when the queue is empty and only continuous
                // processes are still active — integration must run all the way there rather than
                // exiting the loop immediately.
                var next = context.eventQueue.peek()

                // Integrate continuous processes up to the next event boundary (or endTime).
                if (context.firstCont != null) {
                    // A step can be invalidated by user code queueing or dropping a turn, in which
                    // case the boundary was computed from a peek that predates it — and the clock
                    // may have moved backwards with the unwind. Re-peek and integrate again rather
                    // than popping against a stale boundary.
                    //
                    // Retried here rather than by restarting the outer loop, because no event has
                    // been processed: re-entering beforeEvent would spend a SimulationController's
                    // single-step authorization on a retry that advances the simulation by nothing.
                    while (context.monitor.integrateUntil(integrationBoundary(next, endTime))) {
                        currentCoroutineContext().ensureActive()
                        next = context.eventQueue.peek()
                    }
                }

                // If no more discrete events: check if integrateUntil added events via
                // checkWaitNotices (e.g. a waitUntil condition became true). If so,
                // loop back to process them; otherwise we are truly done.
                if (next == null) {
                    if (!context.eventQueue.isEmpty()) continue
                    break
                }

                // Stop before processing any event whose time exceeds endTime.
                // Crucially, we do NOT remove the event from the queue here — leaving it
                // in place means pendingEvents() correctly returns all future work after a
                // partial run, enabling capture-and-resume checkpointing.
                if (next.time > endTime) break

                // Pop and process the event.
                val event = context.eventQueue.removeFirst() ?: break

                context.currentTime = event.time
                val process = event.process
                context.currentProcess = process

                val cont = process.continuation
                if (cont != null) {
                    // A spurious resume mid-hold is dropped, not delivered — see Process.holdDue.
                    val spurious = process.queuedEvents > 0 && context.currentTime < process.holdDue
                    if (!spurious) {
                        // Resume existing coroutine (returning from hold/passivate)
                        process.continuation = null
                        process.holdDue = Double.NEGATIVE_INFINITY
                        process._state = ProcessState.RUNNING
                        cont.resumeWith(Result.success(Unit))
                    }
                } else {
                    // First activation — launch new coroutine for process.actions().
                    // Guard against re-launching if the process was terminated and then
                    // erroneously rescheduled (e.g. by a reactivate() call that predates
                    // this guard being in place).
                    if (!process._terminated) {
                        process._state = ProcessState.RUNNING
                        context.emit { SimulationEvent.ProcessActivated(context.currentTime, process) }
                        simScope.launch {
                            try {
                                process.actions()
                            } catch (_: ProcessTerminatedException) {
                                // Process called terminate() — expected, not an error
                            } finally {
                                if (!process._terminated) {
                                    process._state = ProcessState.TERMINATED
                                    // Same cleanup terminate() performs, for the process that
                                    // simply ran off the end of actions(). A surplus turn can
                                    // outlive it — a notice releases the process while a delayed
                                    // activate's turn is still queued — and a turn left behind
                                    // still gets popped: it advances the clock and fires the
                                    // beforeEvent hook for a process the scheduler will then
                                    // refuse to relaunch.
                                    context.dropWakeUpsOf(process)
                                    context.emit { SimulationEvent.ProcessTerminated(context.currentTime, process) }
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
            // Cancellation drops each parked process's notice, but not any event an independent
            // activate had already queued for it — and the scheduler loop stops at the first event
            // past endTime, so later ones are never popped. Those events are unreachable once the
            // run is over (a Simulation cannot be run twice), and leaving them would make
            // scheduledEventCount() and activeProcessCount() report outstanding work that can
            // never be delivered.
            //
            // Their owners need the same treatment. A process that actually started was cancelled
            // by simScope.cancel() above and is already TERMINATED, but one whose only turn was
            // scheduled past endTime never launched, so nothing cancelled it and it is still
            // SCHEDULED — reporting isActive() for a turn that cannot come, and refusing a later
            // activate as a duplicate. Cancellation terminates silently (no ProcessTerminated is
            // emitted for a process the run never activated), and so does this.
            for (process in context.eventQueue.scheduledProcesses()) {
                if (!process._terminated) process._state = ProcessState.TERMINATED
            }
            context.eventQueue.clear()
        }
        return true
    }

    /**
     * Runs the simulation under an external [SimulationController].
     *
     * The controller's [SimulationController.beforeEvent] hook is invoked once per
     * event-loop iteration before the next event is processed.
     */
    suspend fun run(endTime: Double, controller: SimulationController): Boolean =
        run(endTime) { controller.beforeEvent(this) }

    /**
     * Runs the simulation under an external [SimulationController].
     *
     * This is a convenience overload equivalent to [run] with a controller argument.
     */
    suspend fun runControlled(controller: SimulationController, endTime: Double): Boolean = run(endTime, controller)

    /**
     * How far continuous integration may run before the next discrete event: that event's time, or
     * [endTime] when the queue is empty and only continuous processes are still active.
     */
    private fun integrationBoundary(next: ScheduledEvent?, endTime: Double): Double =
        if (next != null) minOf(next.time, endTime) else endTime

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

    /**
     * Returns the scheduled time of the next pending event, or [Double.MAX_VALUE] if no
     * events are queued. May be called from the [run] [beforeEvent] hook to implement
     * step-event control.
     */
    fun nextEventTime(): Double = context.eventQueue.peek()?.time ?: Double.MAX_VALUE

    /** Number of events currently waiting in the event queue. */
    fun scheduledEventCount(): Int = context.eventQueue.size()

    /**
     * Returns a read-only ordered snapshot of all events currently waiting in the event queue.
     *
     * The list is ordered in the same way that the scheduler would process them: by ascending
     * simulation time, with equal-time normal events in FIFO order and equal-time priority
     * events in LIFO order ahead of normal events. The queue itself is not mutated.
     *
     * This method is safe to call at any point — before, during (e.g. from a [run]
     * [beforeEvent] hook), or after [run] has completed.
     */
    fun pendingEvents(): List<PendingEvent> = context.eventQueue.snapshot()

    /**
     * Number of outstanding wake-ups: pending activations, queued events, and registered wait
     * ([Process.waitUntil]) and crossing ([Process.waitCrossing], [Process.waitUntilCrossing])
     * notices, plus the process currently executing. Passivated processes are not counted.
     *
     * This counts wake-ups, not distinct processes: a process that is both parked on a notice and
     * holding a turn queued by [Process.activate] contributes twice. Over-counting is the safe
     * direction for `while (activeProcessCount() > 0)` drain loops.
     */
    fun activeProcessCount(): Int = context.pendingActivations.size + context.eventQueue.size() +
        context.waitNotices.size + context.crossingNotices.size +
        (if (context.currentProcess != null) 1 else 0)

    /** Requests the simulation to stop after the current event. */
    fun stop() {
        context.stopRequested = true
    }

    /** `true` while the simulation is executing [run]. */
    val isRunning: Boolean get() = context.isRunning

    /** `true` if the simulation has been requested to stop. */
    fun isStopRequested(): Boolean = context.stopRequested

    /**
     * Captures the current state of this simulation's random number generator.
     *
     * The returned [RandomState] can be passed to [Simulation.resume] so that the
     * resumed run produces an identical sequence of random draws from the capture point.
     */
    fun captureRandom(): RandomState = context.random.captureState()

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

        /**
         * Reconstructs a [Simulation] from a previously captured checkpoint and returns it
         * ready to [run] onward from that point.
         *
         * Given the same [events], [clockTime], and [randomState] that were captured from an
         * original run, the resumed simulation produces event-for-event identical output to
         * what the original simulation would have produced past the capture point — provided
         * the caller supplies equivalent [Process] instances (i.e. objects whose [Process.actions]
         * will continue the same work from the capture point onwards).
         *
         * **Process identity**: The [Process] instances inside [events] are the *new* instances
         * for this resumed run, supplied by the caller. Capture via [pendingEvents] returns
         * references to the original run's processes; the caller is responsible for mapping
         * those to freshly constructed equivalents before calling this factory.
         *
         * **How to capture**:
         * ```kotlin
         * val sim = simulation(seed = mySeed) { /* activate processes */ }
         * sim.run(captureTime)
         * val snapshot = Triple(sim.pendingEvents(), sim.time(), sim.captureRandom())
         * ```
         *
         * **How to resume**:
         * ```kotlin
         * val (events, clockTime, rngState) = snapshot
         * val resumedEvents = events.map { it.copy(process = newProcessFor(it.process)) }
         * val resumedSim = Simulation.resume(resumedEvents, clockTime, rngState)
         * resumedSim.run(endTime)
         * ```
         *
         * @param events The pre-populated event queue, in any order (each entry is inserted at
         *   its correct position). Each [PendingEvent] must reference a freshly constructed
         *   [Process] instance; the `priority` and `insertionOrder` carried by the snapshot are
         *   preserved, so equal-time ordering matches the captured run exactly.
         * @param clockTime The simulation clock value at the capture point (from [time]).
         * @param randomState The RNG state at the capture point (from [captureRandom]).
         * @param block Optional configuration block — called with the new [Simulation] as
         *   receiver. Use it to register event listeners or adjust integration parameters.
         *   Any [Process.activate] calls inside [block] are scheduled relative to [clockTime].
         */
        fun resume(
            events: List<PendingEvent>,
            clockTime: Double,
            randomState: RandomState,
            block: (Simulation.() -> Unit)? = null
        ): Simulation {
            require(clockTime >= 0.0) { "clockTime must be non-negative, got $clockTime" }
            val simulation = Simulation()
            simulation.context.currentTime = clockTime
            simulation.context.random.restoreState(randomState)

            // Pre-populate the event queue with absolute times from the captured snapshot.
            // Processes must reference the caller's newly constructed instances.
            //
            // Restored via EventQueue.restore rather than schedule: each event keeps its
            // captured priority flag AND its captured insertionOrder, so equal-time ordering
            // is reproduced exactly. Re-scheduling instead would allocate fresh counters and
            // reverse equal-time priority (LIFO) groups. Insertion is position-independent,
            // so [events] may be supplied in any order.
            val previousContext = Process.activeContext
            Process.activeContext = simulation.context
            try {
                for (event in events) {
                    require(event.time >= clockTime) {
                        "Event time ${event.time} is before clockTime $clockTime"
                    }
                    event.process.context = simulation.context
                    event.process._state = ProcessState.SCHEDULED
                    simulation.context.eventQueue.restore(
                        event.process,
                        event.time,
                        event.priority,
                        event.insertionOrder,
                    )
                }
                block?.invoke(simulation)
            } finally {
                Process.activeContext = previousContext
            }
            return simulation
        }
    }
}
