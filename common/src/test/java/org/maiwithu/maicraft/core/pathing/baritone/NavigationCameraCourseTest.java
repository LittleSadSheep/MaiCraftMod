package org.maiwithu.maicraft.core.pathing.baritone;

public final class NavigationCameraCourseTest {
    public static void main(String[] args) {
        var course = new NavigationCameraCourse();
        course.target(0, 0);
        check(course.target(90, 0) == 90, "only the final first-tick request establishes the initial course");
        course.reset();
        course.target(30, 0);
        check(course.target(-30, 0) == -30, "reset within the same revision still replaces the initial course");
        course.reset();
        check(course.target(0, 1) == 0, "first course");
        check(course.target(90, 2) == 9, "turn budget per tick");
        for (int i = 0; i < 12; i++) check(course.target(90, 2) == 9, "movement handoffs cannot multiply the turn rate");
        check(course.target(-90, 2) == -9, "the final target replaces an intermediate completed movement target");
        check(course.target(-90, 3) == -18, "a new tick advances once");
        course.reset();
        check(course.target(179, 4) == 179 && course.target(-179, 5) == 179,
                "wrapped nearby headings do not trigger a full turn");
        course.reset();
        course.target(0, 6);
        for (int tick = 7; tick < 50; tick++) {
            check(course.target(tick % 2 == 0 ? 10 : -10, tick) == 0,
                    "small bearing corrections do not shake the camera");
        }
        System.out.println("NavigationCameraCourseTest: passed");
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
