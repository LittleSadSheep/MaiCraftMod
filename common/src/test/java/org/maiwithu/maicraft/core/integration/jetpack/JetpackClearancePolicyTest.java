// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Real body volumes and arbitrary collision boxes exercise the soft clearance preference. */
public final class JetpackClearancePolicyTest {
    private static final JetpackNativeAdapter.Snapshot POWER = new JetpackNativeAdapter.Snapshot(
            true, "fixture", "create_jetpack:netherite_jetpack", true, true,
            900, 17000, .016, .32, .6, -.03, .08);

    public static void main(String[] args) {
        doorwayCenter();
        plannedCenterline();
        narrowPassage();
        headroomAndShapes();
        requiredCorridor();
        fixedBudget();
        System.out.println("JetpackClearancePolicyTest: passed");
    }

    private static void doorwayCenter() {
        var door = portal(3, 4);
        Vec3 center = new Vec3(1.5, 0, .5), edge = new Vec3(.5, 0, .5);
        double middle = penalty(door, center), nearWall = penalty(door, edge);
        check(middle < nearWall, "wide doorway center must be preferred to the edge");
        check(2 + 5 * middle < 5 * nearWall,
                "five blocks along the wall must cost more than moving a block inward and back");
        double centralEdge = JetpackClearancePolicy.edgePenalty(door, center.add(0, 0, -1), center.add(0, 0, 1), POWER);
        double wallEdge = JetpackClearancePolicy.edgePenalty(door, edge.add(0, 0, -1), edge.add(0, 0, 1), POWER);
        check(centralEdge < wallEdge, "edge preference must include clearance inside the doorway");
    }

    private static void narrowPassage() {
        var narrow = portal(1, 4);
        Vec3 center = new Vec3(.5, 0, .5);
        check(Double.isFinite(penalty(narrow, center)), "a body-clear one-block opening must remain usable");
        check(Double.isFinite(JetpackClearancePolicy.edgePenalty(narrow,
                        center.add(0, 0, -1), center.add(0, 0, 1), POWER)),
                "optional side margins must not forbid the only narrow route");
    }

    private static void plannedCenterline() {
        var hallway = new ShapeSpace(List.of(new AABB(-10, 0, 0, 0, 8, 6), new AABB(3, 0, 0, 10, 8, 6)));
        var search = new JetpackRoute.Search(new Vec3(.5, 0, -2.5), new Vec3(.5, 0, 8.5), POWER);
        for (int i = 0; i < 500 && !search.done(); i++) search.advance(hallway, 128, Long.MAX_VALUE);
        check(search.result() != null, "the open-ended hallway must have a flight route");
        check(search.result().points().stream().anyMatch(p -> p.z >= 1 && p.z <= 5 && p.x == 1.5),
                "the planner must move toward the hallway center rather than fly along the near wall");
        var narrow = portal(1, 6);
        var narrowSearch = new JetpackRoute.Search(new Vec3(.5, 0, -2.5), new Vec3(.5, 0, 3.5), POWER);
        for (int i = 0; i < 500 && !narrowSearch.done(); i++) narrowSearch.advance(narrow, 128, Long.MAX_VALUE);
        check(narrowSearch.result() != null, "soft centering costs must not turn a one-block aperture into no_path");
    }

    private static void headroomAndShapes() {
        Vec3 point = new Vec3(1.5, 0, .5);
        check(penalty(portal(3, 3), point) > penalty(portal(3, 4), point),
                "a lower lintel above the required UP reserve should have a finite extra cost");
        // A protruding custom shape is represented by its actual bounds, with no block-name rule.
        var protrusion = new ShapeSpace(List.of(new AABB(1.9, .2, .2, 2.15, 2.8, .8)));
        var open = new ShapeSpace(List.of());
        check(Double.isFinite(penalty(protrusion, point)) && penalty(protrusion, point) > penalty(open, point),
                "a nearby non-cubic collision shape must influence clearance without blocking the current body");
        check(!Double.isFinite(penalty(protrusion, new Vec3(1.6, 0, .5))),
                "an overlapping custom collision shape must reject the required body volume");
    }

    private static void requiredCorridor() {
        Vec3 point = new Vec3(.5, 0, .5);
        var lowLintel = portal(1, 2.2);
        check(lowLintel.clear(point, point), "fixture body should fit below the lintel");
        check(!Double.isFinite(penalty(lowLintel, point)), "native UP reserve is a hard condition");
        var wall = new ShapeSpace(List.of(new AABB(-.2, 0, -.2, .2, 4, .2)));
        check(!Double.isFinite(JetpackClearancePolicy.edgePenalty(wall,
                        new Vec3(-1, 0, 0), new Vec3(1, 0, 0), POWER)),
                "soft midpoint scoring must not accept an obstructed edge");
    }

    private static void fixedBudget() {
        var open = new ShapeSpace(List.of());
        check(penalty(open, Vec3.ZERO) == 0, "open air must not add a clearance penalty");
        check(open.queries <= 12, "point scoring must use at most six body/UP probe pairs");
        open.queries = 0;
        JetpackClearancePolicy.edgePenalty(open, Vec3.ZERO, new Vec3(1, 0, 0), POWER);
        check(open.queries <= 14, "edge scoring must keep a fixed number of geometry probes");
    }

    private static double penalty(JetpackRoute.Space space, Vec3 point) {
        return JetpackClearancePolicy.clearancePenalty(space, point, POWER);
    }

    private static ShapeSpace portal(double width, double height) {
        return new ShapeSpace(List.of(new AABB(-10, -1, 0, 0, 10, 1),
                new AABB(width, -1, 0, 10, 10, 1), new AABB(0, height, 0, width, 10, 1)));
    }

    private static final class ShapeSpace implements JetpackRoute.Space {
        private final List<AABB> shapes;
        private int queries;
        private ShapeSpace(List<AABB> shapes) { this.shapes = List.copyOf(shapes); }
        public boolean clear(Vec3 from, Vec3 to) {
            queries++;
            int samples = Math.max(1, (int) Math.ceil(from.distanceTo(to) / .05));
            for (int i = 0; i <= samples; i++) {
                Vec3 p = from.lerp(to, (double) i / samples);
                var body = new AABB(p.x - .38, p.y + .001, p.z - .38, p.x + .38, p.y + 1.88, p.z + .38);
                if (shapes.stream().anyMatch(body::intersects)) return false;
            }
            return true;
        }
        public Vec3 landingBelow(Vec3 point) {
            Vec3 floor = new Vec3(point.x, 0, point.z);
            return point.y >= 0 && clear(point, floor) ? floor : null;
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
