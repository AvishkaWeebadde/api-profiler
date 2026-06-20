# ADR 003 — Timing injection approach (entry/exit advice)

**Phase:** 3  
**Status:** decided

## Context

To measure how long a method takes, we need to record a timestamp at entry and
another at exit, then subtract. The question is how to get both timestamps into
the bytecode of a method we don't own, without touching its source.

Two sub-questions:
1. Where do we store the entry timestamp between entry and exit?
2. How do we find every exit point?

---

## Decision

Store the entry timestamp in a **fresh local variable slot** appended after the
method's existing locals. Intercept every **return opcode** via `visitInsn` to
inject the exit timestamp and subtraction.

---

## Reasoning

**Why a local variable slot (not a static field or thread-local)?**  
A static field would be overwritten on re-entrant or concurrent calls — wrong
value, useless measurement. A `ThreadLocal` is the correct production approach
(and what Phase 5 will use for request context), but it requires a method call at
both entry and exit, adding more injected instructions. A local variable slot is
the simplest option: it lives exactly as long as the method invocation, costs one
`LSTORE`/`LLOAD` pair, and is automatically thread-safe because each stack frame
is private to its thread.

**Why append after existing locals (not slot 0)?**  
The method's existing local variables already occupy slots starting at 0
(or 1 for instance methods, where slot 0 = `this`). Writing into an occupied
slot would corrupt the method's own variables and cause wrong results or a
`VerifyError`. We use a two-pass approach: pass 1 reads the original `maxLocals`
from the class file; pass 2 uses `maxLocals` as the starting slot for injected
locals. ASM's `COMPUTE_FRAMES` then recalculates the correct `maxLocals` for the
modified method.

**Why `COMPUTE_FRAMES` instead of `COMPUTE_MAXS`?**  
After inserting instructions, the original StackMapFrames (used by the JVM
verifier) may reference stale local variable counts. `COMPUTE_FRAMES` tells ASM
to discard all original frame information and regenerate it from a dataflow
analysis of the new bytecode. `COMPUTE_MAXS` only recalculates stack depth and
local count — it doesn't fix frames. Using `COMPUTE_MAXS` in a method with
a back-jump (like our loop) would leave a frame that doesn't account for the
injected slots, which can cause a `VerifyError` on Java 8+ class files.

**Why intercept `visitInsn` for return opcodes?**  
ASM calls `visitInsn(opcode)` for every zero-operand instruction, including all
six return opcodes (`RETURN`, `IRETURN`, `LRETURN`, `FRETURN`, `DRETURN`,
`ARETURN`). Overriding `visitInsn` and checking the opcode lets us inject exit
code before each return — we inject our timing code, then call
`super.visitInsn(opcode)` to emit the original return.

**What a competent engineer would argue:**  
"Use `AdviceAdapter` from `asm-commons` — it gives you `onMethodEnter()` and
`onMethodExit()` callbacks that already handle all return types and exception
exits. You don't need a two-pass slot calculation or manual opcode checking."
That's correct. `AdviceAdapter` exists precisely for this pattern. We're not
using it because doing it manually is the learning goal — every `LSTORE`,
`LLOAD`, and opcode switch in the code corresponds to something we disassembled
in Phase 0.

---

## Known gap — exception exits

`visitInsn` only fires for bytecode instructions in the normal instruction stream.
If the method exits by throwing an exception (or by an exception propagating out
of a call it makes), there is no `RETURN` opcode — the method exit is handled by
the exception mechanism, not by a return instruction. Our timer does not fire in
this case.

**Evidence:** add `throw new RuntimeException()` to `doWork()` and run — you will
see no `[profiler]` timing line despite the method having started.

**Fix (Phase 4):** wrap the method body in a try/finally-style exception handler
at the bytecode level using `visitTryCatchBlock`. The finally handler fires on
any exception, records the elapsed time, and rethrows. This mirrors exactly what
the Java compiler does for `try/finally` blocks (see Phase 0 notes on
`withFinally`).

---

## Consequences

**Made easier:**
- Simple, direct mapping from Phase 0 knowledge: `INVOKESTATIC nanoTime`, `LSTORE`,
  `LLOAD`, `LSUB` — every instruction emitted is one we've already read in `javap`.
- Local variable slot lifetime matches method invocation exactly — no cleanup needed.
- Two-pass slot calculation is reusable: any future timing injection starts
  after existing `maxLocals`.

**Made harder:**
- Exception exits are silently missed. A method that always throws (or throws
  50% of the time) will report wrong or missing timings.
- Only handles `RETURN` (void) in Phase 3. Methods returning `int`, `long`,
  `Object`, etc. need their respective return opcodes handled too (Phase 4).
- `COMPUTE_FRAMES` requires ASM to perform a dataflow analysis of the modified
  bytecode, which is slower than `COMPUTE_MAXS`. For a single-class agent target
  this is negligible; for instrumenting thousands of classes it may add startup
  latency.
