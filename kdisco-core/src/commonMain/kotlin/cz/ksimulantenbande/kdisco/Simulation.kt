// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco

import kotlinx.coroutines.CancellationException
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
 *
 * ## Threading
 *
 * A simulation is thread-confined. `Simulation` and `SimulationContext` use no locks or atomics
 * beyond the `@Volatile` stop flag: the engine stays on one thread only because the event loop in
 * [run] issues every resumption of every process body (they are launched on
 * [Dispatchers.Unconfined]). The *engine thread* is the thread the coroutine that runs [run] is
 * executing on.
 *
 * **Thread-confined — engine thread only.** Everything except the members listed under
 * *Publishable*: the clock ([time]), the queue queries ([nextEventTime], [scheduledEventCount],
 * [activeProcessCount]), [isRunning], the integration parameters, every [Process] and
 * [Continuous], [Variable.state] and [Variable.rate], [Head] and [Link] lists, [Resource], and the
 * listener registries. These are plain fields: read from another thread while [run] executes, a
 * value can be stale or torn (a `Double` is not guaranteed to be read atomically). Neither read
 * nor write them off the engine thread.
 *
 * **Publishable — any thread.** [stop] and [isStopRequested] (the flag is `@Volatile`), and
 * [SimulationController]'s [SimulationController.pause], [SimulationController.resume],
 * [SimulationController.step], [SimulationController.setThrottle] and
 * [SimulationController.isPaused]. Step requests are not counted atomically, though: issue the
 * next [SimulationController.step] only after [SimulationController.isPaused] is `true` again (one
 * outstanding step at a time). Concurrent or rapid-fire `step()` calls may coalesce into fewer
 * steps.
 *
 * **Sampling state for another thread.** Sample on the engine thread — in a process body, an
 * [onEvent] listener or the `beforeEvent` hook of [run] — and publish one immutable value, for
 * example a data-class snapshot in a `@Volatile` field or an `AtomicReference`, or sent on a
 * channel. The off-thread reader reads only that value, never the live fields.
 *
 * **When [stop] takes effect.** The flag is read at the top of each event-loop iteration, before
 * the `beforeEvent` hook, and after each counted integration retry (see [run]); an iteration that
 * has started processes at most one event. A run paused in [SimulationController.beforeEvent]
 * therefore does not end on [stop]: it ends only after the controller is resumed
 * ([SimulationController.resume] or [SimulationController.step]) and the paused iteration has
 * processed at most one event, or when the coroutine that runs [run] is cancelled. [run] clears the
 * flag when it starts, so a [stop] issued before [run] begins is lost.
 *
 * **Stopping reliably from another thread.** To stop from another thread regardless of whether
 * [run] has started, set your own `@Volatile` flag and call [stop] from the `beforeEvent` hook of
 * [run] when it is set — the run then processes at most that iteration's event and ends — or
 * cancel the coroutine that runs [run].
 *
 * **What [stop] cannot interrupt.** [stop] does not interrupt a single integration step in
 * progress; inside the integration retry loop (see [IntegrationRestartKind]) it is honoured after
 * the current retry is counted. A process body that spins without suspending cannot be
 * interrupted at all, neither by [stop] nor by cancellation: the engine regains control only when
 * the body suspends.
 *
 * **Which thread the engine resumes on.** The pause in [SimulationController.beforeEvent] and the
 * throttle `delay` suspend the coroutine that runs [run]. It resumes on that coroutine's
 * dispatcher, and the engine — and every process body after it — continues there:
 * - Under `runBlocking`, on the thread that called [run]. This is the supported way to drive a
 *   controlled run from another thread.
 * - Under [Dispatchers.Unconfined], on the thread that called [SimulationController.resume]
 *   (or [SimulationController.step]). There the JVM `ThreadLocal` that holds
 *   [Process.activeContext] is unset, so calls that look up the active simulation —
 *   [Process.activate], [Variable.start], [Continuous.start], [emitCustom] and the like — throw
 *   [DiscoException] or silently do nothing. Unsupported for controlled runs.
 * - On a pool dispatcher such as `Dispatchers.Default`, on a pool worker, possibly a different one
 *   after every pause, where the same `ThreadLocal` is unset. Unsupported for controlled runs, too:
 *   in addition, the teardown of [run] restores the JVM `ThreadLocal` on whatever worker the run
 *   ends on, leaving a stale context on the worker that started it.
 *
 * **One running simulation per process on js and native.** On JVM the active simulation is held
 * per thread, so simulations may run concurrently, each on its own thread. On js and native it is
 * a single global: at most one simulation may be running at a time.
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

    /**
     * How many times in a row continuous integration may restart for one event boundary before
     * [run] fails with [IntegrationRetryLimitException]. Exactly this many retries are allowed; the
     * next one throws. Must be in `1 until Int.MAX_VALUE`, so the failing retry can still be
     * counted. Defaults to 10 000. See [run].
     *
     * Both kinds of [IntegrationRestartKind] count. An [IntegrationRestartKind.NOTICE_RELEASE] retry
     * keeps the accepted step end, so the clock advances between such retries: a model whose wait
     * condition queues or drops a turn on every step during a long continuous phase can reach the
     * limit while still progressing. Raise this limit for such models.
     */
    var integrationRetryLimit: Int = DEFAULT_INTEGRATION_RETRY_LIMIT
        set(value) {
            require(value in 1 until Int.MAX_VALUE) {
                "integrationRetryLimit must be in 1 until Int.MAX_VALUE, got $value"
            }
            field = value
        }

    /**
     * Consecutive integration restarts for one event boundary at which [run] emits
     * [SimulationEvent.Diagnostic.IntegrationRetryThresholdCrossed], once per boundary. Must be > 0.
     * Defaults to 100. See [run]. A threshold of `integrationRetryLimit + 1` fires on the same retry
     * that fails the run; a higher one never fires: the run fails before the count reaches it.
     */
    var integrationRetryWarnThreshold: Int = DEFAULT_INTEGRATION_RETRY_WARN_THRESHOLD
        set(value) {
            require(value > 0) { "integrationRetryWarnThreshold must be positive, got $value" }
            field = value
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

    private val failureListeners = mutableListOf<(Process, Throwable) -> Unit>()

    /** First failure recorded under [stopOnProcessFailure]; thrown by [run] once it has cleaned up. */
    private var processFailure: ProcessFailureException? = null

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
     * An exception escaping a process's [Process.actions] is reported through [onProcessFailure]
     * and [SimulationEvent.ProcessFailed]; see [stopOnProcessFailure] to end the run on it. A
     * [CancellationException] thrown by user code is treated as cancellation, not reported as a
     * failure. An [onEvent] listener that throws while a process body is executing becomes a
     * failure of that process. A throwable raised by a process's cleanup while this function tears
     * down (after the event loop has exited) is not a process failure; it goes to the
     * uncaught-exception handler.
     *
     * Exceptions thrown by user code that runs on the engine coroutine outside a process body —
     * [Continuous.derivatives], wait and crossing conditions and their guards, the [beforeEvent]
     * hook, and [onEvent] listeners called from the event loop itself — propagate out of this
     * function unchanged.
     *
     * Precedence: cancellation of the coroutine running this function wins over a pending process
     * failure — the [CancellationException] is thrown, not the [ProcessFailureException] — and so
     * does an [IntegrationRetryLimitException]. Cancellation wins even when it comes from an
     * [onProcessFailure] listener reacting to that very failure: the caller's coroutine is checked
     * after cleanup, before the failure is thrown.
     *
     * Continuous integration towards one event boundary is retried whenever user code queues or
     * drops a turn during a step (see [IntegrationRestartKind]). Those retries are bounded: when
     * [integrationRetryWarnThreshold] consecutive retries (default 100) are reached for one
     * boundary, a [SimulationEvent.Diagnostic.IntegrationRetryThresholdCrossed] naming the time,
     * the retry count, the restart kind and the mutating source is emitted to [onEvent] listeners,
     * once per boundary. [integrationRetryLimit] (default 10 000) retries are allowed; the next one
     * ends the run with [IntegrationRetryLimitException], thrown after the usual teardown (process
     * scope cancelled, event queue cleared). That is an engine failure, not a process failure: it
     * does not go through [onProcessFailure]. The count restarts at zero for every boundary.
     *
     * A stop requested while integration is being retried — by [stop] from an [onEvent] listener
     * reacting to the diagnostic, or by a process failure under [stopOnProcessFailure] released
     * inside the step — is honoured after the retry is counted: the run ends there (normally, or
     * with the recorded [ProcessFailureException]), with no further integration and no event
     * processed. A stop requested during a step that is not retried takes effect, as always, at the
     * top of the next event-loop iteration.
     *
     * @throws ProcessFailureException if [stopOnProcessFailure] is set and a process failed.
     * @throws IntegrationRetryLimitException if integration restarted more than
     *   [integrationRetryLimit] times in a row for one event boundary.
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
            scheduler@ while (!context.stopRequested) {
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
                    //
                    // Bounded: user code that queues or drops a turn on every retry would otherwise
                    // spin here at (nearly) constant simulated time. See countIntegrationRetry.
                    var retries = 0
                    while (context.monitor.integrateUntil(integrationBoundary(next, endTime))) {
                        currentCoroutineContext().ensureActive()
                        countIntegrationRetry(++retries)
                        // The diagnostic's listener, or a failure under stopOnProcessFailure, may
                        // have asked to stop: retrying on would ignore it until the limit.
                        if (context.stopRequested) break@scheduler
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

                // Now pop and process the event.
                val event = context.eventQueue.removeFirst() ?: break
                if (event.time > endTime) {
                    // Put it back before stopping. Its process is in exactly the position of the
                    // ones still queued behind it — holding a turn the run will never deliver —
                    // and the cleanup below only sees what is in the queue.
                    context.eventQueue.schedule(event.process, event.time)
                    break
                }

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
                        simScope.launch { runProcess(process) }
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
        processFailure?.let {
            // A caller cancelled while the run was stopping — possibly by the failure listener
            // itself — wins over the recorded failure.
            currentCoroutineContext().ensureActive()
            throw it
        }
        return true
    }

    /** Body of a process coroutine: runs [Process.actions] and handles how it ends. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runProcess(process: Process) {
        try {
            process.actions()
        } catch (_: ProcessTerminatedException) {
            // Process called terminate() — expected, not an error
        } catch (cancellation: CancellationException) {
            // The run is over and cancelled the process's coroutine — not a process failure
            throw cancellation
        } catch (failure: Throwable) {
            onFailure(process, failure)
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

    /**
     * Reports an exception that escaped [Process.actions]: records it and stops the run when
     * [stopOnProcessFailure] is set, then emits [SimulationEvent.ProcessFailed] and notifies the
     * [onProcessFailure] listeners. A failure nobody handles is rethrown to the coroutine's
     * uncaught-exception handler.
     *
     * A throwable raised while [run] tears down — after its event loop has exited, when the
     * remaining process coroutines are cancelled — is not a process failure: engine calls are not
     * supported there, so a cleanup block making one would otherwise turn a run that ended normally
     * into a failed one. It goes to the uncaught-exception handler unreported.
     */
    private fun onFailure(process: Process, failure: Throwable) {
        if (!context.isRunning) throw failure
        if (!stopOnProcessFailure) {
            throwFirstOf(notifyFailure(process, failure))
            if (failureListeners.isEmpty()) throw failure
            return
        }
        // Record and stop first, so a listener that throws cannot cancel the stop.
        val recorded = processFailure
            ?: ProcessFailureException(process, context.currentTime, failure).also { processFailure = it }
        context.stopRequested = true
        notifyFailure(process, failure).forEach(recorded::addSuppressed)
    }

    /** Throws the first of [thrown] with the later ones suppressed on it; returns if it is empty. */
    private fun throwFirstOf(thrown: List<Throwable>) {
        if (thrown.isEmpty()) return
        val first = thrown.first()
        for (i in 1 until thrown.size) first.addSuppressed(thrown[i])
        throw first
    }

    /**
     * Emits [SimulationEvent.ProcessFailed] and calls every [onProcessFailure] listener, each in
     * isolation: a hook that throws does not keep the later ones from running. Returns what the
     * hooks threw, in the order they threw it (empty when none did).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun notifyFailure(process: Process, failure: Throwable): List<Throwable> {
        val thrown = mutableListOf<Throwable>()
        try {
            context.emit { SimulationEvent.ProcessFailed(context.currentTime, process, failure) }
        } catch (listenerFailure: Throwable) {
            thrown += listenerFailure
        }
        for (listener in failureListeners) {
            try {
                listener(process, failure)
            } catch (listenerFailure: Throwable) {
                thrown += listenerFailure
            }
        }
        return thrown
    }

    /**
     * Accounts for the [retries]-th consecutive integration restart of one event boundary: emits
     * [SimulationEvent.Diagnostic.IntegrationRetryThresholdCrossed] when it reaches
     * [integrationRetryWarnThreshold], and throws [IntegrationRetryLimitException] when it exceeds
     * [integrationRetryLimit].
     */
    private fun countIntegrationRetry(retries: Int) {
        // Captured before the diagnostic: a listener queueing a turn of its own would otherwise
        // overwrite the source the exception names.
        val source = context.eventQueue.lastMutationSource
        val kind = context.monitor.lastRestartKind
        if (retries == integrationRetryWarnThreshold) {
            context.emit {
                SimulationEvent.Diagnostic.IntegrationRetryThresholdCrossed(context.currentTime, source, retries, kind)
            }
        }
        if (retries > integrationRetryLimit) {
            throw IntegrationRetryLimitException(context.currentTime, retries, kind, source)
        }
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
     * Register a listener that is called when a process's [Process.actions] throws an exception
     * other than the engine's own termination signal (and other than coroutine cancellation).
     *
     * The listener receives the failed process and the throwable, exactly once per failure, from
     * the failing process's coroutine at the failure's simulation time — before the process is
     * marked terminated and before [SimulationEvent.ProcessTerminated] is emitted. A
     * [SimulationEvent.ProcessFailed] is also delivered to [onEvent] listeners.
     *
     * By default a failed process does not affect the others and the run continues; set
     * [stopOnProcessFailure] to end the run instead. Registering a listener (or setting
     * [stopOnProcessFailure]) counts as handling the failure: a failure with at least one
     * registered listener is no longer rethrown to the coroutine's uncaught-exception handler.
     * Only a failure that nobody handles (no listener and [stopOnProcessFailure] off) is
     * rethrown there, as before. Listeners are additive.
     *
     * Every failure hook is attempted: the [SimulationEvent.ProcessFailed] emission and each
     * listener run in isolation, so one that throws does not keep the later ones from running.
     * Exceptions they throw are collected in order. When [stopOnProcessFailure] is set each is
     * added as suppressed to the [ProcessFailureException]; otherwise the first one ends the
     * failing process's coroutine, with the later ones suppressed on it, and reaches the
     * uncaught-exception handler. A listener that cancels the coroutine running [run] makes [run]
     * throw the [CancellationException] rather than the [ProcessFailureException]. A throwable
     * raised while [run] tears down is not reported here (see [run]).
     */
    fun onProcessFailure(listener: (process: Process, failure: Throwable) -> Unit) {
        failureListeners += listener
    }

    /**
     * When `true`, the first process failure (see [onProcessFailure]) stops the run: [run] ends
     * at the failure time, after its usual cleanup, and throws a [ProcessFailureException]
     * carrying the failed process, the time and the original throwable as its cause.
     * Defaults to `false` — a failed process terminates alone and the run continues.
     *
     * The failure is recorded and the stop requested before [SimulationEvent.ProcessFailed] is
     * emitted and the [onProcessFailure] listeners are called; an exception thrown by one of those
     * listeners does not cancel the stop but is added as suppressed to the thrown
     * [ProcessFailureException]. The stop shares the flag [stop] sets, so [isStopRequested] is
     * `true` after such a failure.
     *
     * With this option on and no [onProcessFailure] listener registered, kDisco logs nothing: the
     * embedder must log the [ProcessFailureException] it catches from [run].
     */
    var stopOnProcessFailure: Boolean = false

    /**
     * Returns the scheduled time of the next pending event, or [Double.MAX_VALUE] if no
     * events are queued. May be called from the [run] [beforeEvent] hook to implement
     * step-event control.
     */
    fun nextEventTime(): Double = context.eventQueue.peek()?.time ?: Double.MAX_VALUE

    /** Number of events currently waiting in the event queue. */
    fun scheduledEventCount(): Int = context.eventQueue.size()

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

    /**
     * Requests the simulation to stop after the current event — or, called while continuous
     * integration is being retried (see [run]), after the current retry. Safe to call from any
     * thread; see the "Threading" section of [Simulation] for when the request takes effect.
     *
     * [run] clears the request when it starts. To stop reliably from another thread regardless of
     * whether [run] has started, set your own `@Volatile` flag and call [stop] from the
     * `beforeEvent` hook of [run] when it is set (the run then processes at most that iteration's
     * event and ends), or cancel the coroutine that runs [run].
     */
    fun stop() {
        context.stopRequested = true
    }

    /** `true` while the simulation is executing [run]. */
    val isRunning: Boolean get() = context.isRunning

    /**
     * `true` if the simulation has been requested to stop — by [stop], or by a process failure
     * under [stopOnProcessFailure], which sets the same flag. Safe to call from any thread.
     */
    fun isStopRequested(): Boolean = context.stopRequested

    companion object {
        private const val DEFAULT_INTEGRATION_RETRY_LIMIT = 10_000
        private const val DEFAULT_INTEGRATION_RETRY_WARN_THRESHOLD = 100

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
