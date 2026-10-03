// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** 同一弯岸分别留下十五格和十四格源岩浆，证明整形成本已经从余量扣除。 */
public final class PortalCastingTerrainTest {
    public static void main(String[] args) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var layout = new NetherPortalCastingLayout(new BlockPos(7, 1, 7), Direction.NORTH);
            for (int behind = -3; behind <= 1; behind++) for (int across = -1; across <= 2; across++)
                world.set(layout.cell(across, 0, behind), Blocks.LAVA.defaultBlockState());
            // 岸边一格和远端一格已经是陆地，其余三格岸线要真实填掉；十八个源格填完剩十五格。
            world.set(layout.cell(-1, 0, 1), Blocks.STONE.defaultBlockState());
            world.set(layout.cell(-1, 0, -3), Blocks.STONE.defaultBlockState());
            var enough = PortalCastingTerrain.inspect(world.level, layout, layout.origin(), 16);
            check(enough.sourcesReplaced() == 3 && enough.reserveObserved() && enough.remainingLowerBound() == 15,
                    "fill costs are excluded from the conservative fifteen-source lower bound");
            check(enough.fill().containsAll(List.of(layout.cell(0, 0, 1), layout.cell(1, 0, 1), layout.cell(2, 0, 1))),
                    "the curved shoreline has explicit solid-fill targets");
            world.set(layout.cell(0, 0, -3), Blocks.STONE.defaultBlockState());
            var shortfall = PortalCastingTerrain.inspect(world.level, layout, layout.origin(), 16);
            check(!shortfall.reserveObserved() && shortfall.remainingLowerBound() == 14,
                    "fourteen remaining sources do not satisfy the requested greater-than-fourteen reserve");
            check(PortalCastingTerrain.clearance(layout).stream().noneMatch(layout.mold()::contains),
                    "worksite clearance preserves the later mold positions");
        }
        System.out.println("PortalCastingTerrainTest: curved bank, reserved source counts and scoped platform passed");
    }
}
