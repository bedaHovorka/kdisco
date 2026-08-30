// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

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
 * [RUNNING] while executing [Process.actions]. Suspension points move it to
 * [SCHEDULED] (an event is queued — it will resume automatically), [WAITING]
 * (parked on a condition or guard notice) or [PASSIVATED] (must be resumed
 * explicitly). A completed or explicitly stopped process ends in [TERMINATED].
 *
 * [SCHEDULED] and [WAITING] are distinct because their wake-ups come from
 * different places: the event queue owns the first, the notice registries own
 * the second. Conflating them loses one of two independent wake-up intents
 * (issue #73).
 */
internal enum class ProcessState {
    /** Created but not yet scheduled. */
    IDLE,

    /** Currently executing [Process.actions]. */
    RUNNING,

    /** Suspended with an event in the queue; resumes automatically (e.g. after [hold]). */
    SCHEDULED,

    /**
     * Suspended on a condition or guard notice — [Process.waitUntil], [Process.waitCrossing]
     * or [Process.waitUntilCrossing]. The process has *no* event in the queue; its wake-up is
     * owned by [SimulationContext.waitNotices] / [SimulationContext.crossingNotices].
     */
    WAITING,

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
     * The common scaffold behind every kDisco suspension point.
     *
     * Records [state] and the resume continuation, then runs [register] — which queues an event or
     * adds a notice. [register] deliberately runs *before* the cancellation handler is installed,
     * matching the order every parking primitive used when each had its own copy of this block.
     * The handler marks the process terminated and calls [onCancel] to drop whatever [register]
     * created, so a process cancelled at end of run leaves no stale queue entry or notice behind.
     *
     * `inline` with `crossinline` lambdas is required, not cosmetic: a plain `suspend` wrapper
     * would add a continuation object and an extra `resumeWith` hop to every [hold], which is the
     * innermost loop of the scheduler (see `TickSchedulingBenchmark`).
     */
    private suspend inline fun park(
        state: ProcessState,
        crossinline register: () -> Unit,
        crossinline onCancel: () -> Unit
    ) {
        suspendCancellableCoroutine<Unit> { cont ->
            _state = state
            continuation = cont
            register()
            cont.invokeOnCancellation {
                continuation = null
                _state = ProcessState.TERMINATED
                _terminated = true
                onCancel()
            }
        }
    }

    /**
     * Suspends this process for the specified simulation time duration.
     */
    suspend fun hold(duration: Double) {
        require(duration >= 0.0) { "Duration must be non-negative, got $duration" }
        park(
            state = ProcessState.SCHEDULED,
            register = {
                context.eventQueue.schedule(this@Process, context.currentTime + duration)
                context.emit { SimulationEvent.ProcessHeld(context.currentTime, this@Process, duration) }
            },
            onCancel = { context.eventQueue.remove(this@Process) }
        )
    }

    /**
     * Deactivates this process until explicitly reactivated via [Process.reactivate].
     */
    suspend fun passivate() {
        park(
            state = ProcessState.PASSIVATED,
            // Not scheduled in the event queue — waits for reactivate()
            register = { context.emit { SimulationEvent.ProcessPassivated(context.currentTime, this@Process) } },
            onCancel = {}
        )
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
            val notice = WaitNotice(this, condition)
            park(
                state = ProcessState.WAITING,
                register = { context.waitNotices.add(notice) },
                onCancel = { context.waitNotices.remove(notice) }
            )
            // The notice is removed by checkWaitNotices at the instant it fires. If this resume
            // came from anywhere else — an independent Process.activate, a reactivate — the notice
            // is still registered and has to be dropped here, or the next loop iteration would
            // leave two notices for this process and the condition would deliver two wake-ups.
            context.waitNotices.remove(notice)
        }
    }

    /** Convenience overload accepting a lambda. */
    suspend fun waitUntil(condition: () -> Boolean) = waitUntil(Condition(condition))

    /**
     * Suspends this process until the continuous guard function [guard] (`g(state, t)`)
     * changes sign — a *state event* (zero-crossing).
     *
     * Unlike [waitUntil], which only re-checks a boolean condition after each accepted
     * integration step (resolving a crossing to within one whole step), this locates the
     * crossing time *within* the step by root-finding (bisection). The process is resumed
     * exactly at the located crossing time, with all active [Variable]s rolled back to their
     * values at that instant.
     *
     * This lets hybrid discrete/continuous models leave [Simulation.dtMax] at its natural
     * value and rely on the adaptive error controller for step size, instead of forcing a
     * tiny `dtMax` so that the overshoot past a boundary is negligible.
     *
     * The guard's sign is sampled when [waitCrossing] is called; the process then waits for
     * the *next* change of that sign. A guard that is already zero (or the wrong sign) at
     * registration is not treated as an immediate crossing — the process waits for a genuine
     * sign change during subsequent integration.
     *
     * Crossing detection requires at least one active [Continuous] process driving integration.
     *
     * **Known limitation**: crossings are detected by comparing the guard's sign at the start
     * and end of each accepted integration step. A guard that departs from and returns to zero
     * *entirely within a single step* (e.g. an instantaneous reversal exactly at a boundary) is
     * not detected, because both endpoints can show the same sign (or a start value of exactly
     * zero, which this API deliberately excludes from the crossing test). This mirrors a known
     * limitation of endpoint-based state-event location in ODE solvers generally; a model
     * relying on detecting such same-step reversals needs a smaller `dtMax` around that
     * boundary, same as the tiny-`dtMax` workaround this API otherwise replaces.
     *
     * **Not suitable for threshold-reach conditions on variables that may come to rest.**
     * Because this API is *edge-triggered*, a variable that asymptotes towards the threshold
     * and stops (rate → 0) can leave the process parked permanently: the sign change is either
     * missed or never observed again, and the guard can never change again because the state
     * never changes again. For "resume when a monotone state variable reaches a threshold",
     * use the *level-triggered* [waitUntilCrossing] (same root-finding precision, but also
     * releases the process whenever the guard is already satisfied), or fall back to
     * [waitUntil] (whole-step resolution).
     *
     * ```kotlin
     * // Resume exactly when the train front reaches the block boundary.
     * waitCrossing { boundary - position.state }
     * ```
     *
     * @param tolerance absolute `|g|` threshold used to terminate root-finding early. A value of
     *   0.0 disables the early-out and relies on the bisection bracket collapsing to
     *   floating-point resolution (still bounded). Defaults to 1e-9.
     * @param guard the event function `g(state, t)`; a sign change locates the event.
     *
     * Must only be called from within [actions] (i.e., from a running process).
     */
    suspend fun waitCrossing(tolerance: Double = 1e-9, guard: () -> Double) {
        require(tolerance >= 0.0) { "tolerance must be non-negative, got $tolerance" }
        awaitCrossing(tolerance, guard, levelTriggered = false)
    }

    /**
     * Suspends this process until the continuous guard function [guard] (`g(state, t)`) is
     * satisfied, i.e. `guard() <= 0` — a *level-triggered*, root-found threshold wait.
     *
     * This is the safe primitive for the common case "*resume when a monotone state variable
     * reaches a threshold*". It combines the precision of [waitCrossing] with the safety of
     * [waitUntil]:
     *
     * 1. **Already satisfied at registration → returns immediately.** If `guard() <= 0` when
     *    this is called, no wait occurs (unlike [waitCrossing], which always waits for a
     *    *future* sign change).
     * 2. **Satisfied between steps or by a discrete event → resumes.** The guard is re-tested
     *    after every discrete event and after every accepted integration step (like a
     *    [waitUntil] condition), so no sign-change history is required. A stalled state
     *    variable that has already passed the threshold still releases the process — the
     *    hazard that makes edge-triggered [waitCrossing] unsuitable for threshold-reach
     *    conditions on variables that may come to rest.
     * 3. **Satisfied within an integration step → root-found and rolled back.** When the guard
     *    transitions from positive to non-positive inside an accepted step, the crossing time
     *    is located by bisection and all active [Variable]s are rolled back to their values at
     *    that instant, exactly as [waitCrossing] does — so `dtMax` can stay at its natural
     *    value.
     *
     * Unlike [waitCrossing], this never fires on an *upward* transition (guard leaving the
     * satisfied region): only `guard() <= 0` resumes the process.
     *
     * No active [Continuous] process is required: with a purely discrete model the guard is
     * still re-tested after every event (points 1 and 2), only the within-step root-finding
     * (point 3) needs integration to be running.
     *
     * On resume, `guard()` is `<= 0`, except when the within-step root-finder terminated
     * early at `|guard()| <= tolerance` — the located crossing point can then sit up to
     * [tolerance] on the positive side of the boundary.
     *
     * **Cancellation by [reactivate]/[terminate].** If [Process.reactivate] is called on a process
     * parked in `waitUntilCrossing`, its level notice is dropped and the process resumes at the
     * current time (the wait is not re-registered) — the same cleanup [waitCrossing] performs.
     * [Process.terminate] removes the notice without resuming. Either way the wait is not
     * silently re-armed, so there is no second permanent-park route via these calls.
     *
     * **Known limitation**: as with [waitCrossing], the guard is compared at the start and end of
     * each accepted integration step (plus the post-step/post-event level re-test). A guard that
     * departs from and returns to the satisfied region *entirely within a single accepted step*
     * is not detected, because both endpoints can show the guard positive. A model relying on
     * detecting such same-step dips needs a smaller `dtMax` around that region.
     *
     * ```kotlin
     * // Resume as soon as the train front has reached the block boundary — precisely when
     * // the crossing occurs inside a step, and immediately if the position is already past
     * // (or stalls just past) the boundary.
     * waitUntilCrossing { boundary - position.state }
     * ```
     *
     * @param tolerance absolute `|g|` threshold used to terminate root-finding early. A value of
     *   0.0 disables the early-out and relies on the bisection bracket collapsing to
     *   floating-point resolution (still bounded). Defaults to 1e-9.
     * @param guard the event function `g(state, t)`; the process resumes when `guard() <= 0`.
     *
     * Must only be called from within [actions] (i.e., from a running process).
     */
    suspend fun waitUntilCrossing(tolerance: Double = 1e-9, guard: () -> Double) {
        require(tolerance >= 0.0) { "tolerance must be non-negative, got $tolerance" }
        if (guard() <= 0.0) return  // level-triggered: already satisfied, no wait
        awaitCrossing(tolerance, guard, levelTriggered = true)
    }

    /**
     * Registers a [CrossingNotice] and parks until the [ContinuousMonitor] or
     * [SimulationContext.checkLevelCrossings] resumes this process.
     *
     * Shared by [waitCrossing] and [waitUntilCrossing], whose bodies differ only in
     * [levelTriggered] and in the early-out [waitUntilCrossing] performs before calling this.
     */
    private suspend fun awaitCrossing(tolerance: Double, guard: () -> Double, levelTriggered: Boolean) {
        val notice = CrossingNotice(this, guard, tolerance, levelTriggered)
        context.crossingNotices.add(notice)
        // Re-park on a spurious resume — the same tolerance [waitUntil] gets from re-testing its
        // condition. A notice is removed at the instant it fires, so "still registered" means this
        // wake-up came from somewhere else (an independent [activate]) and the wait is not over.
        // Without the loop such a wake-up would return from the crossing wait early *and* leave a
        // live notice behind to resume the process again later, from a different suspension point.
        // [reactivate] and [terminate] drop the notice, so they still end the wait as documented.
        while (context.crossingNotices.contains(notice)) {
            park(
                state = ProcessState.WAITING,
                register = {},
                onCancel = { context.crossingNotices.remove(notice) }
            )
        }
    }

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
        context.emit { SimulationEvent.ProcessTerminated(context.currentTime, this) }
        context.eventQueue.remove(this)
        continuation = null
        // All three wake-up sources must be dropped. Leaving a wait notice behind would have its
        // condition re-evaluated after every event and every integration step for the rest of the
        // run, repeatedly scheduling a dead process.
        context.waitNotices.removeAll { it.process === this@Process }
        context.crossingNotices.removeAll { it.process === this@Process }
        throw ProcessTerminatedException()
    }

    /** Returns the current simulation time. */
    fun time(): Double = context.currentTime

    /** Returns the simulation's shared random generator. */
    fun random(): Random = context.random

    /** Emit a custom event from within a process. */
    fun emitCustom(payload: Any?) {
        context.emit { SimulationEvent.Custom(context.currentTime, payload) }
    }

    /** Returns true if this process has completed or been terminated. */
    fun terminated(): Boolean = _terminated

    /**
     * Returns true if this process is currently running or will run again without an explicit
     * [Process.reactivate] — i.e. it is [ProcessState.RUNNING], [ProcessState.SCHEDULED] or
     * [ProcessState.WAITING]. It is not active while [passivate]d or after it has [terminate]d.
     *
     * Use [isWaiting] to tell a process parked on a condition or guard notice apart from one that
     * has an event in the queue.
     */
    fun isActive(): Boolean =
        _state == ProcessState.RUNNING ||
            _state == ProcessState.SCHEDULED ||
            _state == ProcessState.WAITING

    /**
     * Returns true if this process is parked on a condition or guard notice — suspended in
     * [waitUntil], [waitCrossing] or [waitUntilCrossing].
     *
     * A waiting process has no event in the event queue; its wake-up is owned by the notice
     * registry. [Process.activate] therefore grants it an *additional* turn instead of being a
     * no-op, and [Process.reactivate] cancels the wait outright.
     */
    fun isWaiting(): Boolean = _state == ProcessState.WAITING

    /**
     * True when this process already has a turn coming from the event queue — [ProcessState.RUNNING]
     * or [ProcessState.SCHEDULED]. This, not [isActive], is what [activate] must guard on: a
     * [ProcessState.WAITING] process has no queued event, so activating it is not a duplicate.
     */
    internal fun isRunningOrScheduled(): Boolean =
        _state == ProcessState.RUNNING || _state == ProcessState.SCHEDULED

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
         *
         * **No-op** when [process] is already running, already has an event in the queue
         * (mid-[hold], or activated earlier at this or a later time), or has terminated. The
         * existing schedule wins and no duplicate event is created. To move an already-scheduled
         * process to the current time, use [reactivate].
         *
         * A process parked in [waitUntil], [waitCrossing] or [waitUntilCrossing] is deliberately
         * **not** covered by that guard. Such a process has no queued event — its wake-up lives in
         * the notice registry — so `activate` queues an independent event and the process gets an
         * *additional* turn once the wait itself has resumed it. The two wake-ups are separate
         * intents and neither may be spent on the other (issue #73). Use [reactivate] instead when
         * the intent is to *cancel* the pending wait rather than to queue a turn after it.
         */
        fun activate(process: Process, delay: Double = 0.0) {
            require(delay >= 0.0) { "Delay must be non-negative, got $delay" }
            val ctx = activeContext ?: throw DiscoException("Not inside a simulation")
            if (process._terminated) return          // mirrors reactivate(); never resurrect the dead
            if (process.isRunningOrScheduled()) return  // already has a turn — no duplicate event
            process.context = ctx
            process._state = ProcessState.SCHEDULED
            if (ctx.isRunning) {
                ctx.eventQueue.schedule(process, ctx.currentTime + delay)
                // ProcessActivated is emitted once by Simulation.run when this process first runs
                // (the cont == null branch). Emitting here too would double-fire for in-run activations.
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
            if (ctx.eventListeners.isNotEmpty()) {
                val event = SimulationEvent.ProcessReactivated(ctx.currentTime, process)
                ctx.eventListeners.forEach { it(event) }
            }
            ctx.waitNotices.removeAll { it.process === process }  // clear stale wait-until notices
            ctx.crossingNotices.removeAll { it.process === process }  // clear stale crossing notices
            ctx.eventQueue.remove(process)   // prevent duplicate if already scheduled
            ctx.eventQueue.schedule(process, ctx.currentTime)
        }

        /**
         * Current process joins [queue] and passivates.
         */
        suspend fun wait(queue: Head) {
            val ctx = activeContext ?: throw DiscoException("Not inside a simulation")
            val current = ctx.currentProcess
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
