# ADR 001 — Instrument via agent transformer vs AOP

**Phase:** 1  
**Status:** decided

## Context

To time method execution we need to intercept it — insert code at entry and exit
without touching the application's source. Two realistic options:

**Option A — Java agent + `ClassFileTransformer`**  
A `premain` method registers a transformer. The JVM calls `transform(byte[])` for
every class as it loads, handing us the raw bytecode. We can return modified bytes
or pass through unchanged. The app knows nothing.

**Option B — AOP framework (Spring AOP or AspectJ LTW)**  
Define pointcuts that match methods; the framework weaves in advice (before/after
logic) automatically. Spring AOP does this via runtime proxies; AspectJ LTW uses
its own agent to weave at load time.

---

## Decision

Option A — Java agent + `ClassFileTransformer`.

---

## Reasoning

The core requirement is decomposing latency *across layers* — not just app code,
but JDBC (`PreparedStatement.execute*`) and HTTP clients (`RestTemplate`). That
requirement kills Option B immediately:

**Why Spring AOP fails here:**  
Spring AOP works by wrapping Spring-managed beans in JDK proxies or CGLIB
subclasses. `PreparedStatement` is not a Spring bean — it comes from the JDBC
driver. Spring AOP cannot touch it. It also cannot intercept static methods or
calls within the same class (self-invocation bypasses the proxy entirely).

**Why AspectJ LTW is closer but still wrong:**  
AspectJ is genuinely powerful and its load-time weaving agent does something
similar to what we're building. But it requires the app to be configured for
AspectJ, handles the weaving internally so you can't see what it emits, and
abstracts away the bytecode — which is exactly what we're trying to learn. Using
AspectJ here would be correct engineering but wrong for the project's goal.

**What a competent engineer would argue for AOP:**  
"AspectJ handles exception exits, multiple return paths, and void vs non-void
returns correctly by default. You're going to hit verifier errors building all of
that yourself, and AspectJ's pointcut language is expressive enough to target
JDBC if you weave the driver classes. The bytecode learning can come separately."
That's a fair argument. The rebuttal is that this project's primary goal is
understanding what ASM does under the hood — accepting the abstraction defeats
the purpose.

**Why the agent transformer wins on technical merit too:**  
- Sees every class that loads — app code, JDBC drivers, HTTP client internals,
  JDK classes — without any app cooperation.
- Zero requirements on the target app: no Spring, no AspectJ config, no
  annotation, no source change. Attach and go.
- Returns `null` from `transform()` to leave a class untouched, so the cost of
  visiting a class we don't care about is minimal.

---

## Consequences

**Made easier:**
- Can instrument JDBC, HTTP clients, and any third-party library — not just app
  code. This is what makes latency decomposition possible.
- No coupling to the target app's framework. The same jar works on Spring Boot,
  plain servlets, or anything else on the JVM.
- We control exactly what bytecode is emitted. When something breaks we can
  inspect the output directly with `javap`.

**Made harder:**
- Stack discipline is now our problem. Every method has an operand stack; we must
  leave it in the state the verifier expects or the class is rejected with a
  `VerifyError`. AOP frameworks handle this internally.
- Multiple return paths must each be found and instrumented. A method with three
  `return` statements needs three exit hooks (Phase 3's main challenge).
- Exception exits are invisible unless we explicitly add a try/finally-style
  exception handler in bytecode (the Phase 4 edge case).
- `void`, `long`, and `double` use different return opcodes (`RETURN`, `LRETURN`,
  `DRETURN`). Getting this wrong causes a verifier error with an unhelpful
  message.
