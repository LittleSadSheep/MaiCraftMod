// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.ability.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.maiwithu.maicraft.ability.machine.spi.Installation;
import org.maiwithu.maicraft.ability.machine.spi.MachineCopy;
import org.maiwithu.maicraft.ability.machine.spi.MachineRole;
import org.maiwithu.maicraft.ability.machine.spi.MachineType;
import org.maiwithu.maicraft.ability.machine.spi.PartCell;
import org.maiwithu.maicraft.behavior.construction.CellKind;

/** 读现场抄机器蓝图：照抄方块去掉运行态、水源写倒桶、流水不写、没加载的不按空气、有自己抄法的机器照它写。 */
class MachineSiteCopyTest {

    private static final BlockPos ANCHOR = new BlockPos(10, 64, 10);

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void 照抄方块_摆放属性写进去_运行态不写_空气不写() {
        Map<BlockPos, BlockState> world = new HashMap<>();
        // 一台点着的熔炉朝东：facing 是摆放，lit 是运行态。
        world.put(ANCHOR, Blocks.FURNACE.defaultBlockState()
                .setValue(AbstractFurnaceBlock.FACING, Direction.EAST).setValue(AbstractFurnaceBlock.LIT, true));
        world.put(ANCHOR.above(), Blocks.STONE.defaultBlockState());
        MachineSiteCopy.Result result = MachineSiteCopy.copy(ANCHOR, ANCHOR, ANCHOR.offset(1, 1, 0),
                at -> Optional.of(world.getOrDefault(at, Blocks.AIR.defaultBlockState())), state -> null);
        assertEquals(2, result.blueprint().cells().size(), "两格空气不写");
        assertEquals(List.of(2, 2, 1), List.of(result.size().getX(), result.size().getY(), result.size().getZ()));
        JsonObject furnace = result.json().getAsJsonArray("cells").get(0).getAsJsonObject();
        assertEquals("minecraft:furnace", furnace.get("block").getAsString());
        assertEquals("east", furnace.getAsJsonObject("properties").get("facing").getAsString());
        assertFalse(furnace.getAsJsonObject("properties").has("lit"), "亮灭是运行态，不写");
        assertEquals(Set.of("facing"), result.blueprint().cells().getFirst().required());
        assertEquals(0, result.unread());
    }

    @Test
    void 水源写成倒桶_流动的水不写_没加载的格记数不按空气() {
        Map<BlockPos, BlockState> world = new HashMap<>();
        world.put(ANCHOR, Blocks.WATER.defaultBlockState());
        world.put(ANCHOR.east(), Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3));
        BlockPos unloaded = ANCHOR.east(2);
        MachineSiteCopy.Result result = MachineSiteCopy.copy(ANCHOR, ANCHOR, unloaded,
                at -> at.equals(unloaded) ? Optional.empty() : Optional.of(world.getOrDefault(at, Blocks.AIR.defaultBlockState())),
                state -> null);
        assertEquals(1, result.blueprint().cells().size());
        assertEquals(CellKind.FLUID_SOURCE, result.blueprint().cells().getFirst().kind());
        JsonObject water = result.json().getAsJsonArray("cells").get(0).getAsJsonObject();
        assertEquals("minecraft:water", water.get("block").getAsString());
        assertFalse(water.has("properties"), "水源不写 level");
        assertEquals(1, result.flowing());
        assertEquals(1, result.unread());
    }

    @Test
    void 有自己抄法的机器照它写_安装段与部件换成相对锚点() {
        MachineTestDoubles.FakeMachineType belts = new MachineTestDoubles.FakeMachineType("machinetest:belt", "测试带子",
                MachineRole.LOGISTICS, state -> state.is(Blocks.FURNACE));
        BlockPos first = ANCHOR;
        BlockPos second = ANCHOR.east();
        // 两格都归带子：两头写成原木（替身里的轴），整条写成一段安装段，再在第二格挂一个部件。
        belts.copier = cells -> Optional.of(new MachineCopy(Map.of(first, Blocks.OAK_LOG.defaultBlockState()),
                List.of(new Installation("machinetest:belt", List.of(first, second))),
                List.of(new PartCell(second, Direction.UP, "minecraft:stone_button")), List.of("带子抄成没包壳的")));
        Map<BlockPos, BlockState> world = new HashMap<>();
        world.put(first, Blocks.FURNACE.defaultBlockState());
        world.put(second, Blocks.FURNACE.defaultBlockState());
        world.put(ANCHOR.above(), Blocks.STONE.defaultBlockState());
        MachineType type = belts;
        MachineSiteCopy.Result result = MachineSiteCopy.copy(ANCHOR, ANCHOR, ANCHOR.offset(1, 1, 0),
                at -> Optional.of(world.getOrDefault(at, Blocks.AIR.defaultBlockState())),
                state -> state.is(Blocks.FURNACE) ? type : null);
        // 带子格里只有写了方块的那一格进逐格清单，另一格交给安装段；没认领的石头照抄。
        assertEquals(2, result.blueprint().cells().size());
        assertTrue(result.blueprint().cellAt(BlockPos.ZERO).orElseThrow().state().is(Blocks.OAK_LOG));
        assertTrue(result.blueprint().cellAt(new BlockPos(1, 0, 0)).isEmpty());
        assertEquals(List.of(BlockPos.ZERO, new BlockPos(1, 0, 0)), result.blueprint().installations().getFirst().offsets());
        assertEquals(new BlockPos(1, 0, 0), result.blueprint().parts().getFirst().offset());
        assertEquals(List.of("带子抄成没包壳的"), result.notes());
    }

    @Test
    void 抄出来的正文能原样喂回机器蓝图解析() {
        MachineTestDoubles.FakeMachineType arms = new MachineTestDoubles.FakeMachineType("machinetest:arm", "测试臂",
                MachineRole.LOGISTICS, state -> state.is(Blocks.FURNACE));
        arms.copier = cells -> Optional.of(new MachineCopy(Map.of(),
                List.of(new Installation("machinetest:arm", List.of(ANCHOR, ANCHOR.east()), List.of("arm", "take"))),
                List.of(), List.of()));
        Map<BlockPos, BlockState> world = new HashMap<>();
        world.put(ANCHOR, Blocks.FURNACE.defaultBlockState());
        world.put(ANCHOR.east(), Blocks.CHEST.defaultBlockState());
        world.put(ANCHOR.below(), Blocks.WATER.defaultBlockState());
        MachineType type = arms;
        MachineSiteCopy.Result result = MachineSiteCopy.copy(ANCHOR.below(), ANCHOR.below(), ANCHOR.east(),
                at -> Optional.of(world.getOrDefault(at, Blocks.AIR.defaultBlockState())),
                state -> state.is(Blocks.FURNACE) ? type : null);
        MachineBlueprint parsed = MachineBlueprints.parse(result.json());
        assertEquals(result.blueprint().cells().size(), parsed.cells().size());
        assertEquals(result.blueprint().installations(), parsed.installations());
        for (var cell : result.blueprint().cells()) {
            var back = parsed.cellAt(cell.pos()).orElseThrow();
            assertEquals(cell.state(), back.state(), "抄出来的状态读回来一样：" + cell.pos());
            assertEquals(cell.kind(), back.kind());
        }
    }
}
