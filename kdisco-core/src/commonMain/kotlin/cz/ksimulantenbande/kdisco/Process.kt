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

    /**
     * Has a turn coming without an explicit [Process.reactivate] — an event in the queue
     * (e.g. after [hold]) or, before [Simulation.run] starts, a pending activation.
     */
    SCHEDULED,

    /**
     * Parked on a condition or guard notice — [Process.waitUntil], [Process.waitCrossing] or
     * [Process.waitUntilCrossing]. The wake-up is owned by [SimulationContext.waitNotices] /
     * [SimulationContext.crossingNotices], not by the event queue: the process has no turn of
     * its own, which is why [Process.activate] grants it an independent one.
     *
     * This is *not* the same as "has no event in the queue". All three release paths schedule the
     * process without changing its state, so between a notice firing and the scheduler taking that
     * turn the process is still [WAITING] with an event queued for it. See [Process.isWaiting].
     */
    WAITING,

    /** Suspended and waiting for explicit [Process.reactivate]. */
    PASSIVATED,

    /** Completed normally or stopped via [Process.terminate]. */
    TERMINATED,
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

    @Suppress("ktlint:standard:backing-property-naming", "VariableNaming")
    internal val _terminated: Boolean get() = _state == ProcessState.TERMINATED

    @Suppress("ktlint:standard:backing-property-naming", "VariableNaming")
    internal var _state: ProcessState = ProcessState.IDLE

    /**
     * How many events are queued for this process right now. Maintained by [EventQueue] on every
     * schedule and removal.
     *
     * [_state] alone cannot answer "does this process have a turn coming?". A process parked on a
     * notice can be carried through [ProcessState.RUNNING] to [ProcessState.PASSIVATED] by its
     * notice's wake-up while a turn queued by an independent [activate] is still outstanding —
     * the state then describes the latest suspension point and says nothing about that turn. This
     * count does, so [isActive] and [activate] consult it.
     */
    internal var queuedEvents: Int = 0

    /**
     * How many of [queuedEvents] are a notice's wake-up rather than a turn this process owns.
     *
     * The difference is what [activate] guards on. A release event belongs to the *wait* — the
     * process has no turn of its own — so activating it then is not a duplicate. A [hold],
     * [activate] or [reactivate] event does belong to the process, and is, even once the process
     * has moved on to a different suspension point (including a *second* wait, which is how a
     * state-only test misses it).
     */
    internal var noticeReleases: Int = 0

    /** Queued events that are this process's own turn, rather than a notice's wake-up. */
    internal val ownedTurns: Int get() = queuedEvents - noticeReleases

    /**
     * Simulation time at which this process's current [hold] is due to end, or
     * [Double.NEGATIVE_INFINITY] when it is not holding.
     *
     * The scheduler uses it, together with [queuedEvents], to recognise an event delivered to a
     * mid-[hold] process for some other reason (a *spurious* resume) and drop it instead of
     * cutting the hold short. Cleared by the scheduler on every genuine resume, so it is never
     * stale.
     */
    internal var holdDue: Double = Double.NEGATIVE_INFINITY

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
     * adds a notice. [register] runs *before* the cancellation handler is installed. The handler
     * marks the process terminated and calls [onCancel], which drops whatever this parking site
     * depends on — what [register] created, or a notice registered outside the re-park loop and
     * kept across re-parks (see [awaitCrossing]) — so a process cancelled at end of run leaves no
     * stale queue entry or notice behind.
     *
     * `inline` with `crossinline` lambdas is required, not cosmetic: a plain `suspend` wrapper
     * would add a continuation object and an extra `resumeWith` hop to every [hold], which is the
     * innermost loop of the scheduler (see `TickSchedulingBenchmark`).
     */
    private suspend inline fun park(
        state: ProcessState,
        crossinline register: () -> Unit,
        crossinline onCancel: () -> Unit,
    ) {
        suspendCancellableCoroutine<Unit> { cont ->
            _state = state
            continuation = cont
            register()
            cont.invokeOnCancellation {
                continuation = null
                _state = ProcessState.TERMINATED
                onCancel()
            }
        }
    }

    /**
     * Suspends this process for the specified simulation time duration.
     *
     * A *spurious* resume — an event delivered to this process for some other reason, e.g. the
     * surplus turn an [activate] granted while the process was parked in [waitUntil], left over
     * once the wait ended at that same instant — does not shorten the hold. The scheduler drops
     * such an event while the clock has not reached the hold's due time and this process's own
     * hold event is still queued. [Process.reactivate] removes that event before rescheduling, so
     * it still cuts a hold short as documented.
     */
    suspend fun hold(duration: Double) {
        require(duration >= 0.0) { "Duration must be non-negative, got $duration" }
        park(
            state = ProcessState.SCHEDULED,
            register = {
                val due = context.currentTime + duration
                holdDue = due
                context.eventQueue.schedule(this@Process, due)
                context.emit { SimulationEvent.ProcessHeld(context.currentTime, this@Process, duration) }
            },
            onCancel = { context.eventQueue.remove(this@Process) },
        )
    }

    /**
     * Deactivates this process until explicitly reactivated via [Process.reactivate].
     */
    suspend fun passivate() {
        park(
            state = ProcessState.PASSIVATED,
            register = { context.emit { SimulationEvent.ProcessPassivated(context.currentTime, this@Process) } },
            onCancel = {},
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
     * true before returning. An independent [Process.activate] on a parked process is one such
     * spurious wake-up: the loop absorbs it while the condition is false, and the extra turn
     * survives only once the condition is confirmed true. [Process.reactivate] likewise resumes
     * the process, which re-tests the condition and re-parks if it is still false.
     *
     * Must only be called from within [actions] (i.e., from a running process).
     */
    suspend fun waitUntil(condition: Condition) {
        // One notice object per wait, re-registered on each re-park — the invariant is that at
        // most one live notice exists per process, so the condition can never deliver two wake-ups.
        val notice = WaitNotice(this, condition)
        while (!condition.test()) {
            park(
                state = ProcessState.WAITING,
                register = { context.waitNotices.add(notice) },
                onCancel = { context.waitNotices.remove(notice) },
            )
            // The notice is removed by checkWaitNotices at the instant it fires. If this resume
            // came from anywhere else — an independent Process.activate, a reactivate — the notice
            // is still registered and has to be dropped here, or the next re-park would add it to
            // the registry a second time (a duplicate list entry) and the condition would later
            // deliver two wake-ups.
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
     * **Cancellation by [reactivate]/[terminate], and [activate].** [Process.reactivate] drops the
     * crossing notice and resumes the process at the current time; [Process.terminate] drops it
     * without resuming. [Process.activate] does neither — it queues a turn, which this wait absorbs
     * by re-parking while its notice is still registered, so the process still resumes at the
     * crossing and nowhere else. That holds only *while the notice is registered*: if the notice
     * fires with an activate-queued turn still outstanding — e.g. a crossing located before a
     * delayed `activate`'s event is taken — the wait ends at the crossing and the outstanding turn
     * survives as a surplus resume at the process's next suspension point, the same way a
     * confirmed-true [waitUntil] keeps its turn.
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
     * [Process.activate] is not a cancellation: the turn it queues is absorbed by the re-park loop
     * while the level notice is still registered, so the wait still ends at the crossing. One
     * exception, by design: if the guard becomes satisfied in the very event that issues the
     * activate, the post-event level re-test fires the notice too — the wait ends, and the turn
     * survives as a surplus resume at the process's next suspension point, exactly like a
     * confirmed-true [waitUntil].
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
        if (guard() <= 0.0) return // level-triggered: already satisfied, no wait
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
                onCancel = { context.crossingNotices.remove(notice) },
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
        context.emit { SimulationEvent.ProcessTerminated(context.currentTime, this) }
        continuation = null
        // Every wake-up source must be dropped. Leaving a wait notice behind would have its
        // condition re-evaluated after every event and every integration step for the rest of the
        // run, repeatedly scheduling a dead process; leaving a pending pre-run activation behind
        // would let Simulation.run turn it into an event that advances the clock for a dead one.
        context.dropWakeUpsOf(this)
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
     * [Process.reactivate] — i.e. it is [ProcessState.RUNNING], [ProcessState.SCHEDULED],
     * [ProcessState.WAITING], or has an event queued for it. It is never active after it has
     * [terminate]d.
     *
     * The queued-event clause is what makes this honest about a surplus turn. A process parked on
     * a notice can be released by that notice while a turn queued by an independent [activate] is
     * still outstanding; the release carries it to its next suspension point, so [passivate] leaves
     * it [ProcessState.PASSIVATED] with an event still queued. It *will* run again, so this reports
     * true — and [isPassivated] reports true at the same time, describing the suspension point it
     * is parked at. The two are not mutually exclusive in that window.
     *
     * Use [isWaiting] to tell a process parked on a condition or guard notice apart from one that
     * has an event in the queue.
     */
    fun isActive(): Boolean = when (_state) {
        ProcessState.TERMINATED -> false
        ProcessState.RUNNING, ProcessState.SCHEDULED, ProcessState.WAITING -> true
        ProcessState.IDLE, ProcessState.PASSIVATED -> queuedEvents > 0
    }

    /**
     * Returns true while this process is parked on a condition or guard notice — suspended in
     * [waitUntil], [waitCrossing] or [waitUntilCrossing] — and its wake-up has not yet been
     * delivered.
     *
     * A waiting process's wake-up is owned by the notice registry, not the event queue, which is
     * why [activate] treats it differently from a [hold]-scheduled process. Note that an [activate]
     * on a waiting process queues a turn and moves it to [ProcessState.SCHEDULED], so this reports
     * false from that moment until the turn is taken, even though the wait itself is still pending.
     *
     * This is *not* the same as "has no event in the queue". When a notice fires, the release
     * paths ([SimulationContext.checkWaitNotices], [SimulationContext.checkLevelCrossings] and
     * [ContinuousMonitor]'s crossing location) schedule the process without changing its state, so
     * between the notice firing and the scheduler taking that turn this still reports true while an
     * event for the process is queued. A [Simulation.run] `beforeEvent` hook can observe that
     * window. Read it as "parked until its notice event is delivered".
     */
    fun isWaiting(): Boolean = _state == ProcessState.WAITING

    /**
     * True when this process already has a turn of its own — it is [ProcessState.RUNNING], it is
     * [ProcessState.SCHEDULED] (an event queued, or a pending activation before the run starts), or
     * it is parked at a [passivate] it has not reached the end of yet with an event still queued
     * for it. This, not [isActive], is what [activate] guards on.
     *
     * The two cases the state alone gets wrong:
     *
     * - A [ProcessState.WAITING] process usually has no turn of its own — its wake-up is owned by
     *   the notice registry — so activating it is not a duplicate, even once the notice has fired
     *   and queued the release event. A plain "is anything queued for it?" test would suppress the
     *   independent turn in exactly that window. Hence [ownedTurns], which excludes release events,
     *   rather than [queuedEvents]. But *usually* is not *always*: a process can leave one wait via
     *   its notice while an earlier [activate]'s turn is still queued and immediately enter a
     *   second one, and it is then waiting *and* holding a turn. Reading the state alone would
     *   queue a duplicate there.
     * - Conversely, a process whose notice released it while an earlier [activate]'s turn was still
     *   queued is left describing its new suspension point ([ProcessState.PASSIVATED], say) while
     *   that turn waits in the queue. Guarding on the state alone would queue a second one and
     *   break the documented "the existing schedule wins" contract.
     */
    internal fun hasOwnTurn(): Boolean = when (_state) {
        ProcessState.RUNNING, ProcessState.SCHEDULED -> true
        ProcessState.TERMINATED -> false
        ProcessState.IDLE, ProcessState.PASSIVATED, ProcessState.WAITING -> ownedTurns > 0
    }

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
            set(value) {
                SimulationContextHolder.context = value
            }

        /**
         * Schedules a process to begin execution after an optional delay.
         *
         * **No-op** when [process] is already running, already has a turn of its own (mid-[hold],
         * or activated earlier at this or a later time), or has terminated. The existing schedule
         * wins and no duplicate event is created. To move an already-scheduled process to the
         * current time, use [reactivate].
         *
         * "A turn of its own" is narrower than "has an event in the queue", in both directions.
         * A process released from a wait while an earlier activation's turn was still queued is
         * parked at its next suspension point with that turn outstanding, so activating it again
         * is a duplicate even though its state no longer says so. Conversely a process whose
         * notice has fired has an event queued that belongs to the *wait*, not to it, so an
         * activate then is not a duplicate — which is the exception spelled out below. See
         * [hasOwnTurn].
         *
         * A process parked in [waitUntil], [waitCrossing] or [waitUntilCrossing] is deliberately
         * **not** covered by that guard. Such a process has no turn of its own — its wake-up lives
         * in the notice registry — so `activate` queues an independent one. The two wake-ups are
         * separate intents and neither may be spent on the other (issue #73). What the queued turn
         * then does depends on the primitive, and none of them ends the wait early:
         *
         * - **[waitUntil]**: the turn re-enters the condition loop. If the condition is confirmed
         *   true the wait ends and the turn survives as a genuine extra resume — the case issue #73
         *   describes. If the condition is still false the loop re-parks and the turn is absorbed.
         * - **[waitCrossing] / [waitUntilCrossing]**: the turn is absorbed while the notice is
         *   still registered. These are root-found waits with no condition to re-test, so they
         *   re-park and the process resumes at the crossing and nowhere else. If the notice fires
         *   with the turn still outstanding — a [waitUntilCrossing] guard satisfied in the same
         *   event, or a crossing located before a delayed turn is taken — the wait ends and the
         *   turn survives as a surplus resume at the next suspension point, like the confirmed
         *   [waitUntil] case above.
         *
         * Use [reactivate] when the intent is to *cancel* the pending wait and resume now.
         *
         * A surviving extra turn is delivered at whatever suspension point the process reaches
         * next. That is clean when the process passivates (the shape in issue #73). If it is a
         * [hold], the hold returns at once and its own event stays queued, so the surplus resume
         * moves on to the following suspension point — see
         * `ProcessTest.extraTurnGrantedDuringWaitUntilLandsOnTheNextSuspensionPoint`.
         */
        fun activate(process: Process, delay: Double = 0.0) {
            require(delay >= 0.0) { "Delay must be non-negative, got $delay" }
            val ctx = activeContext ?: throw DiscoException("Not inside a simulation")
            if (process._terminated) return // mirrors reactivate(); never resurrect the dead
            if (process.hasOwnTurn()) return // already has a turn — no duplicate event
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
         * Reactivates a process at the current time.
         *
         * No-op if [process] is already terminated.
         * If [process] is already in the event queue (e.g. mid-[hold]), it is
         * rescheduled at the current time (no duplicate event is created).
         * If [process] is parked in [waitUntil], [waitCrossing] or [waitUntilCrossing], the pending
         * notice is dropped and the process resumes now — this, not [activate], is the call that
         * *cancels* a wait. One nuance: a [waitUntil] whose condition is still false is not over
         * yet — the resumed process re-tests the condition and re-parks (the dropped notice is
         * replaced by a fresh one). The crossing waits have nothing to re-test, so for them the
         * wait truly ends here.
         */
        fun reactivate(process: Process) {
            if (process._terminated) return
            val ctx = process.context
            process._state = ProcessState.SCHEDULED
            ctx.emit { SimulationEvent.ProcessReactivated(ctx.currentTime, process) }
            // Drops the notices, any queued event, and any pending pre-run activation. The last
            // one matters when reactivate is called during Simulation.create setup: run() would
            // otherwise also convert that entry into a second, later event for this process.
            ctx.dropWakeUpsOf(process)
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

internal class PendingActivation(val process: Process, val delay: Double)
