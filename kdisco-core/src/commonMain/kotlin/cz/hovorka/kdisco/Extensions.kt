package cz.hovorka.kdisco

/**
 * Kotlin-idiomatic DSL extensions for the kDisco simulation API.
 *
 * This file provides extension functions that make simulation setup and process
 * management more convenient and expressive in Kotlin. These functions build on
 * the core expect/actual classes to provide:
 *
 * - Builder-style simulation creation ([simulation], [runSimulation])
 * - Concise process activation syntax ([Process.activate], [activateIn], [activateAt])
 * - Kotlin sequence-based iteration over [Head] contents ([asSequence], [asSequenceOf])
 *
 * @since 0.1.0
 */

/**
 * Creates a new simulation with the given setup configuration.
 *
 * This is a builder-style alternative to [Simulation.create] that provides
 * a cleaner syntax for simulation setup. The [setup] block receives the
 * simulation instance as its receiver (`this`).
 *
 * ### Example
 * ```kotlin
 * val sim = simulation {
 *     // 'this' is the Simulation
 *     Process.activate(Customer(), delay = 0.0)
 *     Process.activate(Customer(), delay = 5.0)
 *     Process.activate(Server(), delay = 0.0)
 * }
 * sim.run(100.0)
 * ```
 *
 * @param setup configuration block executed with the new simulation as receiver
 * @return the newly created simulation
 * @see runSimulation
 * @see Simulation.create
 */
fun simulation(setup: Simulation.() -> Unit): Simulation =
    Simulation.create(setup)

/**
 * Creates and immediately runs a simulation.
 *
 * This combines [simulation] and [Simulation.run] into a single convenient
 * function. Use this when you want to set up and execute a simulation in one call.
 *
 * ### Example
 * ```kotlin
 * runSimulation(endTime = 100.0) {
 *     repeat(10) { i ->
 *         Process.activate(Customer(), delay = i * 5.0)
 *     }
 * }
 * // Simulation completes when this returns
 * ```
 *
 * @param endTime the simulation time at which to stop (default: Double.MAX_VALUE)
 * @param setup configuration block executed with the simulation as receiver
 * @see simulation
 * @see Simulation.run
 */
fun runSimulation(endTime: Double = Double.MAX_VALUE, setup: Simulation.() -> Unit) {
    simulation(setup).run(endTime)
}

/**
 * Activates this process with an optional delay.
 *
 * This is a more Kotlin-idiomatic alternative to the static [Process.activate]
 * function, allowing you to write `process.activate(delay)` instead of
 * `Process.activate(process, delay)`.
 *
 * ### Example
 * ```kotlin
 * val customer = Customer()
 * customer.activate(delay = 10.0)  // Activate after 10 time units
 * ```
 *
 * @param delay simulation time to wait before activation (default: 0.0)
 * @receiver the process to activate
 * @see activateIn
 * @see activateAt
 */
fun Process.activate(delay: Double = 0.0) {
    Process.activate(this, delay)
}

/**
 * Activates this process after the specified delay.
 *
 * This is an alias for [activate] with more explicit naming. Use when you want
 * to emphasize that the parameter is a relative time delay.
 *
 * ### Example
 * ```kotlin
 * customer.activateIn(5.0)  // Activate 5 time units from now
 * ```
 *
 * @param delay simulation time to wait before activation
 * @receiver the process to activate
 * @see activate
 * @see activateAt
 */
fun Process.activateIn(delay: Double) {
    Process.activate(this, delay)
}

/**
 * Activates this process at the specified absolute simulation time.
 *
 * Unlike [activate] and [activateIn] which use relative delays, this function
 * schedules activation at an absolute time point.
 *
 * ### Example
 * ```kotlin
 * // Current time is 10.0
 * customer.activateAt(25.0)  // Will activate at t=25.0
 * ```
 *
 * @param time absolute simulation time for activation
 * @receiver the process to activate
 * @throws IllegalArgumentException if [time] is in the past (less than current simulation time)
 * @see activate
 * @see activateIn
 */
fun Process.activateAt(time: Double) {
    val delay = time - this.time()
    require(delay >= 0.0) {
        "Cannot schedule activation at past time $time (current time: ${this.time()})"
    }
    Process.activate(this, delay)
}

/**
 * Activates a process with an optional delay (top-level form).
 *
 * This top-level function mirrors Java's static method inheritance, allowing
 * `activate(process)` to be called without a qualifier from Process subclasses
 * after importing `cz.hovorka.kdisco.activate`.
 *
 * This is especially useful for code migrated from jDisco where Java's static
 * inheritance allowed calling `activate(process)` unqualified.
 *
 * @param process the process to activate
 * @param delay simulation time delay before activation (default: 0.0)
 */
@JvmName("activateProcess")
fun activate(process: Process, delay: Double = 0.0) = Process.activate(process, delay)

/**
 * The minimum allowable integration step-size (top-level property).
 *
 * Delegates to [Process.dtMin]. Import this to use `dtMin` unqualified in
 * Process subclasses (required after migrating from jDisco's static field inheritance).
 */
var dtMin: Double
    get() = Process.dtMin
    set(value) { Process.dtMin = value }

/**
 * The maximum allowable integration step-size (top-level property).
 *
 * Delegates to [Process.dtMax].
 */
var dtMax: Double
    get() = Process.dtMax
    set(value) { Process.dtMax = value }

/**
 * The upper bound for the absolute integration error (top-level property).
 *
 * Delegates to [Process.maxAbsError].
 */
var maxAbsError: Double
    get() = Process.maxAbsError
    set(value) { Process.maxAbsError = value }

/**
 * The upper bound for the relative integration error (top-level property).
 *
 * Delegates to [Process.maxRelError].
 */
var maxRelError: Double
    get() = Process.maxRelError
    set(value) { Process.maxRelError = value }

/**
 * Returns a Kotlin [Sequence] that iterates over all links in this [Head].
 *
 * This enables using standard Kotlin collection operations (filter, map, etc.)
 * on the links in a [Head]. The sequence traverses the doubly-linked circular
 * list from [Head.first] to [Head.last].
 *
 * ### Example
 * ```kotlin
 * val queue = Head()
 * // ... add some Customer links to queue ...
 *
 * queue.asSequence()
 *     .take(5)
 *     .forEach { println(it) }
 *
 * val count = queue.asSequence().count()
 * ```
 *
 * ### Thread Safety WARNING
 * [Head] is **NOT thread-safe**. On JVM, each process runs in its own thread. If
 * multiple processes access the same Head concurrently, you **MUST** use external
 * synchronization.
 *
 * If the Head is modified during iteration, behavior is undefined. For safe concurrent
 * access, use a synchronized block:
 *
 * ```kotlin
 * synchronized(queue) {
 *     queue.asSequence().forEach { process(it) }
 * }
 * ```
 *
 * ### Note
 * The sequence is evaluated lazily. If the [Head] is modified during iteration,
 * behavior is undefined.
 *
 * @receiver the [Head] to iterate over
 * @return a [Sequence] of [Link] objects in the list
 * @see asSequenceOf
 */
fun Head.asSequence(): Sequence<Link> = sequence {
    var current = first()
    while (current != null) {
        yield(current)
        val next = current.suc()
        if (next == first()) break
        current = next
    }
}

/**
 * Returns a type-filtered Kotlin [Sequence] over links of a specific type.
 *
 * This is a convenience function that combines [asSequence] with
 * [Sequence.filterIsInstance] to iterate only over links of type [T].
 *
 * ### Example
 * ```kotlin
 * class Customer : Link()
 * class Server : Link()
 *
 * val entities = Head()
 * Customer().into(entities)
 * Server().into(entities)
 * Customer().into(entities)
 *
 * val customers = entities.asSequenceOf<Customer>().toList()
 * println(customers.size)  // Prints: 2
 * ```
 *
 * @param T the specific [Link] subtype to filter for
 * @receiver the [Head] to iterate over
 * @return a [Sequence] of [Link] objects filtered to type [T]
 * @see asSequence
 */
inline fun <reified T : Link> Head.asSequenceOf(): Sequence<T> =
    asSequence().filterIsInstance<T>()
