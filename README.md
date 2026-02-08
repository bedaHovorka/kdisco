# kDisco

**Kotlin Multiplatform API for combined discrete-event and continuous simulation.**

> **⚠️ WORK IN PROGRESS**: kDisco is under active development. Many things from chat (like waitUntil) was not implemented! The core discrete-event simulation API is not stable and production-ready (backed by battle-tested jDisco). Continuous simulation features are functional but undergoing validation. See [FIXES_IMPLEMENTED.md](FIXES_IMPLEMENTED.md) for recent improvements and test status.

> **⚠️ CRITICAL FINDING**: jDisco's class design (non-final classes with internal state) causes Kotlin Multiplatform modality warnings. This is currently a warning but will become a compile error in future Kotlin versions. See [Issue #16](JDISCO_OBSERVED_ISSUES.md#16-final-classes-prevent-kotlin-multiplatform-wrapping) for details. **This may require changes to jDisco's core classes for long-term Kotlin compatibility.**

kDisco provides a Kotlin-idiomatic API that mirrors the class structure of [jDisco](https://github.com/bedaHovorka/jdisco) — a Java library for combined discrete-event and continuous simulation originally written by Keld Helsgaun (Roskilde University, Denmark).

## Architecture

```
┌─────────────────────────────────────────────────┐
│                  commonMain                      │
│                                                  │
│  expect class Link / Head / Process /            │
│  Continuous / Variable / Simulation              │
│                                                  │
│  + Kotlin DSL extensions                         │
├────────────────────┬────────────────────────────-┤
│      jvmMain       │   (future targets)          │
│                    │                              │
│  actual classes    │   js / native / wasm         │
│  delegate to       │   pure-Kotlin impl           │
│  jDisco 1.2.0      │                              │
└────────────────────┴──────────────────────────────┘
```

The **JVM target** is the first (and currently only) implementation. Every `actual` class is a thin wrapper that delegates to the corresponding `jdisco.*` class, so you get the battle-tested jDisco simulation engine underneath with a Kotlin-friendly API on top.

## Core Classes

| kDisco class   | jDisco class          | Description |
|----------------|-----------------------|-------------|
| `Link`         | `jdisco.Link`         | Base class for linked-list membership |
| `Head`         | `jdisco.Head`         | Doubly-linked circular list container |
| `Process`      | `jdisco.Process`      | Discrete process with instantaneous events |
| `Continuous`   | `jdisco.Continuous`   | Continuous process with time-interval phases |
| `Variable`     | `jdisco.Variable`     | Piecewise-continuous state variable |
| `Simulation`   | `jdisco.Simulation`   | Simulation control and clock |

## Quick Start

### 1. Add jDisco dependency

Place `jdisco-1.2.0.jar` in `kdisco-core/libs/`, or publish jDisco to your local Maven repository and update the dependency in `kdisco-core/build.gradle.kts`.

### 2. Write a simulation

```kotlin
import cz.hovorka.kdisco.*

class Customer : Process() {
    override fun actions() {
        println("Customer arrives at t=${time()}")
        hold(5.0)   // service takes 5 time units
        println("Customer leaves at t=${time()}")
    }
}

fun main() {
    runSimulation(endTime = 100.0) {
        // Create and activate 3 customers with staggered arrivals
        repeat(3) { i ->
            val c = Customer()
            Process.activate(c, delay = i * 10.0)
        }
    }
}
```

### 3. Continuous simulation example

```kotlin
import cz.hovorka.kdisco.*

/** A simple exponential-decay process: dx/dt = -k*x */
class Decay(private val k: Double, x0: Double) : Continuous() {
    val x = Variable(x0)

    override fun actions() {
        hold(50.0)  // simulate for 50 time units
        println("x(${time()}) = ${x.state}")
    }

    override fun derivatives() {
        x.rate = -k * x.state
    }
}

fun main() {
    runSimulation {
        val decay = Decay(k = 0.1, x0 = 100.0)
        Process.activate(decay)
    }
}
```

## Kotlin DSL Extensions

kDisco adds idiomatic Kotlin helpers on top of the jDisco-parallel API:

```kotlin
// Top-level simulation builder
simulation {
    val p = MyProcess()
    p.activate()          // extension: Process.activate(this)
    p.activateIn(5.0)     // extension: Process.activate(this, 5.0)
    run(100.0)
}

// Head traversal as Kotlin Sequence
val queue = Head()
queue.asSequence().forEach { link -> /* ... */ }
queue.asSequenceOf<Customer>().filter { it.priority > 3 }
```

## Koin Integration (`kdisco-koin`)

The `kdisco-koin` module provides first-class [Koin](https://insert-koin.io/) dependency injection for simulations.

### Why?

Simulation models often have shared resources (queues, monitors, statistics collectors, configuration) that multiple processes depend on. Koin eliminates manual wiring and gives each simulation run an isolated DI context.

### Setup

```kotlin
// build.gradle.kts
dependencies {
    implementation("cz.hovorka.kdisco:kdisco-koin:0.1.0-SNAPSHOT")
}
```

### Define a module

```kotlin
val shopModule = simulationModule {
    single { ServiceQueue() }          // one per simulation
    single { SimulationStats() }
    factory { params -> Customer(params.get()) }  // new per injection
    factory { params -> Server(params.get<Double>()) }
}
```

### Write DI-aware processes

```kotlin
class Customer(private val id: Int) : KoinProcess() {
    private val queue: ServiceQueue by inject()
    private val stats: SimulationStats by inject()

    override fun actions() {
        stats.recordArrival(time())
        queue.enqueue(this)
        passivate()
        stats.recordDeparture(time())
    }
}
```

### Run

```kotlin
koinSimulation(shopModule) {
    val server: Server = get { parametersOf(3.0) }
    Process.activate(server)

    repeat(100) { i ->
        val c: Customer = get { parametersOf(i) }
        Process.activate(c, delay = i * 2.0)
    }

    simulation.run(5000.0)

    val stats: SimulationStats = get()
    println("Average wait: ${stats.averageWait()}")
}
```

### Parameter sweeps

```kotlin
val results = koinSimulationSweep(shopModule, params = listOf(1.0, 2.0, 5.0)) { serviceTime ->
    val server: Server = get { parametersOf(serviceTime) }
    Process.activate(server)
    // ... add customers ...
    simulation.run(10_000.0)
}
```

Each run gets a fresh Koin context — singletons are isolated, no cross-contamination between parameter values.

### Key classes

| Class / Function | Description |
|---|---|
| `KoinProcess` | `Process` subclass with `get()` / `inject()` |
| `KoinContinuous` | `Continuous` subclass with `get()` / `inject()` |
| `koinSimulation()` | Entry point — creates simulation + Koin context |
| `koinSimulationSweep()` | Run N simulations with varying parameters |
| `simulationModule {}` | Sugar for `module {}` signaling simulation use |
| `activeSimulationKoin()` | Access the current simulation's Koin from anywhere |

### Thread safety (JVM)

jDisco runs each process in its own Java thread. `kdisco-koin` uses `InheritableThreadLocal` on JVM to propagate the Koin context to process threads automatically.

## Project Structure

```
kdisco/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── README.md
└── kdisco-core/
│   ├── build.gradle.kts
│   ├── libs/
│   │   └── jdisco-1.2.0.jar
│   └── src/
│       ├── commonMain/kotlin/cz/hovorka/kdisco/
│       │   ├── Link.kt               # expect Link
│       │   ├── Head.kt               # expect Head
│       │   ├── Process.kt            # expect Process
│       │   ├── Continuous.kt         # expect Continuous
│       │   ├── Variable.kt           # expect Variable
│       │   ├── Simulation.kt         # expect Simulation
│       │   └── Extensions.kt         # DSL & Kotlin extensions
│       ├── commonTest/
│       ├── jvmMain/kotlin/cz/hovorka/kdisco/
│       │   └── Actuals.kt            # actual impls → jDisco
│       └── jvmTest/
└── kdisco-koin/
    ├── build.gradle.kts
    └── src/
        ├── commonMain/kotlin/cz/hovorka/kdisco/koin/
        │   ├── Dsl.kt                # koinSimulation {}, sweep
        │   ├── KoinProcess.kt        # DI-aware Process
        │   ├── KoinContinuous.kt     # DI-aware Continuous
        │   ├── SimulationKoinContext.kt
        │   ├── SimulationModule.kt   # simulationModule {}
        │   └── SimulationScope.kt
        ├── jvmMain/kotlin/cz/hovorka/kdisco/koin/
        │   └── PlatformKoinContext.kt # InheritableThreadLocal
        └── jvmTest/
```

## Building

```bash
./gradlew :kdisco-core:build
```

## Roadmap

- [x] Common API mirroring jDisco class structure
- [x] JVM implementation delegating to jDisco
- [x] Koin dependency injection integration (`kdisco-koin`)
- [ ] Kotlin/Native implementation (pure-Kotlin simulation engine)
- [ ] Kotlin/JS implementation
- [ ] Kotlin/Wasm implementation
- [ ] Coroutine-based process scheduling (using `kotlinx.coroutines`)
- [ ] Random-distribution utilities (`Uniform`, `Exponential`, `Normal`, etc.)

## Acknowledgements

jDisco was originally written by **Keld Helsgaun** (Roskilde University, Denmark) and is based on the simulation concepts from the SIMULA programming language. The bedaHovorka fork modernised the build to Maven/GitHub Actions.

## License

[TBD — match your project's license]
