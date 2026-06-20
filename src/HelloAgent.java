import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class HelloAgent {

    // Hard-coded target for Phase 3 — made config-driven in Phase 4
    static final String TARGET_CLASS  = "App";
    static final String TARGET_METHOD = "doWork";
    static final String TARGET_DESC   = "()V";

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[profiler] agent loaded — timing " + TARGET_CLASS + "#" + TARGET_METHOD);
        inst.addTransformer(new TimingTransformer());
    }

    // -------------------------------------------------------------------------

    static class TimingTransformer implements ClassFileTransformer {

        @Override
        public byte[] transform(
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer) {

            if (!TARGET_CLASS.equals(className)) return null;

            // Pass 1 — read-only: find out how many local variable slots the
            // target method already uses, so our injected locals don't collide.
            int startSlot = findMaxLocals(classfileBuffer);
            System.out.println("[profiler] " + TARGET_METHOD + " original maxLocals=" + startSlot
                    + " — injecting startTime at slot " + startSlot);

            // Pass 2 — modify: wrap ClassWriter in our visitor chain and return
            // the new byte[].  COMPUTE_FRAMES tells ASM to recalculate all
            // StackMapFrames from scratch so we don't corrupt the verifier.
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            cr.accept(new TimingClassVisitor(cw, startSlot), ClassReader.SKIP_FRAMES);
            return cw.toByteArray();
        }

        // Walk the class read-only and return maxLocals for our target method.
        private static int findMaxLocals(byte[] classfileBuffer) {
            int[] result = {0};
            new ClassReader(classfileBuffer).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name,
                        String descriptor, String signature, String[] exceptions) {
                    if (TARGET_METHOD.equals(name) && TARGET_DESC.equals(descriptor)) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitMaxs(int maxStack, int maxLocals) {
                                result[0] = maxLocals;
                            }
                        };
                    }
                    return null;
                }
            }, ClassReader.SKIP_FRAMES);
            return result[0];
        }
    }

    // -------------------------------------------------------------------------

    static class TimingClassVisitor extends ClassVisitor {

        private final int startSlot;

        TimingClassVisitor(ClassWriter cw, int startSlot) {
            super(Opcodes.ASM9, cw);
            this.startSlot = startSlot;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (TARGET_METHOD.equals(name) && TARGET_DESC.equals(descriptor)) {
                return new TimingMethodVisitor(mv, startSlot);
            }
            return mv;
        }
    }

    // -------------------------------------------------------------------------

    static class TimingMethodVisitor extends MethodVisitor {

        private final int startSlot;  // long startTime occupies slots [startSlot, startSlot+1]
        private final int elapsedSlot; // long elapsed   occupies slots [startSlot+2, startSlot+3]

        TimingMethodVisitor(MethodVisitor mv, int startSlot) {
            super(Opcodes.ASM9, mv);
            this.startSlot  = startSlot;
            this.elapsedSlot = startSlot + 2;
        }

        // Fires at the top of the method body — inject:  long startTime = System.nanoTime();
        @Override
        public void visitCode() {
            super.visitCode();
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(Opcodes.LSTORE, startSlot);
        }

        // Fires for every zero-operand instruction — intercept RETURN to inject
        // the exit timing before letting the return actually execute.
        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN) {
                injectExitTiming();
                // Known gap (see ADR 003): if the method exits via a thrown
                // exception, injectExitTiming() never fires. Fixed in Phase 4.
            }
            super.visitInsn(opcode); // pass the original instruction through
        }

        private void injectExitTiming() {
            // long elapsed = System.nanoTime() - startTime;
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "java/lang/System", "nanoTime", "()J", false);
            mv.visitVarInsn(Opcodes.LLOAD, startSlot);
            mv.visitInsn(Opcodes.LSUB);
            mv.visitVarInsn(Opcodes.LSTORE, elapsedSlot);

            // System.out.println("[profiler] App#doWork took X ns");
            mv.visitFieldInsn(Opcodes.GETSTATIC,
                    "java/lang/System", "out", "Ljava/io/PrintStream;");
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn("[profiler] " + TARGET_CLASS + "#" + TARGET_METHOD + " took ");
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                    "java/lang/StringBuilder", "<init>", "(Ljava/lang/String;)V", false);
            mv.visitVarInsn(Opcodes.LLOAD, elapsedSlot);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder", "append", "(J)Ljava/lang/StringBuilder;", false);
            mv.visitLdcInsn(" ns");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                    "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        }
    }
}
