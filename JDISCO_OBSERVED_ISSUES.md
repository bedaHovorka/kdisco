# jDisco Observed Issues

**Source**: kDisco Expert Review (2026-02-08)
**Context**: Issues discovered while wrapping jDisco 1.2.0 for Kotlin
**Status**: All issues have workarounds in kDisco; this documents what was found in jDisco

---

## Critical Issues Observed

### 1. Process.cancel() vs passivate() - Unclear API

**What we observed**:
- Initially implemented kDisco's `terminate()` using `passivate()` instead of `cancel()`
- The distinction between permanent termination and temporary suspension was not obvious
- This was a critical bug that prevented proper process termination

**Root cause**: Naming/documentation in jDisco doesn't make the difference immediately clear

**kDisco workaround**: Fixed to use `cancel()` with clear KDoc

---

### 2. Variable Lifecycle Methods - Undocumented Requirement

**What we observed**:
- Continuous integration didn't work without calling `Variable.start()`
- Failed silently - Variables simply didn't integrate
- Methods exist (`start()`, `stop()`, `isActive()`) but not documented in examples

**Root cause**: Critical setup step not mentioned in documentation

**kDisco workaround**: Added comprehensive documentation and test examples showing required usage

---

### 3. No Input Validation

**What we observed**:
- Negative values for `hold()`, `activate()`, `run()` accepted without error
- Caused undefined behavior and hard-to-debug simulation failures
- Silent failures rather than clear error messages

**Root cause**: jDisco doesn't validate time/duration parameters

**kDisco workaround**: Added `require()` checks at Kotlin wrapper level

---

### 4. Thread Safety Not Documented

**What we observed**:
- Each process runs in its own Java thread (discovered through testing)
- `Head` and `Link` are not thread-safe (caused race conditions in multi-process tests)
- No documentation warning about synchronization requirements

**Root cause**: Critical threading behavior not documented

**kDisco workaround**: Added extensive thread safety warnings in KDoc

---

### 5. WeakHashMap Issue in Link Registry

**What we observed**:
- Initially used `WeakHashMap` for Link→kDisco wrapper registry
- Caused memory leaks when links were removed from Heads
- WeakHashMap entries not cleaned up properly on `out()`

**Root cause**: No clear guidance on memory management for Link registries

**kDisco workaround**: Changed to `HashMap` with explicit cleanup in `out()`

---

## High Priority Issues Observed

### 6. reactivate() vs activate() - Method Confusion

**What we observed**:
- Initially called `activate()` instead of `reactivate()` for passivated processes
- Both methods exist on Process but usage pattern not clear
- Broke 1:1 API mapping goal

**Root cause**: API design requires knowing when to use which method

**kDisco workaround**: Fixed to use correct `reactivate()` method

---

### 7. activate(delay=0.0) Behavior Unclear

**What we observed**:
- Expected immediate execution but process queued after current process
- Follows SIMULA semantics (event queue ordering) but not documented
- Caused timing confusion in early tests

**Root cause**: SIMULA event queue behavior not explained

**kDisco workaround**: Added detailed KDoc explaining event queue semantics

---

### 8. derivatives() Call Frequency Not Mentioned

**What we observed**:
- `derivatives()` called 50-500+ times per integration step
- Discovered through debugging/profiling, not documentation
- Critical for performance optimization

**Root cause**: RK4 implementation details not documented

**kDisco workaround**: Documented call frequency and RK4 algorithm details in KDoc

---

## Medium Priority Issues Observed

### 9. Variable.state Modification Rules Unclear

**What we observed**:
- Initially unclear when Variable.state can/cannot be modified
- Can modify in `actions()`, cannot during `derivatives()` or `hold()`
- Documentation just said "do not modify state" without context

**Root cause**: Incomplete documentation about modification rules

**kDisco workaround**: Clarified with detailed rules in KDoc

---

### 10. Event Detection Pattern Missing

**What we observed**:
- No guidance on detecting zero-crossings during continuous simulation
- Tried (incorrectly) to detect events inside `derivatives()`
- Had to discover correct pattern (calculate time, hold until event, handle in actions)

**Root cause**: No event detection examples or documentation

**kDisco workaround**: Added comprehensive event detection pattern with bouncing ball example

---

### 11. Time Advancement Wording Misleading

**What we observed**:
- Documentation says "time advances smoothly during hold"
- Actually: clock jumps discretely, state variables integrated smoothly
- Intermediate RK4 steps don't affect simulation clock

**Root cause**: Imprecise wording about continuous simulation

**kDisco workaround**: Clarified distinction between clock advancement and state integration

---

### 12. Simulation.stop() Side Effects Unknown

**What we observed**:
- Unclear what happens to Variables mid-integration when stopped
- Thread safety of stop() not documented
- Non-restartability not mentioned

**Root cause**: Incomplete documentation

**kDisco workaround**: Documented thread safety, state consistency, and restart behavior

---

### 13. No Process State Query API

**What we observed**:
- Needed to check if process is active/passivated/terminated
- No API methods available (isActive(), isPassivated(), isTerminated())
- Made debugging lifecycle issues difficult

**Root cause**: Missing API functionality

**kDisco workaround**: Documented limitation, suggested using logging for debugging

---

### 14. Dimensional Analysis Not Mentioned

**What we observed**:
- No warning about unit consistency (state units, rate units, time units)
- Easy to mix units (e.g., meters with milliseconds) with silent failures
- Critical for correct simulation results

**Root cause**: No documentation about dimensional consistency

**kDisco workaround**: Added dimensional analysis warnings in Variable KDoc

---

### 15. Code Examples Use Old/Incorrect Patterns

**What we observed**:
- Circular list traversal examples used fragile while loops
- Should use do-while with identity checks (===)
- Backward traversal had logic error (never terminated)

**Root cause**: Examples not using best practices

**kDisco workaround**: Fixed all traversal examples to use correct patterns

---

### 16. Final Classes Prevent Kotlin Multiplatform Wrapping

**What we observed**:
- Build warnings about modality mismatches between expect/actual classes
- jDisco classes that should be extended (Process, Continuous, Link) are not final
- But internal delegate properties in actual implementations are final
- Warning: "property 'jDiscoDelegate': the modality of this member must be the same in expect class and actual class"

**Specific warnings**:
```
w: ProcessActual.kt:14:5 property 'jDiscoDelegate': the modality must be the same
   in the expect class and the actual class. This error happens because the
   expect class 'Process' is non-final
```

**Root cause**: Kotlin Multiplatform requires consistent modality between expect/actual members when class is non-final

**kDisco workaround**: Using `final override` for delegate properties; warnings are non-critical but indicate design friction

**Note**: This becomes a compile error in future Kotlin versions (currently just warning)

---

## Summary Statistics

**Total Issues Observed**: 16

**Breakdown by Severity**:
- Critical: 5 (would cause major bugs if not addressed)
- High: 3 (important for correct usage)
- Medium: 8 (documentation/examples improvements + multiplatform compatibility)

**All issues have workarounds in kDisco**, but addressing them in jDisco would benefit all users.

---

**Document Purpose**:
This is a record of actual issues encountered, not speculation. Each issue was discovered through:
- Implementation mistakes during kDisco development
- Expert review of kDisco code
- Testing and debugging
- Comparison with jDisco behavior

**Not Included**:
- Theoretical issues
- Feature requests
- Architectural changes
- Speculative improvements

---

**Prepared by**: kDisco development team
**Date**: 2026-02-08
**Version**: 1.0
