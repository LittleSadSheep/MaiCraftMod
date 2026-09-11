package org.maiwithu.maicraft.core.pathing.baritone;

public final class TravelJumpPhysicsTest {
    public static void main(String[] args) {
        var launch = new TravelJumpPhysics.Launch(.08, .42, .48, .13, .546, 1.8);
        var open = TravelJumpPhysics.project(launch, false);
        var low = TravelJumpPhysics.project(launch, true);
        check(open != null && open.airborneTicks() == 12, "ordinary vanilla hop lasts twelve travel ticks");
        check(low != null && low.airborneTicks() == 3, "ceiling collision still applies gravity on the first tick");
        check(Math.abs(low.apexHeight() - .2) < 1e-6, "two-block ceiling caps an adult player's rise");
        check(low.forwardDistance() < open.forwardDistance(), "low ceilings require a shorter landing corridor");
        // First grounded move: (.48 + .13) * .546, then airborne acceleration before moving.
        double first = .48 + .13, second = first * .546 + .026, third = second * .91 + .026;
        check(Math.abs(low.forwardDistance() - first - second - third) < 1e-9,
                "takeoff and airborne friction follow native travel order");
        check(TravelJumpPhysics.project(new TravelJumpPhysics.Launch(0, .42, .48, .13, .546, 1.8), false) == null,
                "zero gravity cannot promise a landing");
        check(TravelJumpPhysics.project(new TravelJumpPhysics.Launch(.0001, .42, .48, .13, .546, 1.8), false) == null,
                "unbounded flight cannot be admitted as a routine hop");
        check(TravelJumpPhysics.project(new TravelJumpPhysics.Launch(.08, Double.NaN, .48, .13, .546, 1.8), false) == null,
                "invalid attributes cannot authorize a jump");
        System.out.println("TravelJumpPhysicsTest: passed");
    }

    private static void check(boolean value, String detail) { if (!value) throw new AssertionError(detail); }
}
