// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.jetpack;

import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 检查三维找路、直接路线、封闭空间、途中障碍变化和预算耗尽；明确区分“还没搜完”和“已确认这次搜索没有通道”。
 */
public final class JetpackSearchTest {
    private static final JetpackNativeAdapter.Snapshot POWER = new JetpackNativeAdapter.Snapshot(
            true, "fixture", "create_jetpack:netherite_jetpack", true, true,
            900, 17000, .016, .32, .6, -.03, .08);
    private static final Vec3 START = new Vec3(.5, 0, .5), DECK = new Vec3(-12.5, 32, 21.5);

    public static void main(String[] args) {
        threeDimensionalPlateau();
        budgetIsNotNoCorridor();
        closedRoom();
        templateNeedsNoGridBudget();
        incrementalGeometry();
        System.out.println("JetpackSearchTest: passed");
    }

    private static void threeDimensionalPlateau() {
        var space = departureRoof();
        var search = new JetpackRoute.Search(START, DECK, POWER);
        finish(search, space);
        check(search.result() != null, "a nearby roof must allow lateral departure, climb and deck arrival");
        check(search.expanded() < 512, "equal-f three-dimensional volume must favor progress toward the goal");
        check(search.failureReason() == null, "successful search has no failure reason");
        check(search.result().points().getLast().equals(DECK), "route must reach the elevated deck");
        for (int i = 1; i < search.result().points().size(); i++) {
            Vec3 from = search.result().points().get(i - 1), to = search.result().points().get(i);
            boolean finalDescent = i == search.result().points().size() - 1;
            check(finalDescent ? space.clear(from, to) : JetpackRoute.flightClear(space, from, to, POWER),
                    "goal preference must preserve every swept body and native rise reserve");
        }
    }

    // 同一处可达场景只缩小搜索额度，结果应说明预算用完，不能冒充已经证明没有路。
    private static void budgetIsNotNoCorridor() {
        var space = departureRoof();
        var limited = new JetpackRoute.Search(START, DECK, POWER, 4);
        limited.advance(space, 0, Long.MAX_VALUE);
        limited.advance(space, 128, 0);
        check(space.queries == 0 && !limited.done(), "initialization must respect an exhausted tick budget too");
        finish(limited, space);
        check(limited.result() == null && "budget_exhausted".equals(limited.failureReason()),
                "an unfinished feasible frontier must report budget exhaustion, never no corridor");
        check(limited.expanded() == 4, "explicit total budget counts exactly the expanded nodes");
        int queries = space.queries;
        limited.advance(space, 128, Long.MAX_VALUE);
        check(space.queries == queries, "completed searches must stop querying the world");
    }

    private static void closedRoom() {
        Vec3 target = new Vec3(4.5, 0, .5);
        JetpackRoute.Space rooms = new JetpackRoute.Space() {
            public boolean clear(Vec3 from, Vec3 to) {
                // Two disconnected narrow rooms, both with a valid departure/arrival column.
                return inRoom(from, to, 0, 1) || inRoom(from, to, 4, 5);
            }
            private boolean inRoom(Vec3 from, Vec3 to, double low, double high) {
                return from.x >= low && from.x <= high && to.x >= low && to.x <= high
                        && Math.min(from.y, to.y) >= 0 && Math.max(from.y, to.y) <= 3
                        && Math.min(from.z, to.z) >= 0 && Math.max(from.z, to.z) <= 1;
            }
            public Vec3 landingBelow(Vec3 point) { return new Vec3(point.x, 0, point.z); }
        };
        var search = new JetpackRoute.Search(START, target, POWER, 100);
        finish(search, rooms);
        check(search.result() == null && "no_corridor".equals(search.failureReason()),
                "exhausting a closed room's frontier must report no corridor");
        check(search.expanded() < 100, "a closed finite room must finish before the search cap");
    }

    private static void templateNeedsNoGridBudget() {
        var open = new ShapeSpace(List.of());
        var direct = new JetpackRoute.Search(START, DECK, POWER, 0);
        finish(direct, open);
        check(direct.result() != null && direct.expanded() == 0,
                "a fully collision-verified climb/cruise/descent template needs no grid expansion");
        check(direct.result().points().size() == 4, "the stable direct template must remain available");
    }

    private static void incrementalGeometry() {
        var open = new ShapeSpace(List.of());
        var search = new JetpackRoute.Search(START, DECK, POWER);
        // Landing, approach and departure each consume an operation; no complete template yet.
        search.advance(open, 3, Long.MAX_VALUE);
        check(!search.done() && search.expanded() == 0, "initial queries must yield before full route validation");
        JetpackRoute.Space changed = new ShapeSpace(List.of()) {
            @Override public boolean clear(Vec3 from, Vec3 to) { return false; }
        };
        finish(search, changed);
        check(search.result() == null, "a route must not reuse a previous tick's clear geometry");
    }

    private static ShapeSpace departureRoof() {
        return new ShapeSpace(List.of(new AABB(-4, 4, -4, 5, 6, 5)));
    }

    private static void finish(JetpackRoute.Search search, JetpackRoute.Space space) {
        for (int i = 0; i < 800 && !search.done(); i++) {
            int before = search.expanded();
            search.advance(space, 16, Long.MAX_VALUE);
            check(search.expanded() - before <= 16, "each tick must obey its node/operation budget");
        }
        check(search.done(), "bounded search must terminate");
    }

    private static class ShapeSpace implements JetpackRoute.Space {
        private final List<AABB> obstacles;
        private int queries;
        ShapeSpace(List<AABB> obstacles) { this.obstacles = obstacles; }
        public boolean clear(Vec3 from, Vec3 to) {
            queries++;
            int samples = Math.max(1, (int) Math.ceil(from.distanceTo(to) / .1));
            for (int i = 0; i <= samples; i++) {
                Vec3 p = from.lerp(to, (double) i / samples);
                if (p.y < 0 || p.y > 50 || Math.abs(p.x) > 66 || Math.abs(p.z) > 66) return false;
                var body = new AABB(p.x - .38, p.y + .001, p.z - .38,
                        p.x + .38, p.y + 1.88, p.z + .38);
                if (obstacles.stream().anyMatch(body::intersects)) return false;
            }
            return true;
        }
        public Vec3 landingBelow(Vec3 point) {
            if (Math.abs(point.x - DECK.x) < .1 && Math.abs(point.z - DECK.z) < .1 && point.y >= DECK.y)
                return DECK;
            return point.y >= 0 ? new Vec3(point.x, 0, point.z) : null;
        }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
