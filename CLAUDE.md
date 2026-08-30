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
  `actual`s in `jvmMain` / `jsMain` / `nativeMain` are the known blind spot. `jvmMain` is
  measured; the source sets the JVM target never compiles — `nonJvmMain`, `jsMain`,
  `nativeMain` — are in `sonar.coverage.exclusions`, because reporting them as 0 %
  covered would fail the new-code coverage gate on any change that merely touches them.
  They are still analysed for bugs, smells and duplication.
- **The gate really does enforce coverage.** The default *Sonar way* gate conditions are
  `new_coverage ≥ 80 %`, `new_duplicated_lines_density ≤ 3 %`,
  `new_security_hotspots_reviewed = 100 %` and A ratings for reliability, security and
  maintainability — all on **new code only**. `new_coverage` is skipped when a change has
  no coverable new lines, which is why a config-only PR can pass showing 0 %.
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
  `-Dsonar.qualitygate.wait=true`, so a red quality gate fails the job for real. Ref names
  reaching that scope are validated against `^[A-Za-z0-9._/+,-]+$` and the resulting
  argument string is handed to the build through `$SONAR_ARGS` rather than a `${{ }}`
  splice, because git permits shell metacharacters in a branch name.
- **One CI run per pull request.** The `push` trigger lists only `main` and `feat/**`
  (the latter because `publish-snapshot` needs a push-triggered build); every other branch
  reaches CI through `pull_request`. Listing a branch in both makes GitHub run the whole
  workflow twice for one push. The Sonar job narrows this further and runs only for a
  pull request from this repository or a push to `main` — the free plan accepts
  feature-branch analyses but retains nothing from them.
- **Encoding.** `sonar.sourceEncoding` and `JavaCompile.options.encoding` are pinned to
  UTF-8; the KDoc in this codebase is full of en-dashes and arrows, and a stale platform
  default renders them as mojibake in the analysis.

#### One-time SonarCloud project setup

CI needs a `SONAR_TOKEN` repository secret. The SonarCloud job runs on Java 17 (the
scanner requires 17+) while the Kotlin `jvmTarget` stays at 11.

kDisco uses the default **`previous_version`** new-code period. That window spans a whole
release, which is acceptable for a project this size. A tighter window would require a
New Code Definition set at project-creation time (`newCodeDefinitionType` parameter of
`api/projects/create`) — every `api/new_code_periods/*` endpoint is 404 on SonarCloud,
and the free-plan UI does not expose it. Do **not** set `sonar.leak.period` to
`previous_analysis`; that value is valid on SonarQube Server only, and the scanner rejects
it on the first *branch* analysis with:

```
Invalid new code period 'previous_analysis': version is none of the existing ones
```

**Trap — `api/projects/create` ignores its `branch` parameter.** Creating the project with
`-d branch=main` still produces a main branch named `master`. Every CI run that passes
`sonar.branch.name=main` then creates a second branch that the free plan discards, and the
trunk analysis never updates. After creating a project always rename the branch and verify:

```bash
curl -u "$SONAR_TOKEN:" -X POST 'https://sonarcloud.io/api/project_branches/rename' \
  -d project=bedaHovorka_kdisco -d name=main

curl -u "$SONAR_TOKEN:" \
  'https://sonarcloud.io/api/project_branches/list?project=bedaHovorka_kdisco'
```

**Rule — never verify a Sonar setting by reading it back.** `api/settings/set` returns 204
and `api/settings/values` echoes the stored value without validating it. The scanner
validates on the first *branch* analysis; a pull-request analysis always computes its own
diff and bypasses the new-code period check entirely, so a green PR gate is not evidence
the setting is accepted. Verify against an analysis result instead:

- Check `api/qualitygates/project_status?projectKey=bedaHovorka_kdisco` — look at
  `ignoredConditions` (true means the gate was waived) and the individual condition
  statuses.
- Read `new_coverage` / `new_uncovered_conditions` from
  `api/measures/component?component=bedaHovorka_kdisco&metricKeys=new_coverage,...`
  rather than trusting a green tick.

Note that a project's **first** analysis passes new-code conditions for free
(`"ignoredConditions": true`). A first green gate is not evidence the gate works — the
second analysis, once a baseline exists, judges it for real.

**Note on `new_coverage`**: it counts **branch conditions as well as lines**. A diff whose
lines are fully covered can still sit far below the 80% threshold if `?:` branches go
untested. The per-file `component_tree` view shows only line coverage and can read 100%
while the project total does not.

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
