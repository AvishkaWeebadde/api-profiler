import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public class HelloAgent {

    public static void premain(String args, Instrumentation inst) {
        System.out.println("[profiler] agent loaded — registering transformer");
        inst.addTransformer(new ClassNamePrinter());
        // premain returns here; JVM resumes normal class loading and calls main()
    }

    static class ClassNamePrinter implements ClassFileTransformer {

        private int count = 0;

        @Override
        public byte[] transform(
                ClassLoader loader,
                String className,        // internal form: java/lang/String
                Class<?> classBeingRedefined,
                ProtectionDomain protectionDomain,
                byte[] classfileBuffer) {

            count++;
            // className is null for anonymous/hidden classes — guard it
            String display = (className != null)
                    ? className.replace('/', '.')   // internal → source form
                    : "<anonymous>";

            // Highlight our own app class so it stands out in the scroll
            if ("App".equals(className)) {
                System.out.println("[profiler] >>> App loaded (#" + count + ") <<<");
            } else {
                System.out.println("[profiler] #" + count + " " + display);
            }

            // Returning null means "leave the bytecode unchanged"
            return null;
        }
    }
}
