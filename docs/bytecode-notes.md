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
