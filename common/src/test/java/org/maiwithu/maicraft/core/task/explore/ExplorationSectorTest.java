package org.maiwithu.maicraft.core.task.explore;

/** 玩家指定北方跑图时，较近的南方群系与脚下群系都不能冒充目标。 */
public final class ExplorationSectorTest {
    public static void main(String[] args) {
        var north = ExplorationSector.of("north", 90, null, 128).at(10, 20, 120);
        check(north.accepts(42, -12), "include east edge of north sector");
        check(north.accepts(-22, -12), "include west edge of north sector");
        check(!north.accepts(43, -12), "exclude just beyond sector edge");
        check(!north.accepts(10, 40), "ignore nearer biome behind the player");
        check(!north.accepts(10, 19), "do not finish at the starting biome");
        check(north.contains(10, 19), "allow short internal steps before minimum target distance");
        var forward = ExplorationSector.of("forward", 60, 0, 128).at(0, 0, -90);
        check(forward.accepts(30, 0) && !forward.accepts(0, 30), "freeze relative heading at start");
        check(ExplorationSector.of("left", 90, 0, 128).at(0, 0, 0).accepts(30, 0), "left of south is east");
        check(ExplorationSector.of("northwest", 20, 0, 128).at(0, 0, 0).accepts(-30, -30), "diagonal bearing");
        check(ExplorationSector.of(null, null, null, 128).at(0, 0, 0).accepts(0, 0), "legacy all-direction search");
        rejects(() -> ExplorationSector.of("up", 90, 0, 128));
        rejects(() -> ExplorationSector.of(null, 90, 0, 128));
        rejects(() -> ExplorationSector.of("north", 0, 0, 128));
        rejects(() -> ExplorationSector.of("north", 90, 129, 128));
        System.out.println("ExplorationSectorTest: passed");
    }
    private static void rejects(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid direction contract was accepted");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
