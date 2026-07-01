# Scale Benchmark Harness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a repeatable benchmark harness that runs 1-50 concurrent kDisco processes doing realistic work and reports events/second and real-time factor.

**Architecture:** Add a `ScaleBenchmark` class in commonTest that drives a simulation with a configurable number of worker processes. Each worker cycles through hold/reserve/release. Results are reported to stdout and collected for assertions.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, assertK, Gradle

## Global Constraints

- Project root: `/home/beda/work/kdisco`
- Worktree: `/home/beda/work/kdisco/.worktrees/issue-26-scale-benchmark`
- Base branch: `toVer0.6.0`; worktree branch: `issue-26-scale-benchmark`
- **Dependencies:** This plan assumes #21 (Resource reservation) and #22 (Deterministic reproducibility) are available.
- Use **assertK** for assertions; `kotlin.test` only for `@Test`
- All new code lives in `kdisco-core/src/commonMain` or `kdisco-core/src/commonTest`
- Run `./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest` before and after changes
- Do not push or create PRs; the coordinator will handle that

---

## File Structure Map

| File | Action | Responsibility |
|------|--------|----------------|
| `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ScaleBenchmark.kt` | Create | Benchmark harness and result class |

---

## Task 1: Implement ScaleBenchmark

**Files:**
- Create: `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ScaleBenchmark.kt`

**Interfaces:**
- Consumes: `Resource`, `runSimulation(seed)`, `Process.random()`
- Produces: `ScaleBenchmark.run(n)`, `BenchmarkResult`

- [ ] **Step 1: Create the benchmark file**

```kotlin
package cz.hovorka.kdisco

import assertk.assertThat
import assertk.assertions.*
import kotlinx.coroutines.test.runTest
import kotlin.system.measureTimeMillis
import kotlin.test.Test

class ScaleBenchmark {

    data class Result(
        val n: Int,
        val seed: Long,
        val events: Int,
        val wallMs: Long,
        val simTime: Double
    ) {
        val eventsPerSecond: Double = events / (wallMs / 1000.0)
        val realTimeFactor: Double = simTime / (wallMs / 1000.0)
    }

    fun run(n: Int, seed: Long = 42L, cycles: Int = 20): Result {
        var eventCount = 0
        val simEnd = 100_000.0
        val wallMs = measureTimeMillis {
            runSimulation(endTime = simEnd, seed = seed) {
                val resource = Resource(capacity = 3)
                repeat(n) { id ->
                    Process.activate(object : Process() {
                        override suspend fun actions() {
                            repeat(cycles) {
                                hold(random().uniform(0.5, 2.0))
                                resource.reserve()
                                eventCount++
                                hold(random().uniform(0.1, 0.5))
                                resource.release()
                                eventCount++
                            }
                        }
                    }, delay = id * 0.1)
                }
            }
        }
        return Result(n, seed, eventCount, wallMs, simEnd)
    }

    @Test
    fun benchmarkReportsStableResults() = runTest {
        val sizes = listOf(1, 5, 10, 20, 50)
        val results = sizes.map { run(it) }

        for (r in results) {
            println("N=${r.n} events=${r.events} wall=${r.wallMs}ms " +
                    "events/s=${r.eventsPerSecond.format()} RTF=${r.realTimeFactor.format()}")
        }

        // Sanity checks
        for (r in results) {
            assertThat(r.events).isGreaterThan(0)
            assertThat(r.eventsPerSecond).isGreaterThan(0.0)
        }

        // 50 workers should still process all events
        val r50 = results.last()
        assertThat(r50.events).isEqualTo(50 * 20 * 2)
    }

    @Test
    fun repeatedRunsAreStable() = runTest {
        val runs = List(5) { run(20) }
        val events = runs.map { it.events }.toSet()
        assertThat(events.size).isEqualTo(1)
    }

    private fun Double.format(): String = kotlin.math.round(this * 100) / 100.0
}
```

- [ ] **Step 2: Compile**

```bash
./gradlew :kdisco-core:compileTestKotlinJvm
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ScaleBenchmark.kt
git commit -m "feat: scale benchmark harness for concurrent processes (#26)"
```

---

## Task 2: Run benchmark and verify

**Files:**
- Create: `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ScaleBenchmark.kt`

**Interfaces:**
- Consumes: `ScaleBenchmark.run(n)`

- [ ] **Step 1: Run benchmark tests**

```bash
./gradlew :kdisco-core:jvmTest --tests "cz.hovorka.kdisco.ScaleBenchmark"
```

Expected: `BUILD SUCCESSFUL` and console output showing N=1,5,10,20,50 results.

- [ ] **Step 2: Run full JVM suite**

```bash
./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit if any test tuning was needed**

If no changes, no extra commit. If you tuned parameters (e.g. `cycles` or `simEnd`), commit with:

```bash
git add kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ScaleBenchmark.kt
git commit -m "test: tune scale benchmark parameters (#26)"
```

---

## Self-Review

- **Spec coverage:** #26 requirements covered: configurable N, realistic work, events/second and real-time factor reporting, N=1/5/10/20/50, stability across repeated runs, runnable via Gradle `--tests`.
- **Placeholders:** None.
- **Type consistency:** `Result` fields are used consistently for reporting and assertions.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-28-issue-26-scale-benchmark.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task.
2. **Inline Execution** — execute tasks in this session using executing-plans.

Which approach?
