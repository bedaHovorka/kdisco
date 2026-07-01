# Process Lifecycle Introspection API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add public lifecycle-query methods to `Process` so callers can distinguish active, passivated, and terminated states.

**Architecture:** Introduce an internal `ProcessState` enum and update the existing state machine inside `Process` and `Simulation.run`. Expose three boolean predicates with stable semantics.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, assertK, Gradle

## Global Constraints

- Project root: `/home/beda/work/kdisco`
- Worktree: `/home/beda/work/kdisco/.worktrees/issue-23-process-lifecycle`
- Base branch: `toVer0.6.0`; worktree branch: `issue-23-process-lifecycle`
- Use **assertK** for assertions; `kotlin.test` only for `@Test`
- All new code lives in `kdisco-core/src/commonMain` or `kdisco-core/src/commonTest`
- Run `./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest` before and after changes
- Do not push or create PRs; the coordinator will handle that

---

## File Structure Map

| File | Action | Responsibility |
|------|--------|----------------|
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt` | Modify | Add `ProcessState`, `_state`, public predicates, and state transitions |
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt` | Modify | Set `RUNNING` before resuming; set `TERMINATED` in launch `finally` |
| `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ProcessTest.kt` | Modify | Add lifecycle-state tests |

---

## Task 1: ProcessState enum and public predicates

**Files:**
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt:10-23`

**Interfaces:**
- Produces: `Process.isActive(): Boolean`
- Produces: `Process.isPassivated(): Boolean`
- Produces: `Process.isTerminated(): Boolean`
- Produces: internal `ProcessState` enum

- [ ] **Step 1: Add the state enum and field**

Insert above the `Process` class declaration:

```kotlin
internal enum class ProcessState {
    IDLE,
    RUNNING,
    SCHEDULED,
    PASSIVATED,
    TERMINATED
}
```

Inside `Process`, add:

```kotlin
internal var _state: ProcessState = ProcessState.IDLE
```

- [ ] **Step 2: Add public predicates**

Inside `Process`, after `terminated()`:

```kotlin
/**
 * Returns true if this process is scheduled to run or is currently running.
 *
 * A process becomes non-active when it [passivate]s or [terminate]s.
 */
fun isActive(): Boolean = _state == ProcessState.RUNNING || _state == ProcessState.SCHEDULED

/**
 * Returns true if this process is suspended and waiting for explicit
 * reactivation (e.g. after calling [passivate]).
 */
fun isPassivated(): Boolean = _state == ProcessState.PASSIVATED

/**
 * Returns true if this process has finished its [actions] or was [terminate]d.
 */
fun isTerminated(): Boolean = _state == ProcessState.TERMINATED
```

- [ ] **Step 3: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt
git commit -m "feat: add ProcessState and lifecycle predicates (#23)"
```

---

## Task 2: Update state transitions in Process

**Files:**
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt:39-150`

**Interfaces:**
- Consumes: `ProcessState` from Task 1

- [ ] **Step 1: Set SCHEDULED in `hold()`**

Change the body of `hold()` so the first thing inside `suspendCancellableCoroutine` is:

```kotlin
_state = ProcessState.SCHEDULED
continuation = cont
context.eventQueue.schedule(this, context.currentTime + duration)
cont.invokeOnCancellation {
    continuation = null
    _state = ProcessState.PASSIVATED
    context.eventQueue.remove(this@Process)
}
```

- [ ] **Step 2: Set PASSIVATED in `passivate()`**

Inside the `suspendCancellableCoroutine` block:

```kotlin
_state = ProcessState.PASSIVATED
continuation = cont
cont.invokeOnCancellation {
    continuation = null
}
```

- [ ] **Step 3: Set TERMINATED in `terminate()`**

At the start of `terminate()`:

```kotlin
open fun terminate() {
    _terminated = true
    _state = ProcessState.TERMINATED
    context.eventQueue.remove(this)
    throw ProcessTerminatedException()
}
```

- [ ] **Step 4: Set SCHEDULED in `activate()` and `reactivate()`**

In `Process.activate()`:

```kotlin
fun activate(process: Process, delay: Double = 0.0) {
    require(delay >= 0.0) { "Delay must be non-negative, got $delay" }
    val ctx = activeContext ?: throw DiscoException("Not inside a simulation")
    process.context = ctx
    process._state = ProcessState.SCHEDULED
    if (ctx.isRunning) {
        ctx.eventQueue.schedule(process, ctx.currentTime + delay)
    } else {
        ctx.pendingActivations.add(PendingActivation(process, delay))
    }
}
```

In `Process.reactivate()`:

```kotlin
fun reactivate(process: Process) {
    if (process._terminated) return
    process._state = ProcessState.SCHEDULED
    process.context.waitNotices.removeAll { it.process === process }
    process.context.eventQueue.remove(process)
    process.context.eventQueue.schedule(process, process.context.currentTime)
}
```

- [ ] **Step 5: Run core tests**

```bash
./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest
```

Expected: `BUILD SUCCESSFUL` with the existing 78 tests passing.

- [ ] **Step 6: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt
git commit -m "feat: update Process state transitions (#23)"
```

---

## Task 3: Update state transitions in Simulation.run

**Files:**
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt:135-157`

**Interfaces:**
- Consumes: `ProcessState` and `Process._state`

- [ ] **Step 1: Set RUNNING before resuming/launching**

Inside the event-processing block, after `context.currentProcess = process`:

```kotlin
context.currentTime = event.time
val process = event.process
context.currentProcess = process
process._state = ProcessState.RUNNING
```

- [ ] **Step 2: Set TERMINATED when actions finish**

In the `simScope.launch` block's `finally`:

```kotlin
simScope.launch {
    try {
        process.actions()
    } catch (_: ProcessTerminatedException) {
        // expected
    } finally {
        process._terminated = true
        process._state = ProcessState.TERMINATED
    }
}
```

- [ ] **Step 3: Run core tests**

```bash
./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt
git commit -m "feat: set RUNNING and TERMINATED states in scheduler (#23)"
```

---

## Task 4: Add lifecycle tests

**Files:**
- Modify: `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ProcessTest.kt`

**Interfaces:**
- Consumes: `Process.isActive()`, `Process.isPassivated()`, `Process.isTerminated()`

- [ ] **Step 1: Add active/passivated/terminated tests**

Append to `ProcessTest`:

```kotlin
@Test
fun lifecycleActiveHoldReactivateTerminate() = runTest {
    lateinit var p: Process
    val log = mutableListOf<String>()
    p = object : Process() {
        override suspend fun actions() {
            log.add("start-${isActive()}-${isPassivated()}-${isTerminated()}")
            hold(5.0)
            log.add("afterHold-${isActive()}-${isPassivated()}-${isTerminated()}")
            passivate()
        }
    }
    val reactivator = object : Process() {
        override suspend fun actions() {
            hold(10.0)
            log.add("reactivating-p-${p.isActive()}-${p.isPassivated()}-${p.isTerminated()}")
            Process.reactivate(p)
            hold(1.0)
            log.add("afterReactivate-p-${p.isActive()}-${p.isPassivated()}-${p.isTerminated()}")
        }
    }
    val terminator = object : Process() {
        override suspend fun actions() {
            hold(12.0)
            log.add("terminating-p-${p.isActive()}-${p.isPassivated()}-${p.isTerminated()}")
            p.terminate()
        }
    }
    runSimulation(endTime = 100.0) {
        Process.activate(p)
        Process.activate(reactivator)
        Process.activate(terminator)
    }
    assertThat(log).containsExactly(
        "start-true-false-false",
        "afterHold-true-false-false",
        "reactivating-p-false-true-false",
        "afterReactivate-p-true-false-false",
        "terminating-p-true-false-false"
    )
    assertThat(p.isTerminated()).isTrue()
}

@Test
fun terminatedProcessIsNotActiveOrPassivated() = runTest {
    val p = object : Process() {
        override suspend fun actions() {
            terminate()
        }
    }
    runSimulation(endTime = 10.0) {
        Process.activate(p)
    }
    assertThat(p.isTerminated()).isTrue()
    assertThat(p.isActive()).isFalse()
    assertThat(p.isPassivated()).isFalse()
}

@Test
fun passivatedProcessIsNotActive() = runTest {
    lateinit var p: Process
    p = object : Process() {
        override suspend fun actions() {
            passivate()
        }
    }
    runSimulation(endTime = 10.0) {
        Process.activate(p)
    }
    assertThat(p.isPassivated()).isTrue()
    assertThat(p.isActive()).isFalse()
    assertThat(p.isTerminated()).isFalse()
}
```

- [ ] **Step 2: Run the new tests**

```bash
./gradlew :kdisco-core:jvmTest --tests "cz.hovorka.kdisco.ProcessTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run the full JVM test suite**

```bash
./gradlew :kdisco-core:jvmTest :kdisco-koin:jvmTest
```

Expected: `BUILD SUCCESSFUL` with 80+ tests passing (the original 78 plus new tests).

- [ ] **Step 4: Commit**

```bash
git add kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ProcessTest.kt
git commit -m "test: process lifecycle state transitions (#23)"
```

---

## Self-Review

- **Spec coverage:** #23 requirements are covered by Tasks 1-4.
- **Placeholders:** No TBD/TODO; all code and commands are concrete.
- **Type consistency:** `ProcessState` is defined once and used consistently in `Process` and `Simulation`.
- **No breaking changes:** Existing `Process` API is unchanged; only new methods are added.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-06-28-issue-23-process-lifecycle.md`.**

Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks.
2. **Inline Execution** — execute tasks in this session using executing-plans.

Which approach?
