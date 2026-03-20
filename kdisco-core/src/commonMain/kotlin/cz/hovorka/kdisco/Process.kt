package cz.hovorka.kdisco

import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Thrown by [Process.terminate] to unwind the coroutine call stack.
 * Uses a custom exception (not CancellationException) to avoid
 * interfering with kotlinx.coroutines structured concurrency.
 */
internal class ProcessTerminatedException : Exception()

/**
 * Base class for discrete-event simulation entities.
 *
 * Each process is a Kotlin coroutine scheduled by the simulation engine.
 * Override [actions] to define process behavior using [hold], [passivate],
 * and [terminate].
 */
abstract class Process : Link() {

    internal lateinit var context: SimulationContext
    internal var continuation: kotlin.coroutines.Continuation<Unit>? = null
    internal var _terminated: Boolean = false

    /**
     * Defines the behavior of this process. Called by the scheduler.
     */
    abstract suspend fun actions()

    /**
     * Suspends this process for the specified simulation time duration.
     */
    suspend fun hold(duration: Double) {
        require(duration >= 0.0) { "Duration must be non-negative, got $duration" }
        suspendCancellableCoroutine<Unit> { cont ->
            continuation = cont
            context.eventQueue.schedule(this, context.currentTime + duration)
            cont.invokeOnCancellation {
                continuation = null
                context.eventQueue.remove(this@Process)
            }
        }
    }

    /**
     * Deactivates this process until explicitly reactivated via [Process.reactivate].
     */
    suspend fun passivate() {
        suspendCancellableCoroutine<Unit> { cont ->
            continuation = cont
            // Not scheduled in event queue — waits for reactivate()
            cont.invokeOnCancellation {
                continuation = null
            }
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
        _terminated = true
        context.eventQueue.remove(this)
        throw ProcessTerminatedException()
    }

    /** Returns the current simulation time. */
    fun time(): Double = context.currentTime

    /** Returns true if this process has completed or been terminated. */
    fun terminated(): Boolean = _terminated

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
            if (ctx.isRunning) {
                ctx.eventQueue.schedule(process, ctx.currentTime + delay)
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
            process.context.waitNotices.removeAll { it.process === process }  // clear stale wait-until notices
            process.context.eventQueue.remove(process)   // prevent duplicate if already scheduled
            process.context.eventQueue.schedule(process, process.context.currentTime)
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
    }
}

internal class PendingActivation(
    val process: Process,
    val delay: Double
)
