# kDisco KDoc Expert Review Report

**Date:** 2026-02-08
**Reviewers:** Two independent simulation domain experts
**Scope:** Complete review of KDoc documentation and JVM implementation in kdisco-core-api

## Executive Summary

Two simulation experts conducted parallel reviews of the kDisco core API:
- **Agent 1 (a7e0bd3):** KDoc-focused review examining documentation accuracy
- **Agent 2 (a2aac6b):** Comprehensive implementation review analyzing both KDoc and actual code

Both reviews identified **critical bugs** and **severe documentation inaccuracies** that must be addressed before release.

## CRITICAL ISSUES (Must Fix Immediately)

### 1. 🔴 CRITICAL BUG: Process.terminate() Calls passivate() Instead

**File:** `ProcessActual.kt:28`

```kotlin
actual fun terminate() {
    jDisco.Process.passivate()  // WRONG - should be terminate!
}
```

**Impact:**
- Processes don't actually terminate when `terminate()` is called
- They remain in a passivated state and can be accidentally reactivated
- Contradicts the KDoc promise: "Terminates this process immediately and permanently"
- Resource cleanup never occurs
- Simulation clock may wait unnecessarily for "terminated" processes

**KDoc Affected:** `Process.kt:118-123`

**Fix Required:** Change to call `jDisco.Process.terminate()` (verify actual jDisco API method name)

---

### 2. 🔴 CRITICAL: Continuous Integration Not Implemented

**File:** `JDiscoIntegrationTest.kt:52`

**Evidence:**
```kotlin
// Note: Continuous integration requires Variable.start() - not yet implemented
assertThat(decay.y.state).isEqualTo(100.0)  // Should have decayed to ~0.67!
```

**Impact:**
- The test explicitly admits continuous integration doesn't work
- Variables don't evolve during `hold()` in Continuous processes
- Users writing Continuous simulations will get silently incorrect results
- KDoc presents the feature as fully functional

**KDoc Affected:** `Continuous.kt:86-94`, `Variable.kt:10-13`

**Fix Required:**
1. Implement proper Variable initialization (likely `Variable.start()` call in jDisco)
2. Ensure Runge-Kutta integration actually occurs during Continuous.hold()
3. Add disclaimer to KDoc if not fixed before release

---

### 3. 🔴 CRITICAL: WeakHashMap Registry Can Lose Link Wrappers

**File:** `LinkActual.kt:43`

```kotlin
private val linkRegistry = WeakHashMap<jDisco.Linkage, Link>()
```

**Problem:**
- Kotlin Link wrappers can be garbage collected while underlying jDisco.Linkage still exists
- Causes `suc()` and `pred()` to return null unexpectedly
- Traversal patterns silently fail in real simulations

**Breaking Scenario:**
```kotlin
queue.asSequence().map { it.suc() }  // Breaks if links not strongly referenced
```

**Why Tests Pass:** Tests keep explicit references like `val link1 = Link()`, preventing GC

**Fix Options:**
1. Use `HashMap` instead and manually clean up in `out()`
2. Keep strong references via simulation context
3. Document the requirement to maintain references

---

### 4. 🔴 CRITICAL: Simulation.run() Architecture Incomplete

**File:** `SimulationActual.kt:7-22`

**Problem:**
- Creates a dummy mainProcess that just holds until endTime
- No mechanism to wait for all other processes to complete
- Unclear when control actually returns to caller
- Should probably delegate to `jDisco.Simulation.run(endTime)` if it exists

**Impact:** Simulation termination semantics are unclear and potentially incorrect

---

## HIGH SEVERITY ISSUES

### 5. 🟠 Process.reactivate() May Use Wrong Method

**File:** `ProcessActual.kt:44-46`

```kotlin
actual fun reactivate(process: Process) {
    jDisco.Process.activate(process.jDiscoProcessDelegate)
}
```

**Problem:**
- Calls `activate()` instead of a dedicated `reactivate()` method
- May not properly restore execution context from passivated state
- Tests pass but semantics may be subtly wrong in complex scenarios

**Action:** Verify if jDisco has `Process.reactivate()` and use it

---

### 6. 🟠 Missing Input Validation Throughout

**Locations:** Multiple files (ProcessActual, SimulationActual, Extensions)

**Missing Checks:**
- `Process.activate(delay)` - no check for negative delays (KDoc says "must be non-negative")
- `Simulation.run(endTime)` - no check for negative endTime
- `hold(duration)` - no check for negative duration
- `Variable.rate = NaN/Infinity` - no validation

**Impact:** Invalid inputs cause confusing jDisco errors instead of clear exceptions

**Fix:**
```kotlin
actual fun hold(duration: Double) {
    require(duration >= 0.0) { "Duration must be non-negative, got $duration" }
    jDisco.Process.hold(duration)
}
```

---

### 7. 🟠 Thread Model Documentation Incomplete

**File:** `Process.kt:18-21`

**Current KDoc:**
> "On JVM, each process runs in its own Java thread managed by the jDisco simulation engine. Processes are scheduled cooperatively..."

**Missing Critical Info:**
- `hold()`, `passivate()`, `terminate()` are **static context-sensitive** calls operating on the currently executing process
- Thread-local simulation context mechanism not explained
- No warning about synchronization requirements for shared state (Head/Link are not thread-safe)
- jDisco's thread pool behavior not documented

**Impact:** Users won't understand:
- When synchronization is needed between processes
- How the implicit process context works
- That accessing shared Heads from multiple processes requires locks

---

### 8. 🟠 Thread Safety: @Volatile Flag Insufficient

**File:** `SimulationActual.kt:4-5`

```kotlin
@Volatile
private var stopRequested = false
```

**Problem:**
- `@Volatile` provides visibility but not atomicity
- Race condition between checking flag and calling `hold()`
- Process might call `hold()` just before `stop()` sets the flag

**Impact:** `stop()` may not actually stop immediately as documented

---

### 9. 🟠 Silent Failure in activateAt()

**File:** `Extensions.kt:129-134`

```kotlin
fun Process.activateAt(time: Double) {
    val delay = time - this.time()
    if (delay >= 0.0) {
        Process.activate(this, delay)
    }
    // If delay < 0, silently does nothing!
}
```

**Problem:**
- If target time is in the past, silently does nothing
- No exception, no warning, no return value
- User code silently fails to schedule processes

**Fix:** Either throw exception or activate immediately

---

## MEDIUM SEVERITY ISSUES (Documentation Quality)

### 10. 🟡 derivatives() Calling Frequency Not Emphasized

**File:** `Continuous.kt:86-89`

**Current:** "invoked multiple times per integration step"

**Should Clarify:**
- Called 50-500+ times per `hold()` for typical 4th-order Runge-Kutta
- Must be deterministic and pure (no randomness)
- Variable.state reflects different RK stages (k1, k2, k3, k4)
- What happens if integration diverges (NaN/Infinity)

---

### 11. 🟡 Variable.state Modification Warning Too Strict

**File:** `Continuous.kt:92`

**Current:** "do not modify [Variable.state] directly"

**Problem:**
- Too strict - users MUST modify state in `actions()` for hybrid systems
- Bouncing ball example does exactly this
- Should say: "Do not modify Variable.state **during derivatives()** or **during a hold() call**"

---

### 12. 🟡 Event Detection Pattern Not Documented

**Gap:** No guidance on zero-crossing detection for hybrid systems

**Bouncing Ball Example Issue:**
```kotlin
while (position.state > 0.0) {
    hold(0.1)  // Fixed step - may miss ground crossing!
}
velocity.state = -0.9 * velocity.state  // Discrete bounce
```

**Missing:**
- How to detect events during continuous evolution
- Need to calculate exact crossing time externally
- Can't detect events inside `derivatives()`

**Correct Pattern:**
```kotlin
while (time() < endTime) {
    hold(timeToGround())  // Calculate landing time externally
    if (position.state <= 0.0) {
        velocity.state = -0.9 * velocity.state  // Discrete change after hold
    }
}
```

---

### 13. 🟡 activate(delay=0.0) Scheduling Semantics Vague

**File:** `Process.kt:139-142`

**Current:** "will not execute until the current process yields control"

**Should Use SIMULA Terminology:**
- Process is scheduled at **same time** but **after** current process in event queue
- Execution order for activation chain: A→B→C (not concurrent)
- Critical for understanding discrete-event simulation semantics

---

### 14. 🟡 reactivate() Timing Misleading

**File:** `Process.kt:151-159`

**Current:** "will resume execution **immediately** after the statement where it called passivate"

**Problem:**
- "Immediately" is misleading
- Process is **scheduled** for execution, not synchronously resumed
- Will resume when scheduler processes the reactivation event

**Better:** "will resume execution as the next event after the scheduler processes this reactivation"

---

### 15. 🟡 Circular List Traversal Examples Have Bugs

**File:** `Link.kt:147-156`

**Problem 1:** Doesn't handle empty list
```kotlin
var current = queue.first()  // Could be null
while (current != null) {    // Never breaks in circular list!
    println(current)
    val next = current.suc()
    if (next == queue.first()) break  // Must have this!
    current = next
}
```

**Problem 2:** Backward traversal logic inverted (Line 176)

**Better Pattern:**
```kotlin
val first = queue.first() ?: return
var current = first
do {
    println(current)
    current = current.suc()
} while (current !== first)
```

---

### 16. 🟡 Simulation.stop() Side Effects Not Documented

**File:** `Simulation.kt:121-122`

**Missing:**
- What happens to passivated processes?
- Are scheduled events discarded?
- Is `stop()` thread-safe from process threads?
- Can multiple processes call `stop()` simultaneously?
- What state are Variables left in if stopped mid-hold()?

---

### 17. 🟡 Integration Method Details Missing

**File:** `Variable.kt:79-102`

**Undocumented:**
- Which Runge-Kutta variant? (RK4, RK45, adaptive?)
- Integration step size control
- Stability requirements (stiff systems?)
- Discontinuity handling
- What happens if you set `rate` in `actions()` instead of `derivatives()`?

---

### 18. 🟡 Variable Units and Dimensional Analysis

**File:** `Variable.kt:88`

**Current:** "Units: [state units] per [time unit]"

**Missing:**
- Example: if state is meters and time is seconds, rate must be m/s
- Warning about unit consistency between state, rate, and hold() duration
- Mixing units causes silent incorrect results

---

### 19. 🟡 Simulation Time Advancement Wording Misleading

**File:** `Simulation.kt:93-96`

**Current:** "time advances smoothly during [Process.hold]"

**Problem:**
- Time doesn't actually advance smoothly
- The **numerical solution** is computed smoothly
- Simulation clock still jumps discretely to end of hold()
- Intermediate time points (t + Δt, t + Δt/2) are internal to integrator

**Better:** "state variables are integrated smoothly during hold(), with the simulation clock jumping to the end of the hold duration"

---

### 20. 🟡 Link Registry Memory Leak

**File:** `LinkActual.kt` (out() method)

**Missing:**
```kotlin
actual fun out() {
    jDiscoDelegate.out()
    // Missing: linkRegistry.remove(jDiscoDelegate)
}
```

**Impact:** WeakHashMap can grow unbounded in long-running simulations with many Link creations/deletions

---

### 21. 🟡 Misleading "Battle-Tested" Claims

**Multiple Files:** Process.kt:25-26, Simulation.kt:31-35, Head.kt:11-12

**Current:** "battle-tested in production simulations since 2007"

**Problem:**
- **jDisco** is battle-tested (true)
- **kDisco wrapper** is new and has critical bugs (terminate(), continuous integration)
- The expect/actual layer adds indirection and potential issues

**Fix:** Qualify claims: "kDisco wraps the battle-tested jDisco library (used since 2007)"

---

### 22. 🟡 Head Thread Safety Warning Not Emphasized

**File:** `Head.kt:14-16`

**Current:** Documented once in Head class

**Problem:**
- Each jDisco process runs in its own thread
- If two processes modify same Head concurrently, synchronization required
- Warning not repeated in extension functions where users will see it

**Fix:** Add warning to asSequence() and other traversal utilities

---

### 23. 🟡 Missing Process Lifecycle State Query API

**Gap:** No way to inspect process state

**Users can't check:**
- Is this process currently scheduled?
- Is it passivated waiting for reactivation?
- Is it terminated?

**Makes debugging difficult and prevents safeguards**

**Desired API:**
```kotlin
fun Process.state(): ProcessState  // (active, passivated, terminated)
```

---

### 24. 🟡 No Warning Against Process Self-Scheduling

**Dangerous Pattern:**
```kotlin
class Dangerous : Process() {
    override fun actions() {
        Process.activate(this, 0.0)  // Self-activate - infinite loop?
        hold(1.0)
    }
}
```

**No documentation warns against this**

---

### 25. 🟡 README Example Uses Wrong API

**File:** `README.md:83`

**Shows:**
```kotlin
override fun equations() {
    x.derivative = -k * x.value
}
```

**Actual API:**
```kotlin
override fun derivatives() {
    x.rate = -k * x.state
}
```

**Impact:** Confusing for new users

---

### 26. 🟡 Typo in Continuous.kt Example

**File:** `Continuous.kt:98`

```kotlin
class Predator Prey : Continuous() {  // Space in class name - won't compile
```

**Should be:** `class PredatorPrey`

---

## TEST COVERAGE GAPS

### 27. No Tests for Error Conditions

**Missing:**
- Negative delays/durations
- Reactivating already-active processes
- Activating already-active processes
- Garbage collection of Links
- Multiple concurrent simulations
- What happens when stop() is called
- Self-scheduling processes
- Past-time activateAt() calls

---

## SUMMARY TABLE

| Priority | Count | Category |
|----------|-------|----------|
| 🔴 CRITICAL | 4 | Broken implementation contradicting KDoc |
| 🟠 HIGH | 5 | Missing validation, incomplete documentation of critical behavior |
| 🟡 MEDIUM | 18 | Documentation quality, examples, clarity |
| **TOTAL** | **27** | **Issues identified** |

---

## RECOMMENDATIONS (Priority Order)

### Immediate (Before ANY Release)

1. **Fix `terminate()` bug** - ProcessActual.kt:28 calls passivate() instead
2. **Fix or disable Continuous integration** - Variables don't evolve, test admits it's broken
3. **Fix WeakHashMap registry** - Use HashMap with manual cleanup or document reference requirements
4. **Audit jDisco API** - Verify correct method names for terminate(), reactivate(), Simulation.run()

### Before 1.0 Release

5. **Add input validation** - All duration/time parameters should validate non-negativity
6. **Fix Simulation.run() architecture** - Delegate to jDisco properly or document current approach
7. **Document thread model** - Explain static context-sensitive calls, thread-local simulation, synchronization needs
8. **Add event detection pattern** - Show correct hybrid simulation zero-crossing approach
9. **Fix README examples** - Use correct API names (derivatives, rate, state)
10. **Fix circular traversal examples** - Handle null, use do-while pattern

### Quality Improvements

11. **Clarify derivatives() semantics** - Emphasize 50-500+ calls per hold(), determinism requirement
12. **Fix Variable.state modification warning** - Only forbid during derivatives()/hold(), not actions()
13. **Document stop() guarantees** - State consistency, thread safety, process cleanup
14. **Add integration method details** - RK variant, step size, stability considerations
15. **Improve timing terminology** - Use precise SIMULA semantics for activate(delay=0.0)
16. **Fix "immediately" in reactivate()** - Process is scheduled, not synchronously resumed
17. **Qualify battle-tested claims** - Clarify jDisco vs kDisco wrapper
18. **Add dimensional analysis guidance** - Unit consistency warnings for Variable rate/state
19. **Fix thread safety emphasis** - Warn about Head/Link concurrent access in process threads
20. **Add process lifecycle state API** - Allow querying active/passivated/terminated state

### Test Suite Expansion

21. **Add error condition tests** - Negative values, invalid states, edge cases
22. **Add concurrent simulation tests** - Multiple simulations, thread safety
23. **Add garbage collection tests** - Link wrapper lifecycle
24. **Add continuous integration tests** - Once feature is actually implemented

---

## POSITIVE FINDINGS

The reviews also identified strengths:

✅ **Excellent KDoc structure** - Well-organized with clear examples
✅ **Clean expect/actual pattern** - Well-designed for multiplatform future
✅ **Type-safe Link traversal** - asSequenceOf<T>() is elegant
✅ **Kotlin-idiomatic extensions** - activate() and activateAt() provide nice DSL
✅ **Thoughtful Koin integration** - DI-aware processes are well-designed

---

## CONCLUSION

The kDisco KDoc documentation is **well-structured and comprehensive** in scope, but contains **critical bugs** in the implementation and **significant inaccuracies** in the documentation that contradict actual behavior.

**Key Issues:**
1. **terminate() doesn't actually terminate** - calls passivate() instead
2. **Continuous integration doesn't work** - test explicitly admits this
3. **Link wrappers can be garbage collected** - WeakHashMap registry is unsound
4. **Thread model poorly documented** - synchronization requirements unclear

**Before release, the critical bugs MUST be fixed and documentation must accurately reflect actual behavior.**

**Recommendation:** Address all CRITICAL and HIGH priority issues before considering this API stable.

---

## Review Metadata

- **Review Duration:** ~30 minutes per expert
- **Lines of KDoc Reviewed:** ~2000
- **Implementation Files Reviewed:** 15
- **Test Files Reviewed:** 3
- **Total Issues Found:** 27 (4 critical, 5 high, 18 medium)

**Expert Review Agents:**
- Agent a7e0bd3: KDoc-focused simulation theory expert
- Agent a2aac6b: Implementation-focused code reviewer with simulation expertise
