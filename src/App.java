import java.util.ArrayList;
import java.util.List;

public class App {
    public static void main(String[] args) {
        System.out.println("[app] main() starting");

        // Use ArrayList and String.format so we see those classes load
        List<String> items = new ArrayList<>();
        items.add("alpha");
        items.add("beta");
        for (String item : items) {
            System.out.println("[app] item: " + item);
        }

        System.out.println("[app] main() done");
    }
}
