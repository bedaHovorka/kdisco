package cz.hovorka.kdisco

/**
 * Represents a piecewise-continuous state variable in a continuous simulation.
 *
 * `Variable` is used in [Continuous] processes to track state that evolves
 * continuously over time according to differential equations. The variable
 * maintains both its current [state] (value) and its [rate] of change (derivative).
 *
 * ### Integration Flow
 * 1. In [Continuous.derivatives], set [rate] to define dx/dt
 * 2. During [Process.hold], the [Continuous.derivatives] method is called **50-500+ times**
 *    as part of the integration algorithm, which updates [state] based on [rate]
 * 3. In [Process.actions], read [state] to access the integrated value
 *
 * ### Discrete Changes
 * While [state] evolves continuously during [Process.hold] calls, it can also
 * change discretely in [Process.actions]. This enables hybrid discrete-continuous
 * simulation (e.g., a bouncing ball where velocity reverses instantaneously on impact).
 *
 * ### Example: Exponential Decay
 * ```kotlin
 * class RadioactiveDecay : Continuous() {
 *     val atoms = Variable(1000.0)  // Initial count
 *     val lambda = 0.1  // Decay constant
 *
 *     override fun derivatives() {
 *         atoms.rate = -lambda * atoms.state  // dN/dt = -λN
 *     }
 *
 *     override fun actions() {
 *         println("t=0: ${atoms.state} atoms")
 *         hold(10.0)
 *         println("t=10: ${atoms.state} atoms")  // ≈ 368 atoms
 *     }
 * }
 * ```
 *
 * ### Example: Position and Velocity
 * ```kotlin
 * class FallingObject : Continuous() {
 *     val position = Variable(100.0)  // meters
 *     val velocity = Variable(0.0)    // m/s
 *     val gravity = -9.81             // m/s²
 *
 *     override fun derivatives() {
 *         position.rate = velocity.state  // dx/dt = v
 *         velocity.rate = gravity         // dv/dt = g
 *     }
 *
 *     override fun actions() {
 *         while (position.state > 0.0) {
 *             hold(0.1)  // Integrate for 0.1 seconds
 *             println("t=${time()}: h=${position.state}m, v=${velocity.state}m/s")
 *         }
 *         // Discrete event: hit ground
 *         position.state = 0.0
 *         velocity.state = 0.0
 *     }
 * }
 * ```
 *
 * @param initialValue the starting value for [state]
 * @see Continuous
 * @see Continuous.derivatives
 * @since 0.1.0
 */
expect class Variable(initialValue: Double) {
    /**
     * The current value of this variable.
     *
     * During [Process.hold] in a [Continuous] process, this is automatically
     * updated by the numerical integration algorithm based on [rate].
     *
     * Can be read at any time and can be modified discretely in [Process.actions]
     * to model instantaneous state changes (jumps).
     */
    var state: Double

    /**
     * The current rate of change (derivative) of this variable.
     *
     * Set this in [Continuous.derivatives] to define dx/dt. The integration
     * algorithm uses this value to compute how [state] changes during [Process.hold].
     *
     * ### Important
     * - Set [rate] in [Continuous.derivatives], not in [Process.actions]
     * - Express [rate] as a function of current [state] values
     * - Units: [state units] per [time unit]
     * - **CRITICAL**: Ensure dimensional consistency between [state], [rate], and
     *   [Process.hold] duration. For example:
     *   - If [state] is in meters and time in seconds, [rate] must be m/s
     *   - Mixing units (e.g., meters + milliseconds) causes silently incorrect results
     *
     * ### Example
     * ```kotlin
     * val temperature = Variable(100.0)  // Celsius
     * val ambientTemp = 20.0
     * val coolingRate = 0.05  // 1/seconds
     *
     * override fun derivatives() {
     *     // Newton's law of cooling: dT/dt = -k(T - T_ambient)
     *     temperature.rate = -coolingRate * (temperature.state - ambientTemp)
     * }
     * ```
     */
    var rate: Double

    /**
     * Starts continuous integration for this variable.
     *
     * Must be called before the variable can be used in continuous processes.
     * Typically called at the beginning of [Continuous.actions] before any [Process.hold] calls.
     *
     * @return this Variable for method chaining
     */
    fun start(): Variable

    /**
     * Stops continuous integration for this variable.
     *
     * Should be called when the variable is no longer needed in continuous integration,
     * typically at the end of [Continuous.actions].
     */
    fun stop()

    /**
     * Checks if this variable is currently active in continuous integration.
     *
     * @return true if variable is active, false otherwise
     */
    fun isActive(): Boolean
}
