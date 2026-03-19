package cz.hovorka.kdisco.koin

import cz.hovorka.kdisco.Simulation
import org.koin.core.module.Module

/**
 * Creates and runs a simulation with a dedicated Koin DI context.
 *
 * The Koin context is initialised before the simulation starts and
 * torn down after it completes, ensuring clean isolation between runs.
 *
 * The [setup] lambda runs synchronously inside [Simulation.create] where
 * [cz.hovorka.kdisco.Process.activeContext] is set. Use it to activate
 * processes and resolve initial dependencies. Do NOT call [Simulation.run]
 * inside [setup] — pass [endTime] instead.
 *
 * **Breaking change (0.3.0):** This function is now `suspend`. Call sites must be inside
 * a coroutine scope — use `runBlocking { }` for top-level usage or `runTest { }` in tests.
 *
 * ```kotlin
 * val simModule = module {
 *     single { ServiceQueue() }
 *     factory { Customer(get()) }
 * }
 *
 * koinSimulation(simModule, endTime = 500.0) {
 *     val queue: ServiceQueue by inject()
 *     repeat(10) {
 *         val c: Customer = get()
 *         Process.activate(c, delay = it * 5.0)
 *     }
 * }
 * ```
 *
 * @param modules one or more Koin [Module]s providing simulation dependencies
 * @param endTime simulation end time (default: [Double.MAX_VALUE])
 * @param setup   simulation configuration block (non-suspend — activations only)
 * @return the completed [Simulation]
 */
suspend fun koinSimulation(
    vararg modules: Module,
    endTime: Double = Double.MAX_VALUE,
    setup: SimulationKoinContext.() -> Unit
): Simulation {
    val ctx = SimulationKoinContext(modules.toList(), setup)
    return try {
        ctx.execute(endTime)
    } finally {
        ctx.close()
    }
}

/**
 * Variant that accepts a list of modules.
 *
 * **Breaking change (0.3.0):** This function is now `suspend`. Call sites must be inside
 * a coroutine scope — use `runBlocking { }` for top-level usage or `runTest { }` in tests.
 */
suspend fun koinSimulation(
    modules: List<Module>,
    endTime: Double = Double.MAX_VALUE,
    setup: SimulationKoinContext.() -> Unit
): Simulation {
    val ctx = SimulationKoinContext(modules, setup)
    return try {
        ctx.execute(endTime)
    } finally {
        ctx.close()
    }
}

/**
 * Runs multiple simulations (e.g. parameter sweeps) each with a
 * fresh Koin context.
 *
 * **Breaking change (0.3.0):** This function is now `suspend`. Call sites must be inside
 * a coroutine scope — use `runBlocking { }` for top-level usage or `runTest { }` in tests.
 *
 * ```kotlin
 * val results = koinSimulationSweep(simModule, params = listOf(1, 5, 10), endTime = 1000.0) { rate ->
 *     val gen: Generator = get { parametersOf(rate) }
 *     Process.activate(gen)
 * }
 * ```
 */
suspend fun <P> koinSimulationSweep(
    vararg modules: Module,
    params: Iterable<P>,
    endTime: Double = Double.MAX_VALUE,
    setup: SimulationKoinContext.(P) -> Unit
): List<Simulation> = params.map { param ->
    koinSimulation(*modules, endTime = endTime) { setup(param) }
}
