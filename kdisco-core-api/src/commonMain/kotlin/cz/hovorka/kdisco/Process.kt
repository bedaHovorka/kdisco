package cz.hovorka.kdisco

/**
 * Base class for discrete-event simulation entities (processes).
 *
 * `Process` represents an active entity in a discrete-event simulation. Each process
 * executes its [actions] method, which defines the process's behavior using simulation
 * primitives like [hold], [passivate], and [terminate].
 *
 * ### Lifecycle
 * 1. **Created**: Process instance is constructed
 * 2. **Activated**: Process is scheduled via [activate] with optional delay
 * 3. **Running**: The [actions] method executes
 * 4. **Suspended**: Process calls [hold] or [passivate] and yields control
 * 5. **Resumed**: After hold duration expires or explicit [reactivate]
 * 6. **Terminated**: Process calls [terminate] or [actions] completes
 *
 * **Note**: The current API does not expose process state queries (e.g., `isActive()`,
 * `isPassivated()`, `isTerminated()`). To debug process lifecycle issues, use logging
 * or debugging breakpoints in [actions]. Future versions may add state query methods.
 *
 * ### Thread Model (JVM)
 * On JVM, each process runs in its own Java thread managed by the jDisco simulation
 * engine. Processes are scheduled cooperatively - they must explicitly call [hold],
 * [passivate], or [terminate] to yield control back to the simulation scheduler.
 *
 * ### Thread Safety and Shared State
 * **IMPORTANT**: On JVM, each process runs in its own thread. If multiple processes access
 * shared data structures (like [Head] or custom objects), you **must** use explicit
 * synchronization (locks, atomic variables, etc.) to prevent race conditions.
 *
 * The [Head] and [Link] classes are **not thread-safe**. If processes share a [Head] (e.g.,
 * a queue), wrap all operations in synchronized blocks:
 *
 * ```kotlin
 * val sharedQueue = Head()
 *
 * class Producer : Process() {
 *     override fun actions() {
 *         val item = Item()
 *         synchronized(sharedQueue) {  // Required!
 *             item.into(sharedQueue)
 *         }
 *     }
 * }
 * ```
 *
 * ### Common Pitfalls
 * **Self-scheduling**: Do not call [activate] on the same process instance from within
 * its own [actions] method, as this causes infinite loops or stack overflow:
 *
 * ```kotlin
 * // WRONG - causes infinite loop
 * override fun actions() {
 *     Process.activate(this, 0.0)  // Don't do this!
 *     hold(1.0)
 * }
 * ```
 *
 * **Shared state**: If multiple processes modify shared state without synchronization,
 * you will encounter race conditions and non-deterministic behavior.
 *
 * ### SIMULA Heritage
 * The process concept is inspired by SIMULA's coroutine-based simulation classes.
 * kDisco wraps the battle-tested jDisco library (used in production since 2007).
 *
 * ### Example
 * ```kotlin
 * class Customer : Process() {
 *     override fun actions() {
 *         println("Customer arrives at t=${time()}")
 *         hold(5.0)  // Wait for service
 *         println("Customer departs at t=${time()}")
 *     }
 * }
 *
 * runSimulation(endTime = 100.0) {
 *     repeat(10) { i ->
 *         Process.activate(Customer(), delay = i * 10.0)
 *     }
 * }
 * ```
 *
 * @see Continuous
 * @see Simulation
 * @see hold
 * @see passivate
 * @since 0.1.0
 */
expect abstract class Process() : Link {
    /**
     * Defines the behavior of this process.
     *
     * This method is called automatically when the process is activated and
     * becomes the current process. Subclasses must override this method to
     * implement their simulation logic.
     *
     * Use [hold] to suspend for a duration, [passivate] to wait for external
     * reactivation, or [terminate] to end execution. When this method returns
     * naturally (without calling [terminate]), the process terminates automatically.
     *
     * ### Example
     * ```kotlin
     * override fun actions() {
     *     println("Starting at t=${time()}")
     *     hold(10.0)
     *     println("After 10 time units: t=${time()}")
     *     passivate()  // Wait until someone reactivates us
     *     println("Reactivated at t=${time()}")
     * }
     * ```
     */
    protected abstract fun actions()

    /**
     * Suspends this process for the specified simulation time duration.
     *
     * The process will resume execution after [duration] time units have elapsed
     * in the simulation. During this time, other processes and events can execute.
     *
     * @param duration the number of simulation time units to wait (must be non-negative)
     * @throws IllegalArgumentException if duration is negative
     */
    fun hold(duration: Double)

    /**
     * Deactivates this process until explicitly reactivated.
     *
     * The process will remain suspended indefinitely until another process or
     * event calls [reactivate] on it. This is useful for modeling entities that
     * wait for external signals or resources.
     *
     * ### Example
     * ```kotlin
     * class Server : Process() {
     *     private var customer: Customer? = null
     *
     *     fun serve(c: Customer) {
     *         customer = c
     *         Process.reactivate(c)  // Wake up the customer
     *     }
     *
     *     override fun actions() {
     *         while (true) {
     *             passivate()  // Wait for next customer
     *             hold(serviceTime)  // Serve the customer
     *         }
     *     }
     * }
     * ```
     *
     * @see reactivate
     */
    fun passivate()

    /**
     * Terminates this process immediately and permanently.
     *
     * After calling this method, the process cannot be reactivated. Any code
     * following this call in [actions] will not execute.
     *
     * Marked `open` so that loop-managing subclasses (e.g. `LoopProcess`) can
     * override it with a graceful loop-exit instead of immediate termination.
     */
    open fun terminate()

    /**
     * Returns the current simulation time from this process's perspective.
     *
     * This is equivalent to calling `Simulation.current().time()` but more
     * convenient when writing process logic.
     *
     * @return the current simulation clock time
     */
    fun time(): Double

    /**
     * Activates continuous integration for this process (jDisco compatibility).
     *
     * In jDisco, `Process extends Continuous`, so every process can be started/stopped
     * as a continuous integrator. For pure discrete processes this is a no-op.
     * Subclasses with continuous state (extending [Continuous]) override this to
     * start differential-equation integration.
     *
     * @return this process (for chaining)
     */
    open fun start(): Process

    /**
     * Deactivates continuous integration for this process (jDisco compatibility).
     *
     * Counterpart to [start]. No-op for pure discrete processes.
     */
    open fun stop()

    /**
     * Suspends this process until the specified condition becomes true.
     *
     * The process becomes passive until the [condition]'s [Condition.test] method
     * evaluates to `true`. The condition is checked at each simulation time step
     * and at state-event detection points.
     *
     * ### Example
     * ```kotlin
     * // Wait until pressure reaches threshold
     * waitUntil { pressure.state >= 200 }
     *
     * // Or with explicit Condition instance
     * waitUntil(pressureHigh)
     * ```
     *
     * @param condition the condition to wait for
     * @see Condition
     */
    fun waitUntil(condition: Condition)

    /**
     * Tests whether this process has completed execution.
     *
     * @return `true` if this process has executed all its [actions]; `false` otherwise
     */
    fun terminated(): Boolean

    companion object {
        /**
         * Schedules a process to begin execution after an optional delay.
         *
         * The process will become active at time `currentTime + delay` and its
         * [actions] method will be invoked.
         *
         * ### SIMULA Event Queue Semantics
         * If [delay] is 0.0 (default), the process is scheduled at the current simulation
         * time but placed **after** the current process in the event queue (SIMULA semantics).
         * It will execute when the scheduler processes that event, not immediately.
         *
         * @param process the process to activate
         * @param delay the simulation time delay before activation (default: 0.0)
         * @throws IllegalArgumentException if delay is negative
         */
        fun activate(process: Process, delay: Double = 0.0)

        /**
         * Reactivates a previously passivated process.
         *
         * The process must have called [passivate] previously. It will be scheduled for
         * resumption at the current simulation time and will resume execution after the
         * statement where it called [passivate] when the scheduler processes the
         * reactivation event.
         *
         * @param process the process to reactivate (must be in passivated state)
         * @throws IllegalStateException if the process is not passivated
         */
        fun reactivate(process: Process)

        /**
         * Causes the currently active process to wait in a queue.
         *
         * The currently active process is added to the [queue] two-way list and then
         * [passivate]d. Equivalent to `this.into(queue); passivate()`.
         *
         * @param queue the Head list to join while waiting
         */
        fun wait(queue: Head)

        /**
         * Returns the current simulation time.
         *
         * This companion version is equivalent to calling [time] on the current process.
         * Useful when calling from non-process context or when the process instance
         * is not directly available.
         *
         * @return the current simulation clock time
         */
        fun time(): Double

        /**
         * The minimum allowable integration step-size.
         *
         * Controls the accuracy of state-event detection via [waitUntil].
         * Smaller values increase accuracy but may slow continuous simulation.
         * Default: 1e-5.
         */
        var dtMin: Double

        /**
         * The maximum allowable integration step-size.
         *
         * Controls the maximum time step during continuous simulation phases.
         * Larger values speed up simulation but may reduce accuracy.
         * Default: 1.0.
         */
        var dtMax: Double

        /**
         * The upper bound for the absolute integration error.
         * Default: 1e-5.
         */
        var maxAbsError: Double

        /**
         * The upper bound for the relative integration error.
         * Default: 1e-5.
         */
        var maxRelError: Double
    }
}
