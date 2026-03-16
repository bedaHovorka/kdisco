package cz.hovorka.kdisco.koin

import cz.hovorka.kdisco.engine.Process
import org.koin.core.Koin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.qualifier.Qualifier

/**
 * A [Process] that participates in the simulation's Koin context.
 *
 * Subclasses can use [get] and [inject] to resolve dependencies that
 * were declared in the Koin modules passed to [koinSimulation].
 *
 * ```kotlin
 * class Customer : KoinProcess() {
 *     private val queue: ServiceQueue by inject()
 *     private val log: SimulationLog by inject()
 *
 *     override suspend fun actions() {
 *         log.record("Customer arrives at t=${time()}")
 *         queue.enqueue(this)
 *         passivate()   // wait for service
 *         log.record("Customer served at t=${time()}")
 *     }
 * }
 * ```
 */
abstract class KoinProcess : Process(), KoinComponent {

    /**
     * Koin instance captured at construction time during the simulation setup block,
     * while [activeSimulationKoin] is valid on the current coroutine context.
     */
    private val capturedKoin: Koin = activeSimulationKoin().koin

    /**
     * Returns the Koin instance from the active simulation context.
     *
     * This binds process DI lookups to the simulation-scoped Koin
     * context rather than a global one.
     */
    override fun getKoin(): Koin = capturedKoin

    /**
     * Eagerly retrieve a dependency.
     */
    inline fun <reified T : Any> get(
        qualifier: Qualifier? = null,
        noinline parameters: ParametersDefinition? = null
    ): T = getKoin().get(qualifier, parameters)

    /**
     * Lazy-inject a dependency.
     */
    inline fun <reified T : Any> inject(
        qualifier: Qualifier? = null,
        noinline parameters: ParametersDefinition? = null
    ): Lazy<T> = lazy { getKoin().get(qualifier, parameters) }
}
