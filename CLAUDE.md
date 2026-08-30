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

### Core Simulation Classes (package `cz.ksimulantenbande.kdisco`)

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
- **Thread Safety (JVM)**: Stores the active Koin context in an `InheritableThreadLocal` in `PlatformKoinContext.kt`, providing per-thread isolation. When using coroutines, the context is bound to the underlying thread and does *not* automatically follow dispatcher/thread switches; ensure a single-threaded dispatcher, or wrap the thread-local with `ThreadLocal.asContextElement(...)` when launching coroutines if you need it to move with coroutine contexts.

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

### Quality gates — the clean-code trinity

kDisco is gated by three tools plus Kover coverage. **All three must pass before a change
can be merged.**

| Tool | Gates | Local command |
|---|---|---|
| **ktlint** | formatting | `./gradlew ktlintCheck` (auto-fix: `./gradlew ktlintFormat`) |
| **detekt** | code smells | `./gradlew detekt` |
| **SonarCloud** | bugs, coverage, duplication | runs in CI (`./gradlew koverXmlReport sonar`) |

```bash
# Everything a PR is gated on, locally
./gradlew ktlintCheck detekt build

# Aggregate JVM coverage report -> build/reports/kover/report.xml (+ koverHtmlReport)
./gradlew koverXmlReport
```

Notes and constraints:

- **Coverage is Kover, not JaCoCo** — JaCoCo does not understand Kotlin Multiplatform.
  Kover emits a JVM-target XML report that Sonar reads via
  `sonar.coverage.jacoco.xmlReportPaths`.
- **Only the JVM target is covered.** `commonMain` is compiled into the JVM target and
  `commonTest` runs there, so this covers essentially the whole engine; the platform
  `actual`s in `jvmMain` / `jsMain` / `nativeMain` are the known blind spot.
- **Benchmarks are excluded** from coverage and from Sonar analysis
  (`TickSchedulingBenchmark`, `ScaleBenchmark`) so they neither inflate coverage nor
  count as debt.
- **Baselines.** Pre-existing findings are baselined in `<module>/config/ktlint/baseline.xml`
  and `<module>/config/detekt/baseline.xml`. New code must be clean; never regenerate a
  baseline to silence a new finding. Regenerate deliberately with
  `./gradlew ktlintGenerateBaseline detektBaseline` after a genuine ruleset change.
- **Sonar is per-module.** `sonar.sources` / `sonar.tests` / `sonar.java.binaries` /
  `sonar.java.libraries` are declared in each module's `build.gradle.kts`, never in the
  root script, so no file is indexed twice. Because these are KMP modules with no `java`
  source sets the plugin cannot auto-detect `sonar.java.libraries`; each module has a
  `sonarJavaLibraries` task that writes its own resolved JVM classpath to
  `build/sonar/java-libraries.txt` (resolving it inside the `sonar {}` block would
  re-introduce cross-project configuration resolution). Without it every Kotlin rule
  needing type resolution silently degrades.
- **Every analysis must be scoped.** CI passes `sonar.pullrequest.*` when a PR is open and
  `sonar.branch.name` otherwise, and hard-fails when neither can be determined — an
  unscoped run silently overwrites the trunk analysis. PR runs also pass
  `-Dsonar.qualitygate.wait=true`, so a red quality gate fails the job for real.
- **Encoding.** `sonar.sourceEncoding` and `JavaCompile.options.encoding` are pinned to
  UTF-8; the KDoc in this codebase is full of en-dashes and arrows, and a stale platform
  default renders them as mojibake in the analysis.

#### One-time SonarCloud project setup

`sonar.leak.period=previous_analysis` must be set through the Web API — the free-plan UI
does not offer it. The default `previous_version` window spans a whole release here, which
makes the "new issues / new coverage" gate meaningless for most PRs.

```bash
curl -u "$SONAR_TOKEN:" -X POST \
  'https://sonarcloud.io/api/settings/set' \
  -d 'component=bedaHovorka_kdisco' \
  -d 'key=sonar.leak.period' \
  -d 'value=previous_analysis'
```

CI also needs a `SONAR_TOKEN` repository secret. The SonarCloud job runs on Java 17 (the
scanner requires 17+) while the Kotlin `jvmTarget` stays at 11.

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
- `TickSchedulingBenchmark.kt` is the per-tick scheduling micro-benchmark used for fast-sim regression analysis. It runs six patterns (single driver tick loop, co-scheduled entities, per-tick spawn, per-tick wake of a passivated worker, a continuous-integration tick loop, and a deep-queue sweep isolating `removeFirst()` depth-scaling). It's excluded from default `build`/`test`/`allTests` runs (including CI) on every target because its iteration counts (up to 1M ticks) are too slow/flaky under Kotlin/JS and prone to CI timing variance. The primary target for running it is `linuxX64Test` (native, the fast-sim CLI's runtime — no JIT; this pulls in the extra `linkDebugTestLinuxX64` compile/link task automatically before the test binary runs): `./gradlew :kdisco-core:linuxX64Test -PrunBenchmarks=true --tests "cz.ksimulantenbande.kdisco.TickSchedulingBenchmark" --info`. The `runBenchmarks`-gated `testLogging.showStandardStreams` in `build.gradle.kts` already prints the per-pattern `ns/tick` lines when `-PrunBenchmarks=true` is set; `--info` is optional (useful for Gradle link progress). It can also be run on `jvmTest` the same way for a JIT-warmed comparison point.

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
