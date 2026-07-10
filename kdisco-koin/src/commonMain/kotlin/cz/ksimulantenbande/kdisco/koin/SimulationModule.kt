// Public domain. Inspired by jDisco written by Keld Helsgaun and released into the public domain.
// This may be used for any purposes whatsoever without acknowledgment.
// Author of jDisco: Keld Helsgaun, Roskilde University, Denmark. Email: keld@ruc.dk
package cz.ksimulantenbande.kdisco.koin

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
