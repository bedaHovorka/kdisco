package cz.hovorka.kdisco.koin

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Creates a Koin module specifically for simulation dependencies.
 *
 * This is a convenience function that wraps [module] for better semantic clarity
 * when defining simulation-specific dependencies.
 *
 * Example:
 * ```
 * val simModule = simulationModule {
 *     single { ServiceQueue() }
 *     single { SimulationStats() }
 *     factory { params -> Customer(params.get()) }
 * }
 * ```
 */
fun simulationModule(definition: Module.() -> Unit): Module = module { definition() }
