public class Calculator {

    /** Returns the sum of a and b. */
    public static int add(int a, int b) {
        return a - b; // BUG: should be a + b
    }
}
