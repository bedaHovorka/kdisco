package cz.hovorka.kdisco.koin

import cz.hovorka.kdisco.engine.Continuous
import org.koin.core.Koin
import org.koin.core.component.KoinComponent
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.qualifier.Qualifier

/**
 * Base class for [Continuous] processes with Koin dependency injection support.
 *
 * Provides access to the simulation-specific Koin context for dependency resolution.
 */
abstract class KoinContinuous : Continuous(), KoinComponent {

    /**
     * Returns the Koin instance for the current simulation.
     */
    override fun getKoin(): Koin = activeSimulationKoin().koin

    /**
     * Lazily injects a dependency from the simulation Koin context.
     */
    inline fun <reified T : Any> inject(
        qualifier: Qualifier? = null,
        noinline parameters: ParametersDefinition? = null
    ) = lazy { get<T>(qualifier, parameters) }

    /**
     * Retrieves a dependency from the simulation Koin context.
     */
    inline fun <reified T : Any> get(
        qualifier: Qualifier? = null,
        noinline parameters: ParametersDefinition? = null
    ): T = getKoin().get(qualifier, parameters)
}
