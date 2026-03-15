package cz.hovorka.kdisco.engine

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

    /**
     * Executes the simulation until [endTime] or [stop] is called.
     *
     * Process coroutines are launched into a dedicated [CoroutineScope] that is
     * independent of the caller's scope. This prevents [kotlinx.coroutines.test.runTest]
     * from waiting on suspended process coroutines after the simulation ends.
     *
     * [Dispatchers.Unconfined] ensures coroutines start and resume synchronously on
     * the calling thread, giving deterministic single-threaded execution.
     */
    suspend fun run(endTime: Double) {
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
            while (!context.stopRequested) {
                currentCoroutineContext().ensureActive()
                val next = context.eventQueue.removeFirst() ?: break
                if (next.time > endTime) break

                context.currentTime = next.time
                val process = next.process
                context.currentProcess = process

                val cont = process.continuation
                if (cont != null) {
                    // Resume existing coroutine (returning from hold/passivate)
                    process.continuation = null
                    cont.resumeWith(Result.success(Unit))
                } else {
                    // First activation — launch new coroutine for process.actions()
                    simScope.launch {
                        try {
                            process.actions()
                        } catch (_: ProcessTerminatedException) {
                            // Process called terminate() — expected, not an error
                        } finally {
                            process._terminated = true
                        }
                    }
                    // With Unconfined, the launched coroutine runs synchronously until
                    // the process calls hold()/passivate()/terminate(), then control
                    // returns here for the next event.
                }
            }
        } finally {
            context.isRunning = false
            Process.activeContext = previousContext
            // Cancel any remaining suspended coroutines (passivated processes that
            // were never reactivated, or processes whose hold() time is past endTime).
            simScope.cancel()
            withContext(NonCancellable) { simJob.join() }
        }
    }

    /** Returns the current simulation clock time. */
    fun time(): Double = context.currentTime

    /** Requests the simulation to stop after the current event. */
    fun stop() {
        context.stopRequested = true
    }

    companion object {
        /**
         * Creates a new [Simulation] and runs [setup] with it as the receiver.
         * Processes activated during [setup] are queued for execution when [run] is called.
         */
        fun create(setup: Simulation.() -> Unit): Simulation {
            val simulation = Simulation()
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
