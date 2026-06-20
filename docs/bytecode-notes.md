# Bytecode Notes

Running notes from Phase 0 — one entry per surprise or insight.
The goal: read any small method's disassembly and roughly predict it before `javap`.

Practice file: `practice/Phase0Practice.java`  
Compile: `javac practice/Phase0Practice.java -d practice/out`  
Disassemble: `javap -c -p practice/out/practice/Phase0Practice.class`

---

## Method set

- [x] `int add(int a, int b)` — baseline
- [x] `for` loop summing 1..n — jumps/labels
- [x] `if/else` returning different ints — branching
- [x] method calling another method — `invokevirtual` + descriptors
- [x] `String greet(String name)` — string concatenation surprise
- [x] `try/finally` — exception table surprise

---

## The JVM is a stack machine — the mental model

Every method has an **operand stack** (for computation) and a **local variable table**
(for storage). Instructions pull values off the stack, operate on them, and push
results back. There is no concept of "registers" at the bytecode level.

**Local variable slots:**  
- Slot 0 is always `this` for instance methods. Parameters fill slots 1, 2, 3...  
- For static methods there is no `this`, so parameters start at slot 0.  
- `long` and `double` occupy two consecutive slots because they are 64-bit.

**Type prefixes on instructions:**  
`i` = int, `a` = reference (object/array), `l` = long, `d` = double, `f` = float.  
So `iload` loads an int; `aload` loads a reference; `ireturn` returns an int.

---

## Method 1 — `int add(int a, int b)`

```
0: iload_1      // push slot 1 (a) onto stack
1: iload_2      // push slot 2 (b) onto stack
2: iadd         // pop two ints, push their sum
3: ireturn      // pop int, return it to caller
```

**Slot layout:** slot 0 = `this`, slot 1 = `a`, slot 2 = `b`.  
Four instructions. The operand stack peaks at depth 2 (both values loaded before add).  
`iload_0/1/2/3` are compact single-byte forms; `iload <n>` is used for slot 4+.

---

## Method 2 — `int sumTo(int n)` (for loop)

```
 0: iconst_0       // push literal 0
 1: istore_2       // pop → slot 2 (sum = 0)
 2: iconst_1       // push literal 1
 3: istore_3       // pop → slot 3 (i = 1)

 4: iload_3        // push i          ← loop header
 5: iload_1        // push n
 6: if_icmpgt 19   // if i > n, jump to 19 (exit)

 9: iload_2        // push sum
10: iload_3        // push i
11: iadd
12: istore_2       // sum += i

13: iinc 3, 1     // slot 3 += 1  (i++, in-place, no stack touch)
16: goto 4         // back to loop header

19: iload_2        // push sum
20: ireturn
```

**Key insight:** `if_icmpgt` pops *two* values and branches if the first is greater
than the second. The condition is inverted from the source: `i <= n` in Java becomes
"jump out if `i > n`" — the compiler flips it.

**`iinc` is special:** it modifies a local variable slot directly without touching
the operand stack at all. Only works for int locals with small constants.

**Why is there no instruction at offset 7 or 8?** `if_icmpgt` is a 3-byte instruction
(opcode + 2-byte branch offset). Offsets are byte positions, not instruction counts.

---

## Method 3 — `int sign(int x)` (if/else)

```
0: iload_1      // push x
1: ifle 6       // if x <= 0, jump to 6
4: iconst_1     // x > 0 path: push 1
5: ireturn
6: iconst_m1    // x <= 0 path: push -1  (m1 = minus one)
7: ireturn
```

**Compiler inversion again:** `if (x > 0)` becomes `ifle` (jump if *not* greater).
The true branch falls through; the false branch is the jump target. Compilers
consistently invert conditions so the happy path needs no jump.

**`iconst_m1`** is a dedicated opcode for the literal -1. There are compact opcodes
for -1 through 5 (`iconst_m1` through `iconst_5`). Larger literals need `bipush`,
`sipush`, or a constant pool entry (`ldc`).

---

## Method 4 — `int doubled(int a)` (calling another method)

```
0: aload_0        // push this (the receiver)
1: iload_1        // push a
2: iload_1        // push a again
3: invokevirtual #7   // call add:(II)I
6: ireturn
```

**`invokevirtual` needs the receiver on the stack first,** then the arguments in
order. The `#7` is a constant pool index — `javap` resolves it to
`Method add:(II)I`.

**Reading a method descriptor:** `(II)I` means "takes two ints, returns an int."
Format: `(parameter-types)return-type`. A `void` return is `V`.
Reference types use `L<fully/qualified/Name>;` — e.g. `Ljava/lang/String;`.

**`invokevirtual` vs others:**
- `invokevirtual` — instance method, normal virtual dispatch (checks runtime type)
- `invokespecial` — constructors, `super` calls, private methods (no virtual dispatch)
- `invokestatic` — static methods (no receiver on stack)
- `invokeinterface` — calls through an interface reference
- `invokedynamic` — runtime-linked call site (see method 5)

---

## Method 5 — `String greet(String name)` — SURPRISE

```
0: aload_1          // push name
1: invokedynamic #13, 0   // InvokeDynamic #0:makeConcatWithConstants:(Ljava/lang/String;)Ljava/lang/String;
6: areturn
```

**Expected:** `new StringBuilder().append("hi ").append(name).toString()`  
**Actual (Java 9+):** a single `invokedynamic` call to `makeConcatWithConstants`.

`invokedynamic` links its call site at runtime via a *bootstrap method*. For string
concatenation the JDK ships a bootstrap (`StringConcatFactory`) that can implement
the join however it wants — currently it uses `StringConcatHelper` internals that
are faster than `StringBuilder` in many cases. The compiler doesn't emit the
`StringBuilder` anymore; it delegates the strategy to the runtime.

**Why this matters for the profiler:** if you instrument `StringBuilder.append` to
find string-building hotspots, you will miss all modern Java string concatenation.
The work happens inside `makeConcatWithConstants`, not in explicit `StringBuilder`
calls.

**Pre-Java-9** compilers did emit `StringBuilder` explicitly. If you ever see that
in production bytecode, the jar was compiled with an old JDK.

---

## Method 6 — `int withFinally(int x)` — SURPRISE

```
 0: iload_1         // push x
 1: iconst_2        // push 2
 2: imul            // x * 2
 3: istore_2        // store result in slot 2

 4: getstatic #17   // System.out
 7: ldc #23         // "done"
 9: invokevirtual #25  // println
12: iload_2         // push stored result
13: ireturn         // return it   ← normal path

14: astore_3        // exception lands here: store it in slot 3
15: getstatic #17   // System.out   ← finally block AGAIN
18: ldc #23         // "done"
20: invokevirtual #25  // println
23: aload_3         // push the exception
24: athrow          // rethrow it   ← exception path

Exception table:
   from  to  target  type
      0   4    14    any
```

**The big surprise: `finally` is duplicated.** The compiler does not jump to a
single `finally` block — it copies the finally body into each exit path. Here it
appears twice: once before `ireturn` (normal path) and once before `athrow`
(exception path).

**The exception table** is how the JVM knows where to jump on a throw. It says:
"if any exception is thrown between offset 0 and 4, jump to offset 14." The
`any` type means it catches everything — equivalent to `catch (Throwable t)`.

**`getstatic`** loads a static field reference onto the stack. Here it loads
`System.out` (a `PrintStream`). It is the bytecode equivalent of reading a static
field in Java.

**`ldc`** (load constant) pushes a value from the constant pool — strings,
class literals, numeric constants too large for the compact `iconst` opcodes.

**`athrow`** pops a reference off the stack and throws it. It does not return.

**Why this matters for the profiler:** when we instrument method exit in Phase 3
by inserting code before every `ireturn`/`areturn` etc., we will miss the
exception path. The fix (Phase 4) is to add an exception handler in bytecode
that fires the timer and rethrows — exactly what the compiler did here for
`finally`.

---

## The constructor (bonus)

```
0: aload_0           // push this
1: invokespecial #1  // Object.<init>:()V
4: return
```

Every class gets a default constructor even if you don't write one. Its only job
is to call `super()` via `invokespecial`. `return` (no prefix) is the void return
opcode. Note that constructors are named `<init>` in bytecode and called with
`invokespecial`, never `invokevirtual`.

---

---

## Phase 1 — ClassFileTransformer observations

**What fires `premain`:** The JVM calls `premain` before it calls `main()`. The
agent is wired in at JVM startup via `-javaagent:profiler.jar`. By the time `main`
runs, the transformer is already registered and has seen every class that loaded
to get there.

**`transform()` signature:**
```java
byte[] transform(ClassLoader loader, String className,
                 Class<?> classBeingRedefined,
                 ProtectionDomain protectionDomain,
                 byte[] classfileBuffer)
```
- `className` is in **internal form** (`java/lang/String`, not `java.lang.String`).
  Slashes, not dots. Convert with `.replace('/', '.')` for display.
- `classfileBuffer` is the raw bytes of the `.class` file — exactly what `javap`
  reads. This is what we will pass to ASM in Phase 2.
- Return `null` to leave the class unchanged. Return a new `byte[]` to replace it.
- `className` can be `null` for anonymous/hidden classes — always null-check it.

**What actually loaded for `App` (12 classes total):**
```
#1  jdk.internal.vm.PostVMInitHook        — JVM startup hook
#2  jdk.internal.vm.PostVMInitHook$2      — inner class of above
#3  jdk.internal.util.EnvUtils            — env setup
#4  jdk.internal.vm.PostVMInitHook$1      — another inner class
#5  sun.launcher.LauncherHelper           — parses the command line, finds main()
#6  java.nio.charset.CharsetDecoder       — needed for stdout encoding
#7  sun.nio.cs.ArrayDecoder
#8  sun.nio.cs.SingleByte$Decoder
#9  java.util.concurrent.ConcurrentHashMap$ForwardingNode
#10 App                                   — OUR class, loaded last before main()
#11 java.lang.Shutdown                    — loaded after main() returns
#12 java.lang.Shutdown$Lock
```

**Surprises:**
- Only 12 classes in a trivial app. Most JDK classes (`String`, `Object`,
  `System`, `ArrayList`) loaded *before* the agent was registered — they are
  bootstrapped by the JVM itself before `premain` even fires. The transformer
  never sees them. This is expected and fine for our use case (we want app
  classes, not JDK internals).
- `App` is class #10, not #1. Five classes load just to parse the command line
  and find `main()` before our app code gets a turn.
- `Shutdown` loads *after* `main()` returns — the transformer fires all the way
  to JVM teardown, not just during startup.
- `ArrayList` and `String` did NOT appear, confirming they bootstrapped before
  the agent. If you need to instrument JDK classes you must use
  `inst.addTransformer(t, true)` + `inst.retransformClasses()` — a Phase 8 topic.

**The key confirmation:** `[profiler] agent loaded` prints before `[app] main() starting`.
The transformer is in place before any app code runs.

---

---

## Phase 2 — ASM visitor model

**The visitor chain:** `ClassReader` → `ClassVisitor` → `MethodVisitor`

```
byte[]  →  ClassReader.accept(ClassVisitor)
                │
                ├── visitClass(...)         — class-level metadata
                ├── visitField(...)         — one call per field
                └── visitMethod(...)        — one call per method
                         │
                         └── returns a MethodVisitor (or null to skip the body)
                                  │
                                  ├── visitCode()
                                  ├── visitInsn(opcode)        — zero-operand instructions
                                  ├── visitVarInsn(op, slot)   — iload/istore/aload/...
                                  ├── visitMethodInsn(...)     — invokevirtual/static/...
                                  └── visitMaxs(maxStack, maxLocals)
```

Each callback maps to a `javap` section you've already seen. `visitMethod` is
called once per method definition (not per call site). If you return `null` from
`visitMethod`, ASM skips the method body entirely — use this when you only need
the signature, not the instructions.

**`ClassReader` flags:**  
- `SKIP_CODE` — don't call any `visitInsn`-family methods (skips instruction stream)
- `SKIP_FRAMES` — don't parse stack map frames (safe to skip when read-only)
- `SKIP_DEBUG` — skip line numbers and local variable names

In Phase 2 we used `SKIP_CODE | SKIP_FRAMES` since we only need method signatures.
In Phase 3 we drop those flags to see (and modify) individual instructions.

**`access` flags — the bitmask:**
```java
boolean isStatic  = (access & Opcodes.ACC_STATIC)  != 0;
boolean isPublic  = (access & Opcodes.ACC_PUBLIC)   != 0;
boolean isFinal   = (access & Opcodes.ACC_FINAL)    != 0;
boolean isNative  = (access & Opcodes.ACC_NATIVE)   != 0;
```
The static/instance distinction controls slot numbering (ADR 001 + Phase 0 notes).
A static method's first parameter is slot 0; an instance method's first parameter
is slot 1 (slot 0 = `this`). Getting this wrong in Phase 3 causes a `VerifyError`.

**Reading a method descriptor:**
```
([Ljava/lang/String;)V
 ^                  ^
 |                  return type: V = void
 parameter: [ = array, Ljava/lang/String; = String reference

(II)I           two ints in, one int out
()J             no params, returns long
(Ljava/lang/String;I)Z   String + int in, boolean out
```

**Output from Phase 2 against App:**
```
[profiler] found  App#main   descriptor=([Ljava/lang/String;)V   static=true
```
`main` is static (no `this`), takes a `String[]`, returns void. Confirmed.

**Fat jar — why it's needed:**  
The agent's `ClassFileTransformer` runs in the bootstrap classloader context.
It cannot see jars on the application `-cp`. ASM must be bundled inside
`profiler.jar` itself. The build script extracts ASM's `.class` files into
`src/out/` before packaging, so they land in the same jar as `HelloAgent.class`.

---

## Summary — things to know cold before Phase 1

| Concept | Key point |
|---|---|
| Slot 0 | Always `this` for instance methods; params start at slot 1 |
| Type prefix | `i`=int `a`=ref `l`=long `d`=double `f`=float |
| Condition inversion | Compiler flips conditions; true branch falls through |
| `iinc` | Increments a local slot in-place, no stack involvement |
| Offsets = bytes | Multi-byte instructions leave gaps in offset sequence |
| `invokedynamic` | String concat (Java 9+) is NOT `StringBuilder` in bytecode |
| `finally` duplication | Finally body is copied to each exit path by the compiler |
| Exception table | Separate from the instruction stream; maps ranges to handlers |
| Void return | Opcode is `return` with no prefix |
| `invokespecial` | Constructors and `super` calls — no virtual dispatch |

---

## Phase 3 — Injecting timing into one method

**The ClassWriter chain (modifying, not just reading):**

```
byte[]  →  ClassReader
                │
                └── accept(TimingClassVisitor wrapping ClassWriter)
                             │
                             └── visitMethod("doWork") → TimingMethodVisitor
                                          │
                                          ├── visitCode()    → inject LSTORE startTime
                                          ├── visitInsn(...) → on RETURN, inject timing print
                                          └── all other visits pass through to ClassWriter
                                                   │
                                                   ▼
                                              ClassWriter.toByteArray() → new byte[]
```

The key difference from Phases 1–2: `transform()` now returns `cw.toByteArray()` instead
of `null`. The JVM loads our modified bytes, not the originals.

**Two-pass slot calculation:**

The target method's existing locals occupy slots 0..maxLocals-1. We must not
write into those slots — that would corrupt the method's own variables.

Pass 1 (read-only): visit the method, grab `maxLocals` from `visitMaxs(maxStack, maxLocals)`.  
Pass 2 (modify): start our injected locals at slot `maxLocals`.

For `doWork()` (static, `long sum` at 0-1, `int i` at 2): `maxLocals = 3`.  
Our `startTime` (long) → slots 3-4. Our `elapsed` (long) → slots 5-6.

**Why COMPUTE_FRAMES:**  
`doWork()` has a loop (a back-jump), so the class file contains a `StackMapFrame`
at the loop header. After inserting instructions, that frame's local variable
count is stale. `COMPUTE_FRAMES` throws away all original frames and regenerates
them via dataflow analysis — the only safe option when inserting locals into a
method that has branching.

**Instructions emitted at entry (`visitCode`):**
```
INVOKESTATIC java/lang/System.nanoTime ()J   ← pushes long onto stack
LSTORE 3                                      ← pops long → slot 3-4 (startTime)
```

**Instructions emitted at exit (before each `RETURN`):**
```
INVOKESTATIC java/lang/System.nanoTime ()J   ← pushes endTime
LLOAD 3                                       ← pushes startTime
LSUB                                          ← pops both, pushes elapsed
LSTORE 5                                      ← pops elapsed → slot 5-6
GETSTATIC java/lang/System.out               ← pushes PrintStream
NEW java/lang/StringBuilder                   ← pushes uninitialised SB ref
DUP                                           ← duplicates ref (needed for <init>)
LDC "[profiler] App#doWork took "            ← pushes String
INVOKESPECIAL StringBuilder.<init> (String)V ← consumes ref + String, initialises
LLOAD 5                                       ← pushes elapsed
INVOKEVIRTUAL StringBuilder.append (J)SB     ← appends long, returns SB
LDC " ns"
INVOKEVIRTUAL StringBuilder.append (String)SB
INVOKEVIRTUAL StringBuilder.toString ()String
INVOKEVIRTUAL PrintStream.println (String)V  ← prints, returns void
```

**The `DUP` before `INVOKESPECIAL <init>` is mandatory.**  
`INVOKESPECIAL <init>` consumes the reference on the stack (to initialise the
object) but does NOT push a result. If you didn't `DUP` first, after `<init>`
you'd have no reference left to call `append` on. This is a stack discipline
rule specific to constructors — one of the things ASM does for you automatically
if you use higher-level APIs, but must be done manually here.

**Result:**
```
[profiler] doWork original maxLocals=3 — injecting startTime at slot 3
[app] doWork sum=49999995000000
[profiler] App#doWork took 8199800 ns   (~8.2 ms)
```

**Known gap:** if `doWork()` exits by throwing an exception, `visitInsn(RETURN)`
never fires. The timer silently produces no output. Fixed in Phase 4 with a
bytecode-level try/finally handler (`visitTryCatchBlock`). This is exactly the
`finally` duplication pattern from Phase 0.
