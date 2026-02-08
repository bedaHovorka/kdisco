package cz.hovorka.kdisco.koin

import cz.hovorka.kdisco.Simulation
import org.koin.core.module.Module

/**
 * Creates and runs a simulation with a dedicated Koin DI context.
 *
 * The Koin context is initialised before the simulation starts and
 * torn down after it completes, ensuring clean isolation between runs.
 *
 * ```kotlin
 * val simModule = module {
 *     single { ServiceQueue() }
 *     factory { Customer(get()) }
 * }
 *
 * koinSimulation(simModule) {
 *     val queue: ServiceQueue by inject()
 *     repeat(10) {
 *         val c: Customer = get()
 *         Process.activate(c, delay = it * 5.0)
 *     }
 *     run(500.0)
 * }
 * ```
 *
 * @param modules one or more Koin [Module]s providing simulation dependencies
 * @param setup   simulation configuration block with Koin-aware receivers
 * @return the completed [Simulation]
 */
fun koinSimulation(
    vararg modules: Module,
    setup: SimulationKoinContext.() -> Unit
): Simulation {
    val ctx = SimulationKoinContext(modules.toList(), setup)
    return try {
        ctx.execute()
    } finally {
        ctx.close()
    }
}

/**
 * Variant that accepts a list of modules.
 */
fun koinSimulation(
    modules: List<Module>,
    setup: SimulationKoinContext.() -> Unit
): Simulation {
    val ctx = SimulationKoinContext(modules, setup)
    return try {
        ctx.execute()
    } finally {
        ctx.close()
    }
}

/**
 * Runs multiple simulations (e.g. parameter sweeps) each with a
 * fresh Koin context.
 *
 * ```kotlin
 * val results = koinSimulationSweep(simModule, params = listOf(1, 5, 10)) { rate ->
 *     val gen: Generator = get { parametersOf(rate) }
 *     Process.activate(gen)
 *     run(1000.0)
 * }
 * ```
 */
fun <P> koinSimulationSweep(
    vararg modules: Module,
    params: Iterable<P>,
    setup: SimulationKoinContext.(P) -> Unit
): List<Simulation> = params.map { param ->
    koinSimulation(*modules) { setup(param) }
}
