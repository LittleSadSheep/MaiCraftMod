package org.maiwithu.maicraft.entity;

/** 在水中 Shift 用于下潜，在陆地上则用于慢速潜行。 */
public final class InputDriverTest {
    public static void main(String[] args) {
        check(InputDriver.permitsSprint(true, true, true), "descent must preserve swimming sprint");
        check(!InputDriver.permitsSprint(true, true, false), "grounded sneaking must not sprint");
        check(InputDriver.permitsSprint(true, false, false), "ordinary running");
        check(!InputDriver.permitsSprint(false, true, true), "refill can explicitly stop sprinting");
        System.out.println("InputDriverTest: passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
