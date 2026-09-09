// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

public final class BuildLayerFrontierTest {
    public static void main(String[] args) {
        Map<Long, BuildTaskRecord.Target> floor = new LinkedHashMap<>();
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) {
            var target = stone(x, 0, z); floor.put(target.pos().asLong(), target);
        }
        var corner = stone(0, 0, 0); var rim = stone(0, 0, 2); var face = stone(2, 0, 2);
        var order = BuildLayerFrontier.order(floor);
        check(order.compare(corner, rim) < 0 && order.compare(rim, face) < 0,
                "current-layer corners and outlines precede infill as a soft queue preference");
        check(order.compare(face, stone(0, 1, 0)) < 0,
                "an upper corner never wins over lower-layer infill");
        var lantern = new BuildTaskRecord.Target(Blocks.LANTERN.defaultBlockState()
                .setValue(BlockStateProperties.HANGING, true), Items.LANTERN,
                new BlockPos(2, 1, 2), "hanging lantern", null, null, null);
        check(BuildLayerFrontier.layer(lantern) == 2
                        && order.compare(stone(2, 2, 2), lantern) < 0,
                "hanging fixtures wait for their ceiling rather than deadlocking the lower structural layer");
        var lower = new BuildTaskRecord.Target(Blocks.OAK_DOOR.defaultBlockState(), Items.OAK_DOOR,
                new BlockPos(2, 1, 2), "door lower", null, null, null);
        var upper = new BuildTaskRecord.Target(Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), Items.OAK_DOOR,
                new BlockPos(2, 2, 2), "door upper", null, null, null);
        check(BuildLayerFrontier.layer(lower) == BuildLayerFrontier.layer(upper),
                "automatic door halves are one placement, not two independent layer gates");
        System.out.println("BuildLayerFrontierTest: structural layers and bounded contour preferences passed");
    }

    private static BuildTaskRecord.Target stone(int x, int y, int z) {
        return new BuildTaskRecord.Target(Blocks.STONE, Items.STONE, new BlockPos(x, y, z), "stone", null, null, null);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
