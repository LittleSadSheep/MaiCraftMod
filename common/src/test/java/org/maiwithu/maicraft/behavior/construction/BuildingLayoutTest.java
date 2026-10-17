// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.behavior.construction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 建筑布局：一间五格见方的小木屋，地面、门、门洞、窗、床、梯子、箱子、屋里空地各按事实列出。 */
class BuildingLayoutTest {

    private static BlockState PLANK;
    private static BlockState AIR;

    @BeforeAll
    static void bootMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 方块注册表要等引导完才能碰：静态字段在这里才赋值。
        PLANK = Blocks.SPRUCE_PLANKS.defaultBlockState();
        AIR = Blocks.AIR.defaultBlockState();
    }

    /**
     * 小屋：x、z 从 0 到 4，地面 y=0，墙 y=1..3，屋顶 y=4。北墙（z=0）中间 x=2 装门（朝南），
     * 南墙（z=4）中间 x=2 留一个门洞，东墙（x=4）z=2 在 y=2 开一格窗洞，西墙（x=0）z=2 在 y=2 装一块玻璃。
     * 屋里东北角放床（床脚 (3,1,1) 朝南，床头 (3,1,2)），西北角放箱子，西南角立一根梯子。
     */
    private static Map<BlockPos, BlockState> cottage() {
        Map<BlockPos, BlockState> world = new HashMap<>();
        for (int x = -1; x <= 5; x++) {
            for (int z = -1; z <= 5; z++) {
                for (int y = 0; y <= 6; y++) {
                    boolean inside = x >= 0 && x <= 4 && z >= 0 && z <= 4;
                    boolean shell = x == 0 || x == 4 || z == 0 || z == 4;
                    BlockState state = AIR;
                    if (y == 0 && inside) state = PLANK;
                    if (y == 0 && !inside) state = Blocks.GRASS_BLOCK.defaultBlockState();
                    if (y >= 1 && y <= 3 && inside && shell) state = PLANK;
                    if (y == 4 && inside) state = PLANK;
                    world.put(new BlockPos(x, y, z), state);
                }
            }
        }
        BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        world.put(new BlockPos(2, 1, 0), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        world.put(new BlockPos(2, 2, 0), door.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        world.put(new BlockPos(2, 1, 4), AIR);
        world.put(new BlockPos(2, 2, 4), AIR);
        world.put(new BlockPos(4, 2, 2), AIR);
        world.put(new BlockPos(0, 2, 2), Blocks.GLASS.defaultBlockState());
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
        world.put(new BlockPos(3, 1, 1), bed.setValue(BlockStateProperties.BED_PART, BedPart.FOOT));
        world.put(new BlockPos(3, 1, 2), bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
        world.put(new BlockPos(1, 1, 1), Blocks.CHEST.defaultBlockState());
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        world.put(new BlockPos(1, 1, 3), ladder);
        world.put(new BlockPos(1, 2, 3), ladder);
        world.put(new BlockPos(1, 3, 3), ladder);
        return world;
    }

    private static BuildingLayout layoutOf(Map<BlockPos, BlockState> world) {
        return BuildingLayout.of(new BlockPos(-1, 0, -1), new BlockPos(5, 6, 5), world::get, world::containsKey,
                id -> id.equals("minecraft:chest"));
    }

    @Test
    void 地面在哪层_净高几格() {
        BuildingLayout layout = layoutOf(cottage());
        assertEquals(1, layout.floors().size(), "屋外没有顶，只有屋里这一层");
        BuildingLayout.Floor floor = layout.floors().getFirst();
        assertEquals(0, floor.y(), "地面方块在 y=0，人站在 y=1");
        assertEquals(3, floor.height(), "墙高三格，屋里净高 3");
    }

    @Test
    void 门_门洞_窗洞_窗() {
        BuildingLayout layout = layoutOf(cottage());
        assertEquals(1, layout.doors().size());
        BuildingLayout.Door door = layout.doors().getFirst();
        assertEquals(new BlockPos(2, 1, 0), door.at());
        assertEquals("南", door.facing());
        assertEquals("南", door.inside(), "门南边那一格有顶，是屋里");
        assertTrue(layout.openings().contains(new BuildingLayout.Opening(new BlockPos(2, 1, 4), "门洞", "东西向")), layout.openings().toString());
        assertTrue(layout.openings().contains(new BuildingLayout.Opening(new BlockPos(4, 2, 2), "窗洞", "南北向")), layout.openings().toString());
        assertEquals(List.of(new BlockPos(0, 2, 2)), layout.windows());
    }

    @Test
    void 床_梯子_箱子() {
        BuildingLayout layout = layoutOf(cottage());
        assertEquals(List.of(new BuildingLayout.Bed(new BlockPos(3, 1, 1), new BlockPos(3, 1, 2), "南")), layout.beds());
        assertEquals(1, layout.climbs().size());
        BuildingLayout.Climb ladder = layout.climbs().getFirst();
        assertEquals("梯子", ladder.kind());
        assertEquals(3, ladder.cells());
        assertEquals(List.of(0), ladder.floors(), "梯子挨着屋里那一层地面");
        assertEquals(List.of(new BuildingLayout.Facility(new BlockPos(1, 1, 1), "minecraft:chest")), layout.facilities());
    }

    @Test
    void 屋里空地_放床放箱子的格连成片() {
        BuildingLayout layout = layoutOf(cottage());
        // 屋里九格：床占两格、箱子一格、梯子一格，剩五格连成一片（门洞那一格在墙里，头顶有墙，也算有顶的空地）。
        int total = layout.freeFloor().stream().mapToInt(BuildingLayout.FreeFloor::cells).sum();
        assertEquals(6, total, layout.freeFloor().toString());
        assertTrue(layout.freeFloor().stream().allMatch(area -> area.from().getY() == 1), "空地报的是人站的那一层");
    }

    @Test
    void 没读到的格不算能站也不算有顶() {
        Map<BlockPos, BlockState> world = cottage();
        world.keySet().removeIf(pos -> pos.getY() == 4);
        BuildingLayout layout = layoutOf(world);
        // 屋顶没读到：屋里九格都不知道有没有顶，只剩门洞那一格头上压着墙。
        assertEquals(1, layout.floors().getFirst().roofedCells(), layout.floors().toString());
        assertEquals("两边都没顶", layout.doors().getFirst().inside());
    }
}
