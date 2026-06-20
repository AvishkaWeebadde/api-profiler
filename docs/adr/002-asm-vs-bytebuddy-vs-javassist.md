# ADR 002 — ASM vs ByteBuddy vs Javassist

**Phase:** 2  
**Status:** decided

## Context

We need a library to read and later modify JVM bytecode inside the transformer.
Three realistic options at different levels of abstraction:

**Option A — ASM**  
Low-level visitor API. You walk the class file structure callback by callback
(`visitMethod`, `visitInsn`, `visitVarInsn`...). You are responsible for stack
discipline and correct instruction sequencing. Everything else (ByteBuddy,
Javassist, the OpenTelemetry Java agent, most profilers) is built on top of ASM.

**Option B — ByteBuddy**  
High-level DSL that expresses instrumentation as Java method interception. You
write `ElementMatchers.named("execute")` and supply an `Advice` class; ByteBuddy
figures out the bytecode. Very ergonomic for production instrumentation.

**Option C — Javassist**  
Middle ground. Lets you write advice as Java source strings that it compiles to
bytecode. Lower ceremony than ASM, less abstraction than ByteBuddy.

---

## Decision

Option A — ASM.

---

## Reasoning

The goal of this project is to understand what the JVM is actually doing, not to
produce the fastest path to a working agent. ASM is the right choice because:

**Learning depth:** ASM exposes exactly the structure `javap` shows — opcodes,
descriptors, local variable slots, the operand stack. Every ASM callback maps
directly to something we disassembled in Phase 0. ByteBuddy and Javassist hide
this; ASM teaches it.

**It's what everything is built on:** If you read the OpenTelemetry Java agent,
the Byte Buddy internals, or any serious JVM profiler, you will find ASM underneath.
Understanding ASM means you can read those codebases. The inverse is not true.

**Cost is contained:** The hard parts of manual ASM — stack discipline, multiple
return paths, exception handlers — are exactly the edge cases this project exists
to learn (Phases 3 and 4). Accepting that cost is the point.

**What a competent engineer would argue for ByteBuddy:**  
"ByteBuddy handles every edge case correctly by default — exception exits, type
widening, `this` slot offsets for static vs instance — and its `@Advice` model
is used in production by the biggest Java observability tools. You will spend
three phases debugging verifier errors that ByteBuddy would have silently
handled. The learning goal is met by reading ASM's source, not by suffering it."
That argument is correct if the goal were shipping a production tool. It isn't.

---

## Consequences

**Made easier:**
- Every ASM callback is a direct counterpart to a `javap` instruction or section.
  The mental model from Phase 0 transfers directly.
- No annotation processing, no reflection tricks — just raw byte array in,
  raw byte array out.

**Made harder:**
- Stack discipline is manual. If the operand stack depth or types are wrong at
  any instruction, the verifier rejects the class with a `VerifyError`. The error
  message rarely tells you which instruction caused it.
- Multiple return opcodes (`RETURN`, `IRETURN`, `ARETURN`, `LRETURN`, `DRETURN`,
  `FRETURN`) must each be handled. A `MethodVisitor` that only hooks `IRETURN`
  will silently miss void and reference-returning methods.
- Exception exits need an explicit exception table entry (see Phase 0 notes on
  `try/finally` duplication). ASM provides `visitTryCatchBlock` for this —
  it is not automatic.
- ASM must be bundled inside the agent jar (fat jar) since the app's classloader
  does not know about our `lib/` directory.
