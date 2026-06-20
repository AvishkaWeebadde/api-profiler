import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class HelloAgent {

    public static void premain(String args, Instrumentation inst) {
        // args come from -javaagent:profiler.jar=<prefix>
        // Use dots in the arg (e.g. "com.example") — we convert to slashes internally
        String targetPrefix = (args != null && !args.isBlank())
                ? args.replace('.', '/')
                : "App";  // default: match the demo app

        System.out.println("[profiler] agent loaded — scanning classes under: " + targetPrefix);
        inst.addTransformer(new MethodScanner(targetPrefix));
    }

    static class MethodScanner implements ClassFileTransformer {

        private final String targetPrefix;

        MethodScanner(String targetPrefix) {
            this.targetPrefix = targetPrefix;
        }

        @Override
        public byte[] transform(
                ClassLoader loader,
                String className,
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer) {

            if (className == null) return null;
            if (!className.startsWith(targetPrefix)) return null;

            // Hand the raw class bytes to ASM
            ClassReader cr = new ClassReader(classfileBuffer);
            cr.accept(new MethodPrinter(className), ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
            return null; // bytecode unchanged
        }
    }

    static class MethodPrinter extends ClassVisitor {

        private final String className;

        MethodPrinter(String className) {
            super(Opcodes.ASM9);
            this.className = className.replace('/', '.');
        }

        @Override
        public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions) {

            // Skip constructors and static initialisers — we can't time them usefully yet
            if (name.equals("<init>") || name.equals("<clinit>")) return null;

            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;

            System.out.printf("[profiler] found  %-40s  descriptor=%-20s  static=%s%n",
                    className + "#" + name, descriptor, isStatic);

            // Return null — we don't need to inspect the method body yet
            return null;
        }
    }
}
