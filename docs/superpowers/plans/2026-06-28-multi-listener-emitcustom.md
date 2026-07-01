# kdisco Multi-Listener Fan-out + Top-Level emitCustom Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade kdisco's single-slot event listener to an additive multi-listener list, and add a top-level `emitCustom(payload)` usable from any simulation-thread code (not only from `Process` subclasses).

**Architecture:** `SimulationContext.eventListener` (single `var`) becomes `eventListeners` (a `MutableList`). `Simulation.onEvent` appends instead of replaces. A new top-level `emitCustom` in `Dsl.kt` uses the existing `Process.activeContext` static to reach the list. `Process.emitCustom` is updated to iterate the list. All existing callers continue to work unchanged.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines, assertK, Gradle

## Global Constraints

- Worktree root: `/home/beda/work/kdisco/.worktrees/issue-24-observable-events`
- Branch: `issue-24-observable-events`
- Use **assertK** for assertions; `kotlin.test` only for `@Test`
- All new code lives in `kdisco-core/src/commonMain` or `kdisco-core/src/commonTest`
- Run `./gradlew :kdisco-core:jvmTest` before and after every task
- Do not push or create PRs; the coordinator handles that
- After all tasks pass: publish snapshot with `./gradlew publishToMavenLocal`

---

## File Structure Map

| File | Action | Responsibility |
|------|--------|----------------|
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationContext.kt` | Modify | Replace single-slot `eventListener` with `eventListeners: MutableList` |
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt` | Modify | `onEvent` appends; `emit` iterates |
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt` | Modify | `emitCustom` iterates list |
| `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Dsl.kt` | Modify | Add top-level `emitCustom(payload)` |
| `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ObservableEventsTest.kt` | Modify | Add multi-listener and top-level emitCustom tests |

---

## Task 1: Replace single-slot listener with MutableList in SimulationContext

**Files:**
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationContext.kt`
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt`
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt`

**Interfaces:**
- Consumes: `SimulationContext.eventListeners: MutableList<(SimulationEvent) -> Unit>`
- Produces: `Simulation.onEvent(listener)` (additive), `Simulation.emit(event)` (iterates list), `Process.emitCustom(payload)` (iterates list)

- [ ] **Step 1: Verify baseline tests pass**

```bash
cd /home/beda/work/kdisco/.worktrees/issue-24-observable-events
./gradlew :kdisco-core:jvmTest
```

Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 2: Replace eventListener field in SimulationContext.kt**

In `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationContext.kt`, replace line 18-19:

```kotlin
// BEFORE:
/** Optional event subscriber. Null when no listener is registered (zero-overhead path). */
var eventListener: ((SimulationEvent) -> Unit)? = null
```

with:

```kotlin
// AFTER:
/** Registered event listeners. Empty list = zero-overhead path (guard is isEmpty()). */
val eventListeners: MutableList<(SimulationEvent) -> Unit> = mutableListOf()
```

- [ ] **Step 3: Update Simulation.onEvent and emit in Simulation.kt**

In `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt`, replace lines 193-206:

```kotlin
// BEFORE:
/**
 * Register a listener that receives every [SimulationEvent] in simulation-time order.
 *
 * Only one listener is supported at a time; subsequent calls replace the previous listener.
 * Setting no-op when no subscriber keeps the event path overhead-free.
 */
fun onEvent(listener: (SimulationEvent) -> Unit) {
    context.eventListener = listener
}

/** Emit a [SimulationEvent] to the registered listener, if any. */
internal fun emit(event: SimulationEvent) {
    context.eventListener?.invoke(event)
}
```

with:

```kotlin
// AFTER:
/**
 * Register a listener that receives every [SimulationEvent] in simulation-time order.
 *
 * Listeners are additive — each call appends to the list. All registered listeners
 * receive every event in registration order. Zero-overhead when no listeners registered.
 */
fun onEvent(listener: (SimulationEvent) -> Unit) {
    context.eventListeners += listener
}

/** Emit a [SimulationEvent] to all registered listeners, in registration order. */
internal fun emit(event: SimulationEvent) {
    if (context.eventListeners.isEmpty()) return
    context.eventListeners.forEach { it(event) }
}
```

- [ ] **Step 4: Update Process.emitCustom in Process.kt**

In `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt`, replace the `emitCustom` method body (lines ~152-154):

```kotlin
// BEFORE:
/** Emit a custom event from within a process. */
fun emitCustom(payload: Any?) {
    context.eventListener?.invoke(SimulationEvent.Custom(context.currentTime, payload))
}
```

with:

```kotlin
// AFTER:
/** Emit a custom event from within a process. */
fun emitCustom(payload: Any?) {
    if (context.eventListeners.isEmpty()) return
    val event = SimulationEvent.Custom(context.currentTime, payload)
    context.eventListeners.forEach { it(event) }
}
```

- [ ] **Step 5: Run tests to verify nothing broke**

```bash
./gradlew :kdisco-core:jvmTest
```

Expected: BUILD SUCCESSFUL, all tests green. The existing `ObservableEventsTest` exercises the single-listener path; it should still pass since `onEvent` still works, just now appends.

- [ ] **Step 6: Commit**

```bash
cd /home/beda/work/kdisco/.worktrees/issue-24-observable-events
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/SimulationContext.kt \
        kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Simulation.kt \
        kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Process.kt
git commit -m "feat: replace single-slot eventListener with additive MutableList fan-out (#569)"
```

---

## Task 2: Add top-level emitCustom to Dsl.kt

**Files:**
- Modify: `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Dsl.kt`

**Interfaces:**
- Consumes: `Process.activeContext: SimulationContext?` (existing static, used by `Variable`)
- Produces: `fun emitCustom(payload: Any?)` — top-level function in package `cz.hovorka.kdisco`

- [ ] **Step 1: Write the failing test first**

In `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ObservableEventsTest.kt`, add at the bottom of the class:

```kotlin
@Test
fun topLevelEmitCustomDeliveredWhenCalledOutsideProcessSubclass() = runTest {
    val received = mutableListOf<Any?>()
    runSimulation(endTime = 10.0) {
        onEvent { if (it is SimulationEvent.Custom) received.add(it.payload) }
        Process.activate(object : Process() {
            override suspend fun actions() {
                // Call the top-level emitCustom (not Process.emitCustom)
                cz.hovorka.kdisco.emitCustom("from-process")
            }
        })
    }
    assertThat(received).isEqualTo(listOf("from-process"))
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :kdisco-core:jvmTest --tests "cz.hovorka.kdisco.ObservableEventsTest.topLevelEmitCustomDeliveredWhenCalledOutsideProcessSubclass"
```

Expected: FAIL — `Unresolved reference: emitCustom` (top-level function doesn't exist yet).

- [ ] **Step 3: Add top-level emitCustom to Dsl.kt**

Append to the end of `kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Dsl.kt`:

```kotlin
/**
 * Emit a custom simulation event from any code running on the simulation thread.
 *
 * Unlike [Process.emitCustom], this top-level function does not require a [Process]
 * instance — any service or helper method called from within simulation-time execution
 * can use it. Uses [Process.activeContext] to reach the event bus.
 *
 * No-op when called outside a simulation run (activeContext is null) or when no
 * listeners are registered.
 */
fun emitCustom(payload: Any?) {
    val ctx = Process.activeContext ?: return
    if (ctx.eventListeners.isEmpty()) return
    val event = SimulationEvent.Custom(ctx.currentTime, payload)
    ctx.eventListeners.forEach { it(event) }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :kdisco-core:jvmTest --tests "cz.hovorka.kdisco.ObservableEventsTest.topLevelEmitCustomDeliveredWhenCalledOutsideProcessSubclass"
```

Expected: PASS.

- [ ] **Step 5: Run full test suite**

```bash
./gradlew :kdisco-core:jvmTest
```

Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 6: Commit**

```bash
git add kdisco-core/src/commonMain/kotlin/cz/hovorka/kdisco/Dsl.kt \
        kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ObservableEventsTest.kt
git commit -m "feat: add top-level emitCustom usable outside Process subclass (#569)"
```

---

## Task 3: Add multi-listener test + publish snapshot

**Files:**
- Modify: `kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ObservableEventsTest.kt`

**Interfaces:**
- Consumes: `Simulation.onEvent(listener)` (now additive)
- Produces: verified test coverage for multi-listener fan-out

- [ ] **Step 1: Add multi-listener test**

In `ObservableEventsTest.kt`, add:

```kotlin
@Test
fun multipleListenersAllReceiveEveryEvent() = runTest {
    val listener1 = mutableListOf<Double>()
    val listener2 = mutableListOf<Double>()
    runSimulation(endTime = 10.0) {
        onEvent { listener1.add(it.time) }
        onEvent { listener2.add(it.time) }
        Process.activate(object : Process() {
            override suspend fun actions() {
                hold(2.0)
                hold(3.0)
            }
        })
    }
    // Both listeners receive identical event times
    assertThat(listener1).isEqualTo(listOf(0.0, 0.0, 2.0, 5.0))
    assertThat(listener2).isEqualTo(listener1)
}

@Test
fun secondListenerDoesNotReplaceFirst() = runTest {
    val firstCount = mutableListOf<SimulationEvent>()
    val secondCount = mutableListOf<SimulationEvent>()
    runSimulation(endTime = 5.0) {
        onEvent { firstCount.add(it) }
        onEvent { secondCount.add(it) }
        Process.activate(object : Process() {
            override suspend fun actions() { hold(1.0) }
        })
    }
    assertThat(firstCount.size).isGreaterThan(0)
    assertThat(secondCount.size).isEqualTo(firstCount.size)
}
```

- [ ] **Step 2: Run tests to verify they pass**

```bash
./gradlew :kdisco-core:jvmTest --tests "cz.hovorka.kdisco.ObservableEventsTest"
```

Expected: BUILD SUCCESSFUL, all tests in ObservableEventsTest green.

- [ ] **Step 3: Run full test suite**

```bash
./gradlew :kdisco-core:jvmTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit tests**

```bash
git add kdisco-core/src/commonTest/kotlin/cz/hovorka/kdisco/ObservableEventsTest.kt
git commit -m "test: multi-listener fan-out and top-level emitCustom coverage (#569)"
```

- [ ] **Step 5: Publish snapshot to mavenLocal for interlockSim consumption**

```bash
cd /home/beda/work/kdisco/.worktrees/issue-24-observable-events
./gradlew publishToMavenLocal
```

Expected: BUILD SUCCESSFUL. The `0.6.0-SNAPSHOT` artifact is now in `~/.m2/repository/cz/hovorka/kdisco/`. interlockSim's `settings.gradle.kts` already has `mavenLocal()` at highest priority, so it will pick up the snapshot automatically.
