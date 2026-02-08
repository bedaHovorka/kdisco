package cz.hovorka.kdisco

/**
 * Base class for reporting objects that gather information about simulation behaviour.
 *
 * A `Reporter` can automatically have its user-defined [actions] executed with a
 * specified frequency - either at uniformly spaced intervals, after each time step,
 * or at event times.
 *
 * ### Usage
 * ```kotlin
 * val reporter = object : Reporter() {
 *     override fun actions() {
 *         println("t=${time()}, value=${myVariable.state}")
 *     }
 * }
 * reporter.setFrequency(1.0).start()
 * ```
 *
 * **Note:** `Reporter` must not be used for any kind of state change; only for
 * passive observation of simulation state.
 *
 * @see Process.waitUntil
 * @since 0.2.0
 */
expect abstract class Reporter() : Link {
    /**
     * The actions associated with this Reporter object.
     * Override to define what is reported at each reporting interval.
     */
    protected open fun actions()

    /**
     * Activates this Reporter and begins periodic reporting.
     * @return this (for method chaining)
     */
    open fun start(): Reporter

    /**
     * Deactivates this Reporter, stopping periodic reporting.
     */
    open fun stop()

    /**
     * Sets the reporting frequency.
     * - Positive frequency: reports at uniformly spaced intervals
     * - Zero frequency: reports after each time step
     * - Negative frequency: reports at event times
     *
     * @param f the frequency value
     * @return this (for method chaining)
     */
    fun setFrequency(f: Double): Reporter

    /**
     * Returns true if this Reporter is currently active (reporting).
     */
    fun isActive(): Boolean
}
