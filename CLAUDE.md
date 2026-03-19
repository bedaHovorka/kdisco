# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**kDisco** is a pure-Kotlin Multiplatform discrete-event and continuous simulation engine, implemented using Kotlin coroutines. It supports JVM, JS, and Native targets with no external runtime dependencies.

## Architecture

### Multiplatform Structure

```
kdisco-core (pure-Kotlin KMP engine)
    ├── commonMain  — simulation engine (Process, Continuous, Variable, Head, Link, …)
    ├── jvmMain     — JVM-specific: ThreadLocal SimulationContextHolder, java.util.Random
    ├── jsMain      — JS-specific: global-var context holder, kotlin.math
    └── nativeMain  — Native-specific: global-var context holder, kotlin.math
```

All simulation logic lives in `commonMain`. Platform-specific code is minimal — only the `SimulationContextHolder` (active simulation tracking) and `Random` (seeded random-number generator) differ per target.

### Core Simulation Classes (package `cz.hovorka.kdisco.engine`)

| Class        | Purpose |
|--------------|---------|
| `Process`    | Discrete process with suspend-based actions |
| `Continuous` | Continuous process with time-interval phases |
| `Variable`   | Piecewise-continuous state variable with ODE integration |
| `Head`       | Doubly-linked circular list container |
| `Link`       | Linked-list membership base class |
| `Simulation` | Simulation control and clock |
| `EventQueue` | Priority queue of scheduled notices |

### Koin Integration Module (`kdisco-koin`)

The `kdisco-koin` module provides dependency injection for simulations using [Koin](https://insert-koin.io/):

- **`KoinProcess`** / **`KoinContinuous`**: Base classes with `get()` / `inject()` for DI-aware processes
- **`SimulationKoinContext`**: Bridges a Simulation with a dedicated Koin instance
- **`koinSimulation()`**: Entry point that creates simulation + isolated Koin context
- **`koinSimulationSweep()`**: Runs multiple simulations with varying parameters
- **Thread Safety (JVM)**: Uses `InheritableThreadLocal` in `PlatformKoinContext.kt` to propagate Koin context to coroutine threads

**Key principle**: Each simulation run gets a fresh Koin context. Singletons (queues, monitors, stats collectors) are isolated between runs and automatically released when the simulation ends.

## Project Structure

```
kdisco/
├── kdisco-core/                   # Pure-Kotlin KMP simulation engine
│   └── src/
│       ├── commonMain/            # Simulation engine (all platforms)
│       ├── commonTest/            # Platform-independent tests + examples
│       ├── jvmMain/               # JVM-specific: ThreadLocal, java.util.Random
│       ├── jsMain/                # JS-specific: context holder, math
│       ├── nativeMain/            # Native-specific: context holder, math
│       └── nonJvmMain/            # Shared non-JVM source (JS + Native)
└── kdisco-koin/                   # Koin DI integration module
    └── src/
        ├── commonMain/            # DI-aware Process/Continuous, koinSimulation DSL
        ├── jvmMain/               # PlatformKoinContext (InheritableThreadLocal)
        └── jvmTest/               # Integration tests
```

## Common Development Commands

### Building

```bash
# Build core module
./gradlew :kdisco-core:build

# Build all modules
./gradlew build
```

### Testing

```bash
# Run all tests
./gradlew test

# Run tests for specific module
./gradlew :kdisco-core:jvmTest
./gradlew :kdisco-koin:test
```

## Development Notes

### Coroutine-based Simulation Engine

Each `Process` runs in its own coroutine. The scheduler (`SimulationContext`) uses `suspendCoroutine` / `resumeWith` to implement discrete events such as `hold`, `passivate`, and `waitUntil`. Continuous processes use the RKF45 integrator for state variable ODEs.

### Koin Context Lifecycle

When using `koinSimulation()`:
1. Koin context is initialized before simulation starts
2. `currentKoinContext` is set (platform-specific: `InheritableThreadLocal` on JVM)
3. Simulation runs with DI-aware processes accessing `activeSimulationKoin()`
4. Koin context is torn down after completion (`.close()`)

This ensures clean isolation between parameter sweep runs and prevents cross-contamination of singletons.

### Writing Tests

See `KoinSimulationTest.kt` for integration test patterns:
- Use `simulationModule {}` to define DI modules
- `KoinProcess` subclasses can use `by inject()` for dependencies
- Test isolation by verifying each run gets unique singleton instances

## Code Examples

### Basic Discrete Simulation

```kotlin
class Customer : Process() {
    override fun actions() {
        println("Arrives at t=${time()}")
        hold(5.0)
        println("Leaves at t=${time()}")
    }
}

runSimulation(endTime = 100.0) {
    repeat(3) { i ->
        Process.activate(Customer(), delay = i * 10.0)
    }
}
```

### DI-Aware Simulation

```kotlin
class Customer(private val id: Int) : KoinProcess() {
    private val queue: ServiceQueue by inject()
    private val stats: SimStats by inject()

    override fun actions() {
        stats.recordArrival(time())
        queue.enqueue(this)
        passivate()
        stats.recordDeparture(time())
    }
}

val module = simulationModule {
    single { ServiceQueue() }
    single { SimStats() }
    factory { params -> Customer(params.get()) }
}

koinSimulation(module) {
    val server: Server = get { parametersOf(3.0) }
    Process.activate(server)
    repeat(100) { i ->
        val c: Customer = get { parametersOf(i) }
        Process.activate(c, delay = i * 2.0)
    }
    simulation.run(5000.0)
}
```

### Parameter Sweeps

```kotlin
koinSimulationSweep(module, params = listOf(1.0, 2.0, 5.0)) { serviceTime ->
    val server: Server = get { parametersOf(serviceTime) }
    Process.activate(server)
    simulation.run(10_000.0)
}
// Each run gets isolated Koin context with fresh singletons
```

## Testing Conventions

- **Assertion library**: Use assertK (`assertThat(...).isEqualTo(...)`) in all test files.
  `kotlin.test` is kept only for the `@Test` annotation. Never use `kotlin.test.assert*`
  functions — use assertK equivalents instead.
  - `assertEquals(exp, act)` → `assertThat(act).isEqualTo(exp)`
  - `assertTrue(cond)` → `assertThat(cond).isTrue()`
  - `assertTrue(x >= a && x <= b)` → `assertThat(x).isBetween(a, b)`
  - `assertFalse(cond)` → `assertThat(cond).isFalse()`
  - `assertNull(val)` → `assertThat(val).isNull()`
  - `assertSame(exp, act)` → `assertThat(act).isSameInstanceAs(exp)`
