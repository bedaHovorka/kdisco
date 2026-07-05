// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco.koin

/**
 * JVM-specific: uses an [InheritableThreadLocal] to store the active [SimulationKoinContext].
 *
 * The pure-Kotlin coroutine engine runs all simulation logic on a single thread
 * (cooperative scheduling), so child-thread inheritance is not required by the
 * engine itself. [InheritableThreadLocal] is used for defensive correctness in
 * case the caller launches additional threads or coroutines on a multi-threaded
 * dispatcher from within simulation code.
 *
 * Note: coroutine context elements do NOT automatically carry thread-locals across
 * dispatcher thread switches. If you launch coroutines on a multi-threaded dispatcher
 * inside a simulation, wrap with [kotlinx.coroutines.asContextElement] to propagate the
 * context correctly.
 *
 * The context is set by [SimulationKoinContext.execute] before the simulation starts
 * and cleared by [SimulationKoinContext.close], which is called by [koinSimulation] in a
 * `finally` block after the simulation completes.
 */
private val threadLocalKoinContext = InheritableThreadLocal<SimulationKoinContext?>()

/**
 * JVM accessor — install into the common-level property.
 *
 * This file is loaded on JVM, and the initializer below patches
 * the common `currentKoinContext` accessors to use [InheritableThreadLocal].
 */
internal actual var platformKoinContext: SimulationKoinContext?
    get() = threadLocalKoinContext.get()
    set(value) { threadLocalKoinContext.set(value) }
