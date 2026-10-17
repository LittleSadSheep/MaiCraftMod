// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.maiwithu.maicraft.behavior.construction.PlannedCell;

/** 机器补丁合并：新声明的格覆盖同位旧目标、范围外保留；部件按宿主加面、安装段按起始格、设置按格加键替换。 */
class MachinePatchTest {

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static MachineBlueprint blueprintOf(PlannedCell... cells) {
        return new MachineBlueprint(List.of(cells), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void 补丁覆盖同位旧目标_范围外保留() {
        MachineBlueprint existing = blueprintOf(
                PlannedCell.block(new BlockPos(0, 0, 0), Blocks.FURNACE.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState(), Set.of()));
        MachineBlueprint patch = blueprintOf(
                PlannedCell.block(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState(), Set.of()),
                PlannedCell.block(new BlockPos(2, 0, 0), Blocks.STONE.defaultBlockState(), Set.of()));
        MachineBlueprint merged = MachinePatch.merge(existing, patch);
        assertEquals(3, merged.cells().size());
        assertEquals(Blocks.STONE, merged.cellAt(new BlockPos(1, 0, 0)).orElseThrow().state().getBlock(),
                "同位的格以补丁为准");
        assertEquals(Blocks.FURNACE, merged.cellAt(new BlockPos(0, 0, 0)).orElseThrow().state().getBlock(),
                "补丁没提的格保留");
        assertTrue(merged.cellAt(new BlockPos(2, 0, 0)).isPresent(), "补丁新增的格并入");
    }

    @Test
    void 部件按宿主加面替换_安装段按起始格替换_设置按格加键替换() {
        MachineBlueprint existing = new MachineBlueprint(
                List.of(PlannedCell.block(new BlockPos(0, 0, 0), Blocks.FURNACE.defaultBlockState(), Set.of())),
                List.of(new MachineBlueprint.Part(new BlockPos(0, 0, 0), Direction.NORTH, "ae2:terminal"),
                        new MachineBlueprint.Part(new BlockPos(0, 0, 0), Direction.SOUTH, "ae2:terminal")),
                List.of(new MachineBlueprint.Segment("test:belt", List.of(new BlockPos(0, 1, 0), new BlockPos(0, 1, 1)))),
                List.of(new MachineBlueprint.Setting(new BlockPos(0, 0, 0), "side.north", "input"),
                        new MachineBlueprint.Setting(new BlockPos(0, 0, 0), "side.south", "output")),
                List.of());
        MachineBlueprint patch = new MachineBlueprint(
                List.of(),
                List.of(new MachineBlueprint.Part(new BlockPos(0, 0, 0), Direction.NORTH, "ae2:fluid_terminal")),
                List.of(new MachineBlueprint.Segment("test:belt", List.of(new BlockPos(0, 1, 0), new BlockPos(0, 1, 2)))),
                List.of(new MachineBlueprint.Setting(new BlockPos(0, 0, 0), "side.north", "output:items")),
                List.of());
        MachineBlueprint merged = MachinePatch.merge(existing, patch);
        assertEquals(2, merged.parts().size(), "北面的部件被替换，南面的保留");
        assertTrue(merged.parts().stream().anyMatch(part -> part.itemId().equals("ae2:fluid_terminal")));
        assertEquals(1, merged.installations().size());
        assertEquals(new BlockPos(0, 1, 2), merged.installations().get(0).offsets().get(1), "同起始格的段整段替换");
        assertEquals(2, merged.settings().size());
        assertTrue(merged.settings().stream().anyMatch(setting ->
                setting.key().equals("side.north") && setting.value().equals("output:items")));
    }

    @Test
    void 补丁什么都没改_按没变化对待() {
        MachineBlueprint existing = blueprintOf(
                PlannedCell.block(new BlockPos(0, 0, 0), Blocks.FURNACE.defaultBlockState(), Set.of()));
        assertFalse(MachinePatch.changed(existing, existing), "一样的蓝图没有变化");
        MachineBlueprint added = MachinePatch.merge(existing, blueprintOf(
                PlannedCell.block(new BlockPos(1, 0, 0), Blocks.STONE.defaultBlockState(), Set.of())));
        assertTrue(MachinePatch.changed(added, existing), "多了一格就是有变化");
    }
}
