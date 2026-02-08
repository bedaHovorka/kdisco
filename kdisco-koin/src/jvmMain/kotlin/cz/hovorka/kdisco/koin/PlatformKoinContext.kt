package cz.hovorka.kdisco.koin

/**
 * JVM-specific: uses a [ThreadLocal] to store the active [SimulationKoinContext].
 *
 * jDisco runs each Process in its own Java thread, so the simulation's
 * Koin context must be accessible from any process thread. This override
 * replaces the common `currentKoinContext` property with a thread-local
 * that is inherited by child threads.
 *
 * The context is set by [SimulationKoinContext.execute] on the main
 * simulation thread and inherited by process threads via
 * [InheritableThreadLocal].
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
