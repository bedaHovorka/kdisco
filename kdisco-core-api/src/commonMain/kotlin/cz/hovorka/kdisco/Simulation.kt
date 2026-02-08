package cz.hovorka.kdisco

/**
 * Manages the simulation clock, event scheduling, and execution control.
 *
 * `Simulation` is the central coordinator for a discrete-event or continuous
 * simulation. It maintains the simulation clock, schedules process activations,
 * and executes the simulation until a specified end time or explicit [stop] call.
 *
 * ### Simulation Context
 * Each simulation maintains its own independent context:
 * - Simulation clock starting at time 0.0
 * - Event queue for scheduled process activations
 * - Set of active processes
 *
 * On JVM, simulations are thread-local - each thread can have its own independent
 * simulation running concurrently.
 *
 * ### Typical Usage
 * Use the [simulation] or [runSimulation] DSL functions instead of calling
 * [create] directly:
 *
 * ```kotlin
 * runSimulation(endTime = 100.0) {
 *     // 'this' is the Simulation instance
 *     Process.activate(MyProcess())
 * }
 * ```
 *
 * ### JVM Implementation
 * On JVM, kDisco wraps the battle-tested jDisco library (used in production
 * since 2007). The jDisco engine uses:
 * - Priority queue for efficient event scheduling
 * - Java threads for process execution (one thread per process)
 * - Runge-Kutta integration for continuous simulation
 *
 * ### Example: Simple Discrete Simulation
 * ```kotlin
 * class Customer : Process() {
 *     override fun actions() {
 *         println("Arrival at t=${time()}")
 *         hold(5.0)
 *         println("Departure at t=${time()}")
 *     }
 * }
 *
 * val sim = simulation {
 *     repeat(3) { i ->
 *         Process.activate(Customer(), delay = i * 10.0)
 *     }
 * }
 * sim.run(endTime = 100.0)
 * ```
 *
 * ### Example: Controlled Execution
 * ```kotlin
 * class Monitor : Process() {
 *     override fun actions() {
 *         while (time() < 50.0) {
 *             hold(10.0)
 *             println("Checkpoint at t=${time()}")
 *         }
 *         simulation().stop()  // Stop simulation early
 *     }
 * }
 *
 * runSimulation(endTime = 100.0) {
 *     Process.activate(Monitor())
 * }
 * // Will stop at t=50.0, not 100.0
 * ```
 *
 * @see simulation
 * @see runSimulation
 * @see Process
 * @since 0.1.0
 */
expect class Simulation() {
    /**
     * Executes the simulation until the specified end time or [stop] is called.
     *
     * The simulation processes events in chronological order, activating processes
     * as their scheduled times arrive. If a process calls [stop], execution halts
     * immediately even if [endTime] has not been reached.
     *
     * ### Execution Flow
     * 1. Initialize simulation clock to 0.0
     * 2. Process events from the event queue in time order
     * 3. For each event, activate the corresponding process
     * 4. Continue until time reaches [endTime] or [stop] is called
     * 5. Return when no more events or simulation stopped
     *
     * ### Time Progression
     * The simulation clock advances in discrete jumps from one event time to the next.
     * For continuous processes, state variables are integrated smoothly during [Process.hold],
     * with the simulation clock jumping discretely to the end of the hold duration.
     * Intermediate integration steps (t + Δt, t + Δt/2) are internal to the Runge-Kutta
     * algorithm and do not affect the simulation clock.
     *
     * @param endTime the simulation time at which to stop execution
     * @throws IllegalArgumentException if endTime is negative
     */
    fun run(endTime: Double)

    /**
     * Returns the current simulation clock time.
     *
     * The simulation clock starts at 0.0 and advances as events are processed.
     * This method returns the current time from the simulation's perspective.
     *
     * Processes should typically use [Process.time] instead, which is more
     * convenient.
     *
     * @return the current simulation time (non-negative)
     * @see Process.time
     */
    fun time(): Double

    /**
     * Stops the simulation immediately.
     *
     * This halts simulation execution, even if the end time specified in [run]
     * has not been reached. Any processes currently in [Process.hold] or
     * [Process.passivate] will not resume.
     *
     * ### Thread Safety
     * This method can be called from process threads (on JVM). The implementation
     * uses @Volatile to ensure visibility of the stop request across threads.
     * However, there may be a delay before the simulation actually stops if a
     * process is already executing in [Process.hold].
     *
     * ### State Consistency
     * Variables may be left in intermediate states if stopped mid-integration
     * during a continuous [Process.hold]. The simulation cannot be restarted
     * after being stopped - create a new [Simulation] instance for subsequent runs.
     *
     * ### Example: Conditional Termination
     * ```kotlin
     * class QualityCheck : Process() {
     *     override fun actions() {
     *         hold(10.0)
     *         if (defectCount > threshold) {
     *             println("Quality failure at t=${time()}")
     *             simulation().stop()
     *         }
     *     }
     * }
     * ```
     */
    fun stop()

    companion object {
        /**
         * Creates and initializes a new simulation.
         *
         * The [setup] lambda receives the new simulation as its receiver,
         * allowing you to configure the simulation before running it.
         *
         * ### Note
         * Prefer using [simulation] or [runSimulation] DSL functions instead
         * of calling this directly.
         *
         * @param setup configuration block executed with the new simulation as receiver
         * @return the newly created and configured simulation
         * @see simulation
         * @see runSimulation
         */
        fun create(setup: Simulation.() -> Unit): Simulation
    }
}
