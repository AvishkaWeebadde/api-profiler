import java.lang.instrument.Instrumentation;

public class HelloAgent
{
    public static void premain(String args, Instrumentation inst) {
        System.out.println("====================================");
        System.out.println("  [profiler] I'm alive! Agent loaded.");
        System.out.println("====================================");
    }
}
