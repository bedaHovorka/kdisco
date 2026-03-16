# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**kDisco** is a Kotlin Multiplatform API for combined discrete-event and continuous simulation that provides a Kotlin-idiomatic wrapper around [jDisco](https://github.com/bedaHovorka/jdisco), a Java simulation library based on SIMULA concepts.

### Recent Updates (2026-02-08)

kDisco has undergone a comprehensive expert review and bug fix cycle, resolving **27 critical and high-priority issues**:

- ✅ **4 Critical bugs fixed**: Process lifecycle (terminate/reactivate), Variable lifecycle methods, Link memory leak
- ✅ **5 High-priority safety improvements**: Input validation for all time/duration parameters
- ✅ **18 Documentation enhancements**: Thread safety warnings, integration details, event detection patterns
- ✅ **Code compiles successfully** with comprehensive test coverage added

**Status**: Core discrete-event simulation is production-ready. Continuous simulation features are functional and validated (see [FIXES_IMPLEMENTED.md](FIXES_IMPLEMENTED.md) for full details).

**Key improvements**:
- Proper 1:1 API mapping with jDisco maintained throughout
- Thread safety documentation added (critical for JVM multi-threaded processes)
- Continuous integration workflow now properly documented with Variable.start()/stop()
- Input validation prevents silent failures with clear error messages

## Architecture

### Multiplatform Structure

```
commonMain (expect classes) → Kotlin DSL extensions
     ↓
jvmMain (actual classes) → delegates to jDisco 1.2.0
```

All `actual` implementations on JVM are thin wrappers that delegate to the corresponding `jdisco.*` class. This preserves the battle-tested jDisco simulation engine while providing Kotlin-friendly APIs.

Future targets (JS/Native/Wasm) will use pure-Kotlin implementations of the simulation engine.

### Core Simulation Classes

| kDisco class   | jDisco delegate       | Purpose |
|----------------|-----------------------|---------|
| `Link`         | `jdisco.Link`         | Linked-list membership base class |
| `Head`         | `jdisco.Head`         | Doubly-linked circular list container |
| `Process`      | `jdisco.Process`      | Discrete process with instantaneous events |
| `Continuous`   | `jdisco.Continuous`   | Continuous process with time-interval phases |
| `Variable`     | `jdisco.Variable`     | Piecewise-continuous state variable |
| `Simulation`   | `jdisco.Simulation`   | Simulation control and clock |

### Koin Integration Module (`kdisco-koin`)

The `kdisco-koin` module provides dependency injection for simulations using [Koin](https://insert-koin.io/):

- **`KoinProcess`** / **`KoinContinuous`**: Base classes with `get()` / `inject()` for DI-aware processes
- **`SimulationKoinContext`**: Bridges a Simulation with a dedicated Koin instance
- **`koinSimulation()`**: Entry point that creates simulation + isolated Koin context
- **`koinSimulationSweep()`**: Runs multiple simulations with varying parameters
- **Thread Safety (JVM)**: Uses `InheritableThreadLocal` in `PlatformKoinContext.kt` to propagate Koin context to jDisco process threads (jDisco runs each process in its own Java thread)

**Key principle**: Each simulation run gets a fresh Koin context. Singletons (queues, monitors, stats collectors) are isolated between runs and automatically released when the simulation ends.

## Project Structure

```
kdisco/
├── kdisco-core/                   # Core multiplatform simulation API
│   ├── libs/jdisco-1.2.0.jar     # JVM dependency (or use local Maven)
│   └── src/
│       ├── commonMain/            # expect classes + Kotlin DSL
│       ├── jvmMain/               # actual impls → jDisco delegation
│       └── jvmTest/
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
./gradlew :kdisco-koin:test
```

### Dependencies

The `jdisco-1.2.0.jar` must be available:
- **Option 1**: Place JAR in `kdisco-core/libs/`
- **Option 2**: Publish jDisco to local Maven and update `kdisco-core/build.gradle.kts`

## Development Notes

### expect/actual Pattern

All core simulation classes use Kotlin Multiplatform's `expect`/`actual` mechanism:
- `commonMain` declares `expect` classes with the public API
- `jvmMain` provides `actual` implementations that delegate to jDisco
- Extensions and DSL helpers live in `commonMain` (platform-agnostic)

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
