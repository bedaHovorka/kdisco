package cz.hovorka.kdisco

/**
 * Base class for continuous simulation processes with differential equations.
 *
 * `Continuous` extends [Process] to support combined discrete-event and continuous
 * simulation. In addition to the discrete [actions] method, continuous processes
 * define a [derivatives] method that specifies differential equations governing
 * state variables ([Variable]).
 *
 * ### How It Works
 * When a continuous process calls [hold], the simulation integrates the differential
 * equations defined in [derivatives] over the hold duration. The [derivatives] method
 * is called repeatedly during integration to compute the rate of change for each
 * [Variable].
 *
 * On JVM, this uses Runge-Kutta numerical integration from the jDisco library to
 * solve the differential equations with high accuracy.
 *
 * ### Typical Usage Pattern
 * - [actions]: Controls the discrete phases of the simulation (e.g., mode changes)
 * - [derivatives]: Defines continuous behavior within each phase (differential equations)
 * - [Variable]: Stores state that evolves continuously over time
 *
 * ### Example: Exponential Decay
 * ```kotlin
 * class Decay : Continuous() {
 *     val x = Variable(100.0)  // Initial value
 *
 *     override fun derivatives() {
 *         x.rate = -0.1 * x.state  // dx/dt = -0.1 * x
 *     }
 *
 *     override fun actions() {
 *         println("t=0: x=${x.state}")
 *         hold(10.0)  // Integrate for 10 time units
 *         println("t=10: x=${x.state}")  // x ≈ 36.79
 *     }
 * }
 * ```
 *
 * ### Example: Hybrid Discrete-Continuous
 * ```kotlin
 * class BouncingBall : Continuous() {
 *     val height = Variable(10.0)
 *     val velocity = Variable(0.0)
 *     val gravity = -9.81
 *
 *     override fun derivatives() {
 *         height.rate = velocity.state
 *         velocity.rate = gravity
 *     }
 *
 *     override fun actions() {
 *         while (time() < 100.0) {
 *             hold(calculateTimeToGround())
 *             // Discrete event: bounce
 *             velocity.state = -0.9 * velocity.state
 *         }
 *     }
 *
 *     private fun calculateTimeToGround(): Double {
 *         // Solve quadratic equation for when height = 0
 *         val a = gravity / 2.0
 *         val b = velocity.state
 *         val c = height.state
 *         return (-b + sqrt(b * b - 4 * a * c)) / (2 * a)
 *     }
 * }
 * ```
 *
 * @see Process
 * @see Variable
 * @see hold
 * @since 0.1.0
 */
expect abstract class Continuous() : Process {
    /**
     * Default no-op actions for pure-continuous classes (e.g. SimpleIntegration).
     *
     * Pure-continuous processes (those with only [derivatives], no discrete lifecycle)
     * are started/stopped externally via [start]/[stop] and do not need to override [actions].
     */
    protected override fun actions()

    /**
     * Activates differential-equation integration for this continuous process.
     *
     * Registers this [Continuous] with the jDisco monitor so that [derivatives] is called
     * at each integration step during any [hold] executed by any active process.
     * Safe to call when already active (guarded internally).
     *
     * @return this instance (for chaining)
     */
    open override fun start(): Continuous

    /**
     * Deactivates differential-equation integration.
     *
     * Unregisters this [Continuous] from the monitor. Safe to call when already inactive.
     */
    override fun stop()

    /**
     * Whether this continuous process is currently registered with the integration monitor.
     */
    val isActive: Boolean

    /**
     * Defines the differential equations for continuous state variables.
     *
     * This method is called repeatedly during numerical integration when the
     * process calls [hold]. For each [Variable] that should evolve continuously,
     * set its [Variable.rate] to express dx/dt as a function of the current state.
     *
     * ### Integration Mechanics
     * **IMPORTANT**: The [derivatives] method is invoked **50-500+ times per integration step**
     * as part of the 4th-order Runge-Kutta (RK4) algorithm. Each invocation should compute
     * rates based on the current [Variable.state] values, which may be at intermediate points
     * (k1, k2, k3, k4 stages) during the integration step.
     *
     * **Performance**: A `hold(10.0)` call with default step size calls [derivatives]
     * approximately 40-400 times depending on the integrator configuration.
     *
     * ### Important Notes
     * - **Only set [Variable.rate] values** in [derivatives]
     * - **Do NOT modify [Variable.state]** during [derivatives] or during a [hold] call
     * - **You CAN modify [Variable.state]** in [actions] for discrete state changes
     * - **Rates must be deterministic and pure**: Do not use randomness, I/O, or side
     *   effects that depend on the number of calls
     * - This method should be fast, as it's called many times during integration
     *
     * ### Numerical Integration Details
     * kDisco uses 4th-order Runge-Kutta (RK4) integration via jDisco:
     * - Each [hold] call performs RK4 integration over the specified duration
     * - [derivatives] is called 4 times per integration step (k1, k2, k3, k4)
     * - Step size affects accuracy: smaller steps = more accurate but slower
     * - For stiff systems or rapidly changing rates, reduce hold() duration
     * - Integration may diverge if rates become too large (check for NaN/Infinity)
     *
     * ### Event Detection in Hybrid Simulations
     * To detect zero-crossings or discrete events during continuous evolution:
     *
     * 1. Calculate the event time externally (before calling hold)
     * 2. Hold until that specific time
     * 3. Check the event condition and make discrete changes in actions()
     *
     * Example (bouncing ball):
     * ```kotlin
     * fun timeToGround(): Double {
     *     val v = velocity.state
     *     val h = position.state
     *     val g = gravity
     *     // Solve: h + v*t + 0.5*g*t^2 = 0
     *     return (-v - sqrt(v*v + 2*g*h)) / g
     * }
     *
     * override fun actions() {
     *     while (time() < endTime) {
     *         val dt = timeToGround()
     *         hold(dt)
     *         // Discrete event: bounce
     *         velocity.state = -0.9 * velocity.state
     *     }
     * }
     * ```
     *
     * ### Example
     * ```kotlin
     * class PredatorPrey : Continuous() {
     *     val prey = Variable(100.0)
     *     val predators = Variable(10.0)
     *
     *     override fun derivatives() {
     *         // Lotka-Volterra equations
     *         prey.rate = 0.1 * prey.state - 0.002 * prey.state * predators.state
     *         predators.rate = 0.0001 * prey.state * predators.state - 0.05 * predators.state
     *     }
     *
     *     override fun actions() {
     *         hold(100.0)  // Integrate for 100 time units
     *         println("Final: prey=${prey.state}, predators=${predators.state}")
     *     }
     * }
     * ```
     */
    protected abstract fun derivatives()
}
