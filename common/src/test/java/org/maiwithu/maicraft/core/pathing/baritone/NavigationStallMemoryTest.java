// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

// 同一面前格卡住：第一次先切门或重试，第二次列为障碍并撤销切门登记；障碍数有上限，面前格不取脚下。
public final class NavigationStallMemoryTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        retryThenObstacle();
        toggleDoorThenObstacle();
        boundedObstacles();
        frontAndPassageGeometry();
        System.out.println("NavigationStallMemoryTest: passed");
    }

    // 没有门的卡点：第一次原样重试，第二次才列障碍，回执写明卡住次数和现场。
    private static void retryThenObstacle() {
        var memory = new NavigationStallMemory();
        BlockPos front = new BlockPos(3, 64, 0);
        expect(memory.observe(front, null, false, Map.of("cause", "timeout")) == NavigationStallMemory.Decision.RETRY,
                "first stall without a passage must retry");
        expect(memory.obstacles().isEmpty() && memory.isEmpty(), "a retry must not exclude any cell");
        expect(memory.observe(front, null, false, Map.of("cause", "timeout")) == NavigationStallMemory.Decision.OBSTACLE,
                "second stall at the same cell must exclude it");
        expect(memory.obstacles().contains(front.asLong()), "excluded cell must join the forbidden set");
        // 已列障碍、新策略尚未装上时再报同一格，不重复计数也不消耗上限。
        expect(memory.observe(front, null, false, Map.of()) == NavigationStallMemory.Decision.RETRY,
                "an already excluded cell waits for the replan");
        var facts = new LinkedHashMap<String, Object>();
        memory.describeInto(facts);
        var obstacle = ((List<?>) facts.get("stuck_obstacles")).getFirst();
        expect(obstacle.equals(Map.of("cell", "3, 64, 0", "stalls", 2, "cause", "timeout")),
                "receipt must carry the cell, stall count and scene: " + obstacle);
    }

    // 卡在门前：第一次登记切到相反开关状态，换一格卡住不重复登记同一扇门；同格再卡就撤销登记、列为障碍。
    private static void toggleDoorThenObstacle() {
        var memory = new NavigationStallMemory();
        BlockPos front = new BlockPos(0, 64, 1), door = new BlockPos(0, 64, 1);
        expect(memory.observe(front, door, true, Map.of()) == NavigationStallMemory.Decision.TOGGLE_PASSAGE,
                "first stall at a door must toggle it");
        expect(Boolean.TRUE.equals(memory.wantOpen(door)), "the door must be registered to open");
        expect(memory.observe(new BlockPos(0, 64, 2), door, false, Map.of()) == NavigationStallMemory.Decision.RETRY,
                "an already registered door must not be toggled again for another cell");
        expect(memory.observe(front, door, true, Map.of()) == NavigationStallMemory.Decision.OBSTACLE,
                "a door that still blocks after toggling becomes an obstacle");
        expect(memory.wantOpen(door) == null, "the toggle must be withdrawn once the cell is excluded");
        var facts = new LinkedHashMap<String, Object>();
        memory.describeInto(facts);
        var toggle = (Map<?, ?>) ((List<?>) facts.get("passage_toggles")).getFirst();
        expect("withdrawn_after_obstacle".equals(toggle.get("status")) && Boolean.TRUE.equals(toggle.get("want_open")),
                "receipt must show the withdrawn toggle: " + toggle);
    }

    // 每次导航最多学八个障碍格，第九个直接交给导航如实失败。
    private static void boundedObstacles() {
        var memory = new NavigationStallMemory();
        for (int i = 0; i < NavigationStallMemory.MAX_OBSTACLES; i++) {
            BlockPos cell = new BlockPos(i, 64, 5);
            memory.observe(cell, null, false, Map.of());
            expect(memory.observe(cell, null, false, Map.of()) == NavigationStallMemory.Decision.OBSTACLE, "obstacle " + i);
        }
        BlockPos extra = new BlockPos(20, 64, 5);
        memory.observe(extra, null, false, Map.of());
        expect(memory.observe(extra, null, false, Map.of()) == NavigationStallMemory.Decision.EXHAUSTED,
                "obstacles beyond the cap must end the navigation");
        expect(memory.obstacleCells().size() == NavigationStallMemory.MAX_OBSTACLES, "cap must hold");
    }

    // 长直线的面前格取前进方向的下一格而不是远处终点；终点在脚下时没有面前格。门上下两半登记到同一格，铜门可徒手开。
    private static void frontAndPassageGeometry() {
        BlockPos feet = new BlockPos(0, 64, 0);
        expect(new BlockPos(1, 64, 0).equals(MovementStall.straightFront(new Vec3(0.5, 64, 0.5), new Vec3(30.5, 64, 0.5), 64, feet)),
                "straight front must be the next cell along the line");
        expect(new BlockPos(0, 64, -1).equals(MovementStall.straightFront(new Vec3(0.5, 64, 0.2), new Vec3(0.5, 64, -9.5), 64, feet)),
                "front follows the travel direction");
        expect(MovementStall.straightFront(new Vec3(0.5, 64, 0.5), new Vec3(0.7, 64, 0.6), 64, feet) == null,
                "a target inside the feet column has no front cell");
        var upper = Blocks.COPPER_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        expect(MovementStall.passageKey(new BlockPos(4, 65, 4), upper).equals(new BlockPos(4, 64, 4)),
                "both door halves share the lower-half key");
        expect(MovementStall.passageKey(new BlockPos(4, 64, 4), Blocks.OAK_FENCE_GATE.defaultBlockState())
                .equals(new BlockPos(4, 64, 4)), "a fence gate keys itself");
        expect(EmbeddedBaritoneActionBridge.isHandOpenable(upper), "copper doors open by hand");
        expect(!EmbeddedBaritoneActionBridge.isHandOpenable(Blocks.IRON_DOOR.defaultBlockState()),
                "iron doors are never toggled by hand");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
