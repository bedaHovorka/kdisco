# jDisco Improvement Recommendations

**Based on**: kDisco Expert Review (2026-02-08)
**Context**: Issues discovered while creating Kotlin wrapper for jDisco 1.2.0
**Purpose**: Recommendations for jDisco library improvements

---

## Executive Summary

While wrapping jDisco 1.2.0 for kDisco, we conducted an expert review that identified several areas where the underlying jDisco library could be improved. This document provides recommendations for enhancements that would benefit both jDisco users directly and library wrappers like kDisco.

**Key Areas**:
- API clarity and documentation
- Input validation and error handling
- Thread safety documentation
- Memory management patterns
- Continuous simulation documentation

---

## 🔴 Critical Issues

### 1. Process.cancel() vs Process.passivate() Naming Confusion

**Current Issue**: The distinction between `cancel()` (terminates permanently) and `passivate()` (suspends temporarily) is not immediately clear from the names.

**Impact**: Led to incorrect usage in kDisco where `terminate()` initially called `passivate()` instead of `cancel()`.

**Recommendation**:
```java
// Consider adding clearer method names or aliases:
public void terminate() {  // Alias for cancel() with clearer name
    cancel();
}

// Or add comprehensive JavaDoc:
/**
 * Permanently terminates this process. The process cannot be reactivated.
 * Code following this call will not execute.
 *
 * This is different from passivate() which only suspends the process
 * until it is explicitly reactivated.
 *
 * @see #passivate()
 * @see #reactivate(Process)
 */
public static void cancel(Process process) {
    // existing implementation
}
```

---

### 2. Variable Lifecycle Methods Not Documented

**Current Issue**: The `Variable.start()`, `Variable.stop()`, and `Variable.isActive()` methods exist but are not prominently documented in examples or tutorials.

**Impact**: Continuous simulations fail silently if Variables are not properly started before `hold()`.

**Recommendation**:
```java
/**
 * Piecewise-continuous state variable for hybrid simulation.
 *
 * IMPORTANT: Variables must be started before use in continuous integration:
 *
 * Example:
 * <pre>
 * class Decay extends Continuous {
 *     Variable x = new Variable(100.0);
 *
 *     public void actions() {
 *         x.start();  // REQUIRED: Start integration
 *         hold(50.0);
 *         x.stop();   // OPTIONAL: Cleanup
 *     }
 *
 *     public void derivatives() {
 *         x.rate = -0.1 * x.state;
 *     }
 * }
 * </pre>
 */
public class Variable {
    /**
     * Starts continuous integration for this variable.
     * Must be called before the variable is used in hold().
     *
     * @return this Variable for method chaining
     */
    public Variable start() { ... }

    /**
     * Stops continuous integration.
     * Should be called when the variable is no longer needed.
     */
    public void stop() { ... }

    /**
     * Checks if this variable is currently active in integration.
     *
     * @return true if active, false otherwise
     */
    public boolean isActive() { ... }
}
```

---

## 🟠 High Priority Improvements

### 3. Missing Input Validation

**Current Issue**: jDisco does not validate negative time values, leading to undefined behavior.

**Impact**: Silent failures that are hard to debug.

**Recommendation**:
```java
public static void hold(double duration) {
    if (duration < 0.0) {
        throw new IllegalArgumentException(
            "Duration must be non-negative, got " + duration);
    }
    // existing implementation
}

public static void activate(Process p, double delay) {
    if (delay < 0.0) {
        throw new IllegalArgumentException(
            "Delay must be non-negative, got " + delay);
    }
    // existing implementation
}

// In Simulation class:
public void run(double endTime) {
    if (endTime < 0.0) {
        throw new IllegalArgumentException(
            "End time must be non-negative, got " + endTime);
    }
    // existing implementation
}
```

**Benefits**:
- Early error detection
- Clear error messages
- Prevents silent failures
- Helps developers catch bugs faster

---

### 4. Thread Safety Not Documented

**Current Issue**: jDisco uses one thread per process on JVM, but this is not clearly documented. No warnings about thread safety for shared data structures.

**Impact**: Users encounter race conditions when multiple processes access shared Head/Link structures without synchronization.

**Recommendation**: Add comprehensive thread safety documentation:

```java
/**
 * Base class for simulation processes.
 *
 * THREAD SAFETY:
 * On JVM, each process runs in its own Java thread. If multiple processes
 * access shared data structures (Head, Link, or user objects), explicit
 * synchronization is required.
 *
 * Head and Link are NOT thread-safe. Example of safe usage:
 * <pre>
 * Head sharedQueue = new Head();
 *
 * class Producer extends Process {
 *     public void actions() {
 *         Item item = new Item();
 *         synchronized(sharedQueue) {  // Required!
 *             item.into(sharedQueue);
 *         }
 *     }
 * }
 * </pre>
 *
 * Common pitfalls:
 * - Accessing Head without synchronization
 * - Modifying shared variables without locks
 * - Assuming sequential execution
 */
public abstract class Process extends Link { ... }
```

---

### 5. Activation Timing Semantics Unclear

**Current Issue**: The behavior of `activate(process, 0.0)` (placing process after current process in event queue at same time) follows SIMULA semantics but isn't documented.

**Impact**: Users expect immediate execution but process waits for current event to complete.

**Recommendation**:
```java
/**
 * Schedules a process for activation.
 *
 * @param process the process to activate
 * @param delay time delay before activation
 *
 * SIMULA EVENT QUEUE SEMANTICS:
 * If delay is 0.0, the process is scheduled at the current simulation time
 * but placed AFTER the current process in the event queue. It will execute
 * when the scheduler processes that event, not immediately.
 *
 * For immediate execution at the same time, the process must wait until
 * the current event completes.
 */
public static void activate(Process process, double delay) { ... }
```

---

## 🟡 Medium Priority Enhancements

### 6. Continuous Integration Details Missing

**Current Issue**: The `derivatives()` method documentation doesn't mention:
- Call frequency (50-500+ times per integration step)
- RK4 algorithm stages (k1, k2, k3, k4)
- Performance implications
- Purity requirements

**Recommendation**:
```java
/**
 * Defines differential equations for continuous state variables.
 *
 * IMPORTANT - CALL FREQUENCY:
 * This method is invoked 50-500+ times per integration step as part of the
 * 4th-order Runge-Kutta (RK4) algorithm. Each invocation computes rates at
 * different intermediate points (k1, k2, k3, k4 stages).
 *
 * PERFORMANCE IMPLICATIONS:
 * A hold(10.0) call with default step size invokes derivatives() approximately
 * 40-400 times depending on the integrator configuration.
 *
 * REQUIREMENTS:
 * - Must be fast (called many times)
 * - Must be pure (deterministic, no side effects)
 * - Only set Variable.rate, do NOT modify Variable.state
 * - Do NOT use randomness, I/O, or call-count-dependent logic
 *
 * INTEGRATION ALGORITHM:
 * kDisco uses 4th-order Runge-Kutta (RK4):
 * - Each hold() performs RK4 integration over the duration
 * - derivatives() called 4 times per step (k1, k2, k3, k4)
 * - Step size affects accuracy (smaller = more accurate but slower)
 * - May diverge for stiff systems (check for NaN/Infinity)
 *
 * Example:
 * <pre>
 * class Decay extends Continuous {
 *     Variable x = new Variable(100.0);
 *
 *     public void derivatives() {
 *         x.rate = -0.1 * x.state;  // Called 200+ times during hold(50)
 *     }
 *
 *     public void actions() {
 *         x.start();
 *         hold(50.0);  // Integrates using derivatives()
 *     }
 * }
 * </pre>
 */
public abstract void derivatives() { ... }
```

---

### 7. Event Detection Pattern Not Documented

**Current Issue**: No guidance on how to detect zero-crossings or discrete events during continuous evolution.

**Impact**: Users try to detect events inside `derivatives()` which is called many times and gets incorrect results.

**Recommendation**: Add event detection documentation with examples:

```java
/**
 * Guidelines for Event Detection in Hybrid Simulations:
 *
 * To detect zero-crossings or discrete events during continuous evolution:
 * 1. Calculate the event time externally (before calling hold)
 * 2. Hold until that specific time
 * 3. Check the event condition and make discrete changes in actions()
 *
 * Example - Bouncing Ball:
 * <pre>
 * class BouncingBall extends Continuous {
 *     Variable position = new Variable(10.0);
 *     Variable velocity = new Variable(0.0);
 *     double gravity = -9.81;
 *
 *     public void derivatives() {
 *         position.rate = velocity.state;
 *         velocity.rate = gravity;
 *     }
 *
 *     private double timeToGround() {
 *         double v = velocity.state;
 *         double h = position.state;
 *         double g = gravity;
 *         // Solve: h + v*t + 0.5*g*t^2 = 0
 *         return (-v - Math.sqrt(v*v + 2*g*h)) / g;
 *     }
 *
 *     public void actions() {
 *         position.start();
 *         velocity.start();
 *
 *         while (time() < 100.0) {
 *             double dt = timeToGround();
 *             hold(dt);  // Integrate until ground contact
 *
 *             // Discrete event: bounce
 *             velocity.state = -0.9 * velocity.state;
 *         }
 *     }
 * }
 * </pre>
 *
 * DO NOT try to detect events inside derivatives() - it is called
 * many times with intermediate state values and will give incorrect results.
 */
```

---

### 8. Variable Units and Dimensional Analysis

**Current Issue**: No warning about dimensional consistency between Variable.state, Variable.rate, and time units.

**Impact**: Silent errors when users mix units (e.g., meters with milliseconds).

**Recommendation**:
```java
/**
 * The rate of change (derivative) of this variable.
 *
 * Units: [state units] per [time unit]
 *
 * CRITICAL - DIMENSIONAL CONSISTENCY:
 * Ensure dimensional consistency between state, rate, and hold() duration.
 *
 * Example:
 * - If state is in meters and time in seconds, rate must be m/s
 * - If state is in degrees and time in seconds, rate must be degrees/s
 * - Mixing units (e.g., meters + milliseconds) causes silently incorrect results
 *
 * <pre>
 * Variable position = new Variable(100.0);  // meters
 * Variable velocity = new Variable(5.0);     // meters/second
 *
 * public void derivatives() {
 *     position.rate = velocity.state;  // m/s is correct for position in meters
 * }
 *
 * public void actions() {
 *     hold(10.0);  // 10 seconds - matches velocity units
 * }
 * </pre>
 */
public double rate;
```

---

### 9. Simulation.stop() Side Effects Not Clear

**Current Issue**: Documentation doesn't explain:
- Thread safety of stop()
- State consistency when stopped mid-integration
- Non-restartability

**Recommendation**:
```java
/**
 * Stops the simulation immediately.
 *
 * THREAD SAFETY:
 * This method can be called from process threads. Uses @Volatile for
 * visibility across threads, but there may be a delay before the simulation
 * actually stops if a process is in hold().
 *
 * STATE CONSISTENCY:
 * Variables may be left in intermediate states if stopped mid-integration
 * during a Continuous hold(). The state values will be whatever they were
 * at the last completed RK4 sub-step.
 *
 * NON-RESTARTABILITY:
 * The simulation cannot be restarted after being stopped. Create a new
 * Simulation instance for subsequent runs.
 *
 * Example:
 * <pre>
 * class Monitor extends Process {
 *     public void actions() {
 *         hold(10.0);
 *         if (defectCount > threshold) {
 *             simulation().stop();  // Halt everything
 *         }
 *     }
 * }
 * </pre>
 */
@Volatile
public void stop() { ... }
```

---

### 10. Process Lifecycle State Queries

**Current Issue**: No API to query process state (isActive, isPassivated, isTerminated).

**Impact**: Difficult to debug process lifecycle issues.

**Recommendation**: Add state query methods:

```java
public abstract class Process extends Link {
    /**
     * Checks if this process is currently active (scheduled in event queue).
     *
     * @return true if process is scheduled, false otherwise
     */
    public boolean isActive() {
        // Implementation
    }

    /**
     * Checks if this process is passivated (waiting for reactivation).
     *
     * @return true if passivated, false otherwise
     */
    public boolean isPassivated() {
        // Implementation
    }

    /**
     * Checks if this process has terminated.
     *
     * @return true if terminated, false otherwise
     */
    public boolean isTerminated() {
        // Implementation
    }
}
```

---

### 11. Link/Head Memory Management

**Current Issue**: No clear guidance on memory management for Link objects when removed from Heads.

**Impact**: kDisco initially used WeakHashMap incorrectly, leading to memory leaks.

**Recommendation**:
```java
/**
 * Removes this link from its current list.
 *
 * MEMORY MANAGEMENT:
 * After calling out(), this link is no longer part of any list. If you
 * maintain registries or mappings of links, ensure they are cleaned up:
 *
 * <pre>
 * Map<Link, Data> registry = new HashMap<>();
 *
 * link.out();
 * registry.remove(link);  // IMPORTANT: Clean up registry
 * </pre>
 *
 * Do NOT use WeakHashMap for link registries - links may be GC'd while
 * still in use if only weakly referenced.
 */
public void out() { ... }
```

---

### 12. Process Self-Scheduling Warning

**Current Issue**: No warning against calling `activate(this)` from within `actions()`.

**Impact**: Infinite loops or stack overflow.

**Recommendation**:
```java
/**
 * Common Pitfalls:
 *
 * DO NOT call activate() on the same process instance from within
 * its own actions() method:
 * <pre>
 * // WRONG - causes infinite loop or stack overflow
 * public void actions() {
 *     activate(this, 0.0);  // Don't do this!
 *     hold(1.0);
 * }
 * </pre>
 */
```

---

## 📊 Documentation Improvements

### 13. Examples and Tutorials

**Current Status**: Limited examples, especially for:
- Continuous simulation
- Hybrid discrete-continuous models
- Multi-process synchronization
- Event detection

**Recommendation**: Add comprehensive example suite:

1. **Basic Examples**:
   - Simple discrete queue (M/M/1)
   - Exponential decay (continuous)
   - Bouncing ball (hybrid)

2. **Advanced Examples**:
   - Producer-consumer with synchronization
   - Predator-prey (Lotka-Volterra)
   - Traffic simulation
   - Chemical reaction kinetics

3. **Anti-patterns**:
   - Common mistakes and how to avoid them
   - Debugging techniques
   - Performance optimization

---

### 14. API Reference Completeness

**Recommendation**: Ensure comprehensive JavaDoc for all public methods covering:
- Parameters and return values
- Exceptions thrown
- Thread safety
- Side effects
- Usage examples
- Related methods (see also)
- Performance characteristics

---

## 🔧 Implementation Priority

### Phase 1: Critical (Should fix in next release)
1. Input validation with clear error messages
2. Variable lifecycle documentation with examples
3. Thread safety documentation for Process/Head/Link
4. Process.cancel() vs passivate() clarity

### Phase 2: High Priority (Next 2-3 releases)
5. Continuous integration documentation (derivatives() frequency, RK4 details)
6. Event detection patterns and examples
7. Activation timing semantics (SIMULA queue behavior)
8. Simulation.stop() side effects

### Phase 3: Medium Priority (Future releases)
9. Dimensional analysis warnings for Variables
10. Process lifecycle state query API
11. Memory management guidance
12. Process self-scheduling warnings
13. Comprehensive example suite
14. Complete API reference documentation

---

## 📝 Testing Recommendations

Alongside these improvements, consider adding:

1. **Unit tests** for edge cases:
   - Negative time values
   - Empty lists
   - Process lifecycle transitions

2. **Integration tests** for:
   - Multi-threaded scenarios
   - Continuous integration
   - Event detection

3. **Documentation tests**:
   - Verify all code examples compile and run
   - Ensure examples demonstrate best practices

---

## 🎯 Benefits for jDisco Users

These improvements would provide:

✅ **Better Developer Experience**
- Clear error messages
- Comprehensive documentation
- Fewer silent failures

✅ **Fewer Bugs**
- Input validation catches errors early
- Thread safety warnings prevent race conditions
- Clear lifecycle semantics

✅ **Easier Learning Curve**
- More examples
- Better documentation
- Anti-pattern warnings

✅ **Better Performance**
- Understanding derivatives() frequency helps optimization
- Clear memory management prevents leaks

---

## 📚 References

- **kDisco Expert Review**: See KDOC_EXPERT_REVIEW_REPORT.md
- **kDisco Fixes**: See FIXES_IMPLEMENTED.md
- **Original jDisco**: https://github.com/bedaHovorka/jdisco
- **SIMULA**: Original simulation language inspiring Process/Link/Head design

---

**Prepared by**: kDisco development team
**Date**: 2026-02-08
**Version**: 1.0
**Contact**: For discussion of these recommendations

---

**Note**: All recommendations maintain backward compatibility where possible. Breaking changes are clearly marked and include migration guidance.
