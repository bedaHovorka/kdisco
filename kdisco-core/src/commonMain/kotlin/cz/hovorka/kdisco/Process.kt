package cz.hovorka.kdisco

import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Thrown by [Process.terminate] to unwind the coroutine call stack.
 * Uses a custom exception (not CancellationException) to avoid
 * interfering with kotlinx.coroutines structured concurrency.
 */
internal class ProcessTerminatedException : Exception()

/**
 * Lifecycle states of a [Process].
 *
 * A process starts in [IDLE]. The simulation scheduler transitions it to
 * [RUNNING] while executing [Process.actions]. Suspension points move it
 * to [SCHEDULED] (will resume automatically) or [PASSIVATED] (must be
 * resumed explicitly). A completed or explicitly stopped process ends in
 * [TERMINATED].
 */
internal enum class ProcessState {
    /** Created but not yet scheduled. */
    IDLE,

    /** Currently executing [Process.actions]. */
    RUNNING,

    /** Suspended and scheduled to resume automatically (e.g. after [hold]). */
    SCHEDULED,

    /** Suspended and waiting for explicit [Process.reactivate]. */
    PASSIVATED,

    /** Completed normally or stopped via [Process.terminate]. */
    TERMINATED
}

/**
 * Base class for discrete-event simulation entities.
 *
 * Each process is a Kotlin coroutine scheduled by the simulation engine.
 * Override [actions] to define process behavior using [hold], [passivate],
 * and [terminate].
 *
 * When multiple processes are activated at the same simulation time, the engine
 * orders them deterministically by activation order (FIFO for normal activations).
 * Paired with a fixed [Random] seed, repeated runs produce identical event logs.
 */
abstract class Process : Link() {

    internal lateinit var context: SimulationContext
    internal var continuation: kotlin.coroutines.Continuation<Unit>? = null
    internal var _terminated: Boolean = false
    internal var _state: ProcessState = ProcessState.IDLE

    /**
     * Defines the behavior of this process. Called by the scheduler.
     *
     * **Only use kDisco suspension points** ([hold], [passivate], [waitUntil], [terminate])
     * inside this method. Calling arbitrary suspending functions (e.g.
     * `kotlinx.coroutines.delay`, `withContext`, `launch`) may resume the coroutine
     * off-scheduler or on a different thread, breaking simulation time, event ordering,
     * and `SimulationContext` thread confinement.
     */
    abstract suspend fun actions()

    /**
     * Suspends this process for the specified simulation time duration.
     */
    suspend fun hold(duration: Double) {
        require(duration >= 0.0) { "Duration must be non-negative, got $duration" }
        suspendCancellableCoroutine<Unit> { cont ->
            _state = ProcessState.SCHEDULED
            continuation = cont
            context.eventQueue.schedule(this, context.currentTime + duration)
            cont.invokeOnCancellation {
                continuation = null
                _state = ProcessState.PASSIVATED
                context.eventQueue.remove(this@Process)
            }
            if (context.eventListeners.isEmpty()) return@suspendCancellableCoroutine
            val event = SimulationEvent.ProcessHeld(context.currentTime, this, duration)
            context.eventListeners.forEach { it(event) }
        }
    }

    /**
     * Deactivates this process until explicitly reactivated via [Process.reactivate].
     */
    suspend fun passivate() {
        suspendCancellableCoroutine<Unit> { cont ->
            _state = ProcessState.PASSIVATED
            continuation = cont
            // Not scheduled in event queue — waits for reactivate()
            cont.invokeOnCancellation {
                continuation = null
            }
            if (context.eventListeners.isEmpty()) return@suspendCancellableCoroutine
            val event = SimulationEvent.ProcessPassivated(context.currentTime, this)
            context.eventListeners.forEach { it(event) }
        }
    }

    /**
     * Suspends this process until [condition] returns true.
     *
     * The condition is checked once immediately — if already true, this returns at once.
     * Otherwise the process is registered in the wait-notice list. It will be
     * re-awakened automatically after each discrete event and after each
     * continuous-integration step.
     *
     * The condition may be checked spuriously; [waitUntil] loops until it is confirmed
     * true before returning.
     *
     * Must only be called from within [actions] (i.e., from a running process).
     */
    suspend fun waitUntil(condition: Condition) {
        while (!condition.test()) {
            suspendCancellableCoroutine<Unit> { cont ->
                _state = ProcessState.SCHEDULED
                continuation = cont
                context.waitNotices.add(WaitNotice(this, condition))
                cont.invokeOnCancellation {
                    continuation = null
                    context.waitNotices.removeAll { it.process === this@Process }
                }
            }
        }
    }

    /** Convenience overload accepting a lambda. */
    suspend fun waitUntil(condition: () -> Boolean) = waitUntil(Condition(condition))

    /**
     * Terminates this process immediately.
     * Throws [ProcessTerminatedException] to unwind the coroutine call stack.
     *
     * Subclasses may override to implement graceful shutdown (e.g., set a flag and
     * reactivate to allow the process to complete its current cycle first).
     */
    open fun terminate() {
        _state = ProcessState.TERMINATED
        _terminated = true
        context.eventQueue.remove(this)
        if (context.eventListeners.isEmpty()) throw ProcessTerminatedException()
        val event = SimulationEvent.ProcessTerminated(context.currentTime, this)
        context.eventListeners.forEach { it(event) }
        throw ProcessTerminatedException()
    }

    /** Returns the current simulation time. */
    fun time(): Double = context.currentTime

    /** Returns the simulation's shared random generator. */
    fun random(): Random = context.random

    /** Emit a custom event from within a process. */
    fun emitCustom(payload: Any?) {
        if (context.eventListeners.isEmpty()) return
        val event = SimulationEvent.Custom(context.currentTime, payload)
        context.eventListeners.forEach { it(event) }
    }

    /** Returns true if this process has completed or been terminated. */
    fun terminated(): Boolean = _terminated

    /**
     * Returns true if this process is currently running or scheduled to run again.
     *
     * A process is active when it is in the [ProcessState.RUNNING] or
     * [ProcessState.SCHEDULED] state. It is not active while [passivate]d or
     * after it has [terminate]d.
     */
    fun isActive(): Boolean = _state == ProcessState.RUNNING || _state == ProcessState.SCHEDULED

    /**
     * Returns true if this process is passivated (suspended until explicitly
     * reactivated via [Process.reactivate]).
     */
    fun isPassivated(): Boolean = _state == ProcessState.PASSIVATED

    /**
     * Returns true if this process has terminated.
     *
     * A process is terminated after [terminate] is called or after its
     * [actions] body completes normally.
     */
    fun isTerminated(): Boolean = _state == ProcessState.TERMINATED

    companion object {
        /**
         * The currently active simulation context. Set by [Simulation.run] for the
         * duration of execution. Uses [SimulationContextHolder] for thread-safe
         * access on JVM (ThreadLocal), allowing multiple simulations to run on
         * separate threads simultaneously.
         */
        @PublishedApi
        internal var activeContext: SimulationContext?
            get() = SimulationContextHolder.context
            set(value) { SimulationContextHolder.context = value }

        /**
         * Schedules a process to begin execution after an optional delay.
         */
        fun activate(process: Process, delay: Double = 0.0) {
            require(delay >= 0.0) { "Delay must be non-negative, got $delay" }
            val ctx = activeContext ?: throw DiscoException("Not inside a simulation")
            process.context = ctx
            process._state = ProcessState.SCHEDULED
            if (ctx.isRunning) {
                ctx.eventQueue.schedule(process, ctx.currentTime + delay)
                if (ctx.eventListeners.isEmpty()) return
                val event = SimulationEvent.ProcessActivated(ctx.currentTime + delay, process)
                ctx.eventListeners.forEach { it(event) }
            } else {
                ctx.pendingActivations.add(PendingActivation(process, delay))
            }
        }

        /**
         * Reactivates a previously passivated process at current time.
         *
         * No-op if [process] is already terminated.
         * If [process] is already in the event queue (e.g. mid-[hold]), it is
         * rescheduled at the current time (no duplicate event is created).
         */
        fun reactivate(process: Process) {
            if (process._terminated) return
            val ctx = process.context
            process._state = ProcessState.SCHEDULED
            ctx.waitNotices.removeAll { it.process === process }  // clear stale wait-until notices
            ctx.eventQueue.remove(process)   // prevent duplicate if already scheduled
            ctx.eventQueue.schedule(process, ctx.currentTime)
            if (ctx.eventListeners.isEmpty()) return
            val event = SimulationEvent.ProcessReactivated(ctx.currentTime, process)
            ctx.eventListeners.forEach { it(event) }
        }

        /**
         * Current process joins [queue] and passivates.
         */
        suspend fun wait(queue: Head) {
            val ctx = activeContext ?: throw DiscoException("Not inside a simulation")
            val current = ctx.currentProcess as? Process
                ?: throw DiscoException("No current process")
            current.into(queue)
            current.passivate()
        }

        /** Returns the current simulation time. */
        fun time(): Double {
            val ctx = activeContext ?: throw DiscoException("Not inside a simulation")
            return ctx.currentTime
        }

        /** Number of events currently scheduled in the active simulation. */
        fun scheduledEventCount(): Int {
            val ctx = activeContext ?: throw DiscoException("Not inside a simulation")
            return ctx.eventQueue.size()
        }
    }
}

internal class PendingActivation(
    val process: Process,
    val delay: Double
)
