// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.navigation.baritone;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 同一面前格卡住：第一次先切门或重试，第二次列为障碍并撤销切门登记；障碍数有上限，面前格不取脚下。
 */
class NavigationStallMemoryTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // 没有门挡路时：第一次原样重试，第二次才列障碍，诊断写明卡住次数和现场。
    @Test
    void retryThenObstacle() {
        var memory = new NavigationStallMemory();
        BlockPos front = new BlockPos(3, 64, 0);
        assertEquals(NavigationStallMemory.Decision.RETRY,
                memory.observe(front, null, false, Map.of("cause", "timeout")),
                "first stall without a passage must retry");
        assertTrue(memory.obstacles().isEmpty() && memory.isEmpty(), "a retry must not exclude any cell");
        assertEquals(NavigationStallMemory.Decision.OBSTACLE,
                memory.observe(front, null, false, Map.of("cause", "timeout")),
                "second stall at the same cell must exclude it");
        assertTrue(memory.obstacles().contains(front.asLong()), "excluded cell must join the forbidden set");
        // 已列障碍、新策略尚未装上时再报同一格，不重复计数也不消耗上限。
        assertEquals(NavigationStallMemory.Decision.RETRY,
                memory.observe(front, null, false, Map.of()),
                "an already excluded cell waits for the replan");
        var facts = new LinkedHashMap<String, Object>();
        memory.describeInto(facts);
        var obstacle = ((List<?>) facts.get("stuck_obstacles")).getFirst();
        assertEquals(Map.of("cell", "3, 64, 0", "stalls", 2, "cause", "timeout"), obstacle,
                "diagnosis must carry the cell, stall count and scene: " + obstacle);
    }

    // 卡在门前：第一次登记切到相反开关状态，换一格卡住不重复登记同一扇门；同格再卡就撤销登记、列为障碍。
    @Test
    void toggleDoorThenObstacle() {
        var memory = new NavigationStallMemory();
        BlockPos front = new BlockPos(0, 64, 1), door = new BlockPos(0, 64, 1);
        assertEquals(NavigationStallMemory.Decision.TOGGLE_PASSAGE,
                memory.observe(front, door, true, Map.of()), "first stall at a door must toggle it");
        assertEquals(Boolean.TRUE, memory.wantOpen(door), "the door must be registered to open");
        assertEquals(NavigationStallMemory.Decision.RETRY,
                memory.observe(new BlockPos(0, 64, 2), door, false, Map.of()),
                "an already registered door must not be toggled again for another cell");
        assertEquals(NavigationStallMemory.Decision.OBSTACLE,
                memory.observe(front, door, true, Map.of()),
                "a door that still blocks after toggling becomes an obstacle");
        assertEquals(null, memory.wantOpen(door), "the toggle must be withdrawn once the cell is excluded");
        var facts = new LinkedHashMap<String, Object>();
        memory.describeInto(facts);
        var toggle = (Map<?, ?>) ((List<?>) facts.get("passage_toggles")).getFirst();
        assertTrue("withdrawn_after_obstacle".equals(toggle.get("status")) && Boolean.TRUE.equals(toggle.get("want_open")),
                "diagnosis must show the withdrawn toggle: " + toggle);
    }

    // 每次导航最多学八个障碍格，第九个直接交给导航如实失败。
    @Test
    void boundedObstacles() {
        var memory = new NavigationStallMemory();
        for (int i = 0; i < NavigationStallMemory.MAX_OBSTACLES; i++) {
            BlockPos cell = new BlockPos(i, 64, 5);
            memory.observe(cell, null, false, Map.of());
            assertEquals(NavigationStallMemory.Decision.OBSTACLE,
                    memory.observe(cell, null, false, Map.of()), "obstacle " + i);
        }
        BlockPos extra = new BlockPos(20, 64, 5);
        memory.observe(extra, null, false, Map.of());
        assertEquals(NavigationStallMemory.Decision.EXHAUSTED,
                memory.observe(extra, null, false, Map.of()),
                "obstacles beyond the cap must end the navigation");
        assertEquals(NavigationStallMemory.MAX_OBSTACLES, memory.obstacleCells().size(), "cap must hold");
    }
}
