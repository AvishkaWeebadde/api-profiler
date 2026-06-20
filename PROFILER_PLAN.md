# Pluggable JVM Profiler — Build Plan

**Goal:** A profiler I can attach to *any* JVM application that decomposes
request latency by layer, built on ASM so I actually understand the JVM
underneath it — not just wire a library together.

**Priority order (do not reorder):** 1. Learn  2. Working  3. Pluggable.
Pluggability is the *last* hard problem, not the first. The profiler must work
in the simple case before it works everywhere.

---

## Operating principles (read before every session)

- **Read before write.** I cannot emit bytecode I cannot read. `javap` fluency
  comes before ASM.
- **The notes file is the deliverable.** `docs/bytecode-notes.md` + ADRs are
  proof of progress even on weeks I ship no code. Unstructured learning and
  abandoned learning look identical from outside — the notes are how I tell
  them apart.
- **One ADR per real fork.** If a competent engineer could reasonably disagree
  with a choice, it gets an ADR in `docs/adr/`.
- **Reversible decisions get made fast.** Library/format choices are contained
  behind interfaces; don't agonize.
- **Ship the spine, wander one rabbit hole per phase.** The profiler is the
  goal; JIT/assembly tangents are the reward, capped at one per phase.
- **End each session on green.** Commit when something runs. Banking a win is
  how I come back after a hard week.

---

## Known personal failure mode (mitigate, don't pretend it's absent)

History of strong-start → week-4-to-6 collapse. Mitigations baked into this
plan:
- Every phase has a **tiny "floor" deliverable** that's done in one evening, so
  no phase is a cliff.
- **Git checkpoint every session.** Restart cost stays near zero.
- Phases are **independently valuable** — if I stop at Phase 4, I still have a
  real working single-method timer and a pile of JVM knowledge. Nothing is
  wasted by stopping early.

---

## Phase 0 — Read bytecode fluently  *(JDK only, no ASM, no downloads)*

**Learn:** The JVM is a stack machine. Operand stack, local variable slots
(slot 0 = `this` for instance methods), type-prefixed instructions
(`i`/`a`/`l`/`d`...), method descriptors (`(II)I`).

**Build:** Nothing yet — a practice loop. Write small methods, *predict* the
bytecode, then `javap -c -p` to check.

**Method set to disassemble (trivial → surprising):**
1. `int add(int a, int b)` — baseline (loads, `iadd`, return).
2. A `for` loop summing 1..n — see jumps/labels (`goto`, `if_icmp...`).
3. An `if/else` returning different ints — branching.
4. A method calling another method — `invokevirtual` + descriptors.
5. `String greet(String name)` returning `"hi " + name` — **surprise:**
   compiles to `StringBuilder` / `invokedynamic`, not what you wrote.
6. A `try/finally` — **surprise:** see how `finally` is duplicated/handled.

**Done when:** I can read any small method's disassembly and roughly predict it
before peeking. Notes file has an entry per surprise.

**ADR prompt:** none (no decisions yet).

---

## Phase 1 — The transformer hook  *(observe, don't modify)*

**Learn:** `ClassFileTransformer`, the load-time interception point. The JVM
shows me every class's raw `byte[]` on its way in.

**Build:** Register a transformer via `inst.addTransformer(...)` that returns
bytecode **unchanged** but prints every class name it sees load. Attach to the
dummy app, then to the baby tracker, and watch hundreds of classes scroll.

**Floor deliverable:** class-name printer running against the dummy app.

**Done when:** I can see my own classes load and confirm the transformer fires
before `main()`.

**ADR prompt:** ADR for "instrument via agent transformer vs AOP" (already
reasoned: AOP can't see JDBC/HTTP internals and needs app cooperation).

---

## Phase 2 — ASM, read-only  *(parse, classify, still don't modify)*

**Learn:** ASM's visitor model — `ClassReader` → `ClassVisitor` →
`MethodVisitor`. Map every callback to the `javap` instructions from Phase 0.

**Build:** Feed the incoming `byte[]` to a `ClassReader` and walk it. For each
method, print its name + descriptor + access flags (static vs instance!).
Filter: only print methods in a target package I configure.

**Floor deliverable:** prints "found method X with descriptor Y, static=Z" for
my app's classes only.

**Done when:** I can list exactly the methods I'd want to time, correctly
distinguishing static from instance (the slot-0 gotcha).

**ADR prompt:** ASM vs ByteBuddy vs Javassist — record *why ASM* (learning
depth; everything is built on it; accept the cost of manual stack management).

---

## Phase 3 — Inject timing into ONE method  *(first real modification)*

**Learn:** Emitting instructions with `MethodVisitor`. Calling
`System.nanoTime()` (`INVOKESTATIC`, descriptor `()J`). Storing to a fresh
local slot. **Stack discipline** — leave the stack exactly as found or the
verifier rejects the class.

**Build:** For one hard-coded target method: at entry, `nanoTime()` → store; at
*every* return, `nanoTime()` again → subtract → print elapsed. Handle multiple
return paths and the implicit return.

**Floor deliverable:** one method prints its own execution time, zero source
changes to the app.

**Done when:** attaching the agent makes a real method report its latency, and
the app still runs correctly (no verifier errors).

**ADR prompt:** how timing is injected (entry/exit advice) and the known gap
(exceptions thrown past the exit — does the timer still fire?).

**Rabbit-hole reward:** dump the JIT'd assembly of the timed method
(`-XX:+PrintAssembly`) and see what `nanoTime` compiles to.

---

## Phase 4 — Generalize the instrumentation  *(config-driven, robust)*

**Learn:** Bytecode edge cases — exception exits (use try/finally-style
handler so the timer fires even when the method throws), constructors, static
vs instance slot offsets, methods that return `void`/`long`/`double` (different
return opcodes).

**Build:** Drive targeting from config (a package prefix, an annotation like
`@RestController`, or a method-name pattern). Instrument *all* matching methods
correctly. Survive exceptions.

**Floor deliverable:** point the agent at `@RestController` classes and get
timings for every endpoint with no per-method code.

**Done when:** it correctly times a mix of instance/static/void/throwing
methods in the baby tracker.

**ADR prompt:** targeting strategy (annotation vs package vs config file) and
exception-handling approach.

---

## Phase 5 — Latency decomposition  *(the actual product insight)*

**Learn:** `ThreadLocal` request context; why it breaks across thread
boundaries (note as a known limitation, the honest senior move); attributing
time across layers.

**Build:** Propagate a request id per thread. Instrument the layers —
controller → service → JDBC (`PreparedStatement.execute*`) → HTTP clients
(`RestTemplate`/`WebClient`). Produce: "endpoint took 800ms = 20ms app + 760ms
in *this one query*." This is the thing that made the 24s→sub-second win
findable — now it's automatic.

**Floor deliverable:** one endpoint's time broken into app-vs-DB.

**Done when:** a slow endpoint in the baby tracker shows *where* the time went,
not just that it was slow.

**ADR prompt:** context propagation approach + the async/thread-boundary
limitation, explicitly deferred.

---

## Phase 6 — Output that isn't `println`

**Learn:** Span model (start, end, parent, attributes). OTLP / OpenTelemetry
format as the lingua franca of observability.

**Build:** Emit spans as structured JSON to a file. Stretch: OTLP so it could
feed Grafana Tempo / Jaeger. A single static-HTML waterfall viewer is enough —
no UI framework.

**Floor deliverable:** spans written to `spans.json` instead of stdout.

**Done when:** I can open a request's waterfall and read the layer breakdown
visually.

**ADR prompt:** custom format vs OTLP (interop vs simplicity).

**Rabbit-hole reward:** read how the OpenTelemetry Java agent structures its
spans — your "annotate an expert codebase" hour.

---

## Phase 7 — Measure your own overhead  *(the credibility phase)*

**Learn:** What "overhead" means — p50 vs p99, why instrumentation cost is
non-uniform. Honest benchmarking.

**Build:** Use the `-NoAgent` switch in `build.ps1` + JMeter (from the thesis
toolkit) to benchmark the baby tracker with and without the agent at fixed RPS.
Publish the numbers in the README.

**Floor deliverable:** one table: p50/p99 latency, agent on vs off.

**Done when:** the README contains a sentence like "adds ~X% p99 overhead at N
RPS, measured as follows." That is a principal-level sentence.

**ADR prompt:** benchmarking methodology and its limits (single machine, warm
JIT, etc.).

---

## Phase 8 — Make it pluggable into anything  *(the hard generality)*

**Learn:** `premain` (launch-time) vs the **Attach API** (`agentmain`,
attaching to an *already-running* JVM by PID). Classloader isolation — your
agent classes must not collide with the app's. Getting `-javaagent` into
different launch styles: plain `java -jar`, Spring Boot fat jar, Gradle
`bootRun`, Tomcat, Docker (`JAVA_TOOL_OPTIONS`).

**Build:** (a) A clean single jar with a config file, no code changes required
to target apps. (b) Dynamic attach: `profiler attach <pid>` instruments a live
JVM. (c) A short doc: "how to attach in each environment."

**Floor deliverable:** attach to the running baby-tracker droplet process by
PID without restarting it.

**Done when:** I can take the jar to a JVM app I've never seen and get latency
decomposition by editing only its launch command (or attaching by PID).

**ADR prompt:** premain vs dynamic attach tradeoffs; classloader isolation
approach.

---

## Phase 9 — My own essence  *(the differentiator — define this myself)*

A profiler that just times methods is a clone. The "essence" is the opinion it
has that others don't. Candidate directions (pick ONE, write an ADR arguing for
it):

- **Query-hotspot hunter.** Lean hard into the DB layer: auto-flag N+1 patterns
  and the single slowest query behind each slow endpoint. This mirrors my real
  career win (24s→sub-second was a query fix) and is the thing app developers
  actually need. *Strongest fit.*
- **Zero-config "what's slow right now."** Attach by PID to any prod JVM and get
  a 60-second top-N slow-endpoint report with no setup. Optimizes for the panic
  moment.
- **Teaching profiler.** Emits not just timings but the bytecode it injected and
  why — a profiler that shows its own work. Fits the LEARN identity; unusual.

**Done when:** the README's first paragraph states an opinion no generic
profiler makes, and the code backs it up.

---

## Definition of "done" for the whole project

I can hand someone a jar + one page of docs, and they can attach it to a JVM app
they run — launch-time or by PID — and get a per-request latency waterfall that
points at the slow layer, with my one opinionated feature on top, and a README
that honestly states its overhead and its limitations.

If I stop after Phase 4, I still have: a working single-target ASM timing agent
and the ability to read/emit JVM bytecode. That alone was worth the project.
