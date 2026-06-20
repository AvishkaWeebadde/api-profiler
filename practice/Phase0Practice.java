package practice;

public class Phase0Practice {

    // 1. Baseline — loads, iadd, return
    public int add(int a, int b) {
        return a + b;
    }

    // 2. For loop — jumps/labels
    public int sumTo(int n) {
        int sum = 0;
        for (int i = 1; i <= n; i++) {
            sum += i;
        }
        return sum;
    }

    // 3. If/else — branching
    public int sign(int x) {
        if (x > 0) {
            return 1;
        } else {
            return -1;
        }
    }

    // 4. Method calling another — invokevirtual + descriptors
    public int doubled(int a) {
        return add(a, a);
    }

    // 5. String concatenation — StringBuilder/invokedynamic surprise
    public String greet(String name) {
        return "hi " + name;
    }

    // 6. Try/finally — exception table surprise
    public int withFinally(int x) {
        try {
            return x * 2;
        } finally {
            System.out.println("done");
        }
    }
}
