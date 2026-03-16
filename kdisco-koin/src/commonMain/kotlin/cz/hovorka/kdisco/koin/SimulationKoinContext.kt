package cz.hovorka.kdisco.koin

import cz.hovorka.kdisco.engine.Simulation
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.qualifier.Qualifier
import org.koin.dsl.koinApplication

/**
 * Bridges a [Simulation] with a dedicated [Koin] context.
 *
 * Every simulation gets its own Koin application so that
 * simulation-scoped singletons (queues, monitors, stats) are
 * isolated between runs and automatically released when
 * the simulation ends.
 *
 * Usage:
 * ```kotlin
 * koinSimulation(myModule, endTime = 1000.0) {
 *     val server: Server by inject()
 *     Process.activate(server)
 * }
 * ```
 */
class SimulationKoinContext(
    modules: List<Module>,
    private val simulationSetup: SimulationKoinContext.() -> Unit
) {
    /** The Koin application for this simulation run. */
    val koinApp: KoinApplication = koinApplication {
        modules(modules)
    }

    /** Shortcut to the Koin instance. */
    val koin: Koin get() = koinApp.koin

    /** The underlying kDisco simulation. */
    lateinit var simulation: Simulation
        private set

    /**
     * Retrieve a dependency from the simulation's Koin context.
     */
    inline fun <reified T : Any> get(
        qualifier: Qualifier? = null,
        noinline parameters: ParametersDefinition? = null
    ): T = koin.get(qualifier, parameters)

    /**
     * Lazy-inject a dependency from the simulation's Koin context.
     */
    inline fun <reified T : Any> inject(
        qualifier: Qualifier? = null,
        noinline parameters: ParametersDefinition? = null
    ): Lazy<T> = lazy { koin.get(qualifier, parameters) }

    /**
     * Create, configure, run, and return the simulation.
     *
     * The setup lambda runs synchronously inside [Simulation.create] where
     * the simulation context is active, so [Process.activate] calls go to the
     * pending-activations queue. Then [Simulation.run] is called as a suspend
     * function to execute the simulation to [endTime].
     */
    suspend fun execute(endTime: Double): Simulation {
        val newSim = Simulation.create {
            this@SimulationKoinContext.simulation = this
            currentKoinContext = this@SimulationKoinContext
            simulationSetup()
        }
        newSim.run(endTime)
        return newSim
    }

    /**
     * Release all Koin resources after the simulation completes.
     */
    fun close() {
        koinApp.close()
        currentKoinContext = null
    }
}

/**
 * Platform-specific storage for the active [SimulationKoinContext].
 *
 * On JVM this uses [InheritableThreadLocal]. Other platforms use a simple
 * global variable.
 */
internal expect var platformKoinContext: SimulationKoinContext?

/**
 * Reference to the active [SimulationKoinContext].
 * Processes can use this to look up dependencies during the simulation run.
 */
@PublishedApi
internal var currentKoinContext: SimulationKoinContext?
    get() = platformKoinContext
    set(value) { platformKoinContext = value }

/**
 * Returns the [SimulationKoinContext] for the currently running simulation.
 *
 * @throws IllegalStateException if called outside a koinSimulation block.
 */
fun activeSimulationKoin(): SimulationKoinContext =
    currentKoinContext
        ?: error("No active kDisco Koin context. Are you inside a koinSimulation {} block?")
