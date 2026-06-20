import java.util.ArrayList;
import java.util.List;

public class App {
    public static void main(String[] args) {
        System.out.println("[app] main() starting");
        doWork();
        System.out.println("[app] main() done");
    }

    // Phase 3 target — static, void, gives us a meaningful elapsed time to observe
    static void doWork() {
        long sum = 0;
        for (int i = 0; i < 10_000_000; i++) {
            sum += i;
        }
        System.out.println("[app] doWork sum=" + sum);
    }
}
