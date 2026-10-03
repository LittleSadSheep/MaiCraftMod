// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

/** 单层浅池仍可选址，底框由后续原生开槽浇筑；流水和明确保留的位置不当作可用池岸。 */
public final class PortalCastingSurveyTest {
    public static void main(String[] args) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var layout = new NetherPortalCastingLayout(new BlockPos(7, 1, 7), Direction.NORTH);
            for (int x = -1; x <= 2; x++) {
                world.set(layout.cell(x, 0, 0), Blocks.LAVA.defaultBlockState());
                world.set(layout.cell(x, 0, 1), Blocks.STONE.defaultBlockState());
            }
            check(PortalCastingSurvey.atShore(world.level, layout), "a shallow pool permits native bottom excavation");
            // 四格直岸虽然能起手，却供不起整扇门；只读选址必须分别报告几何可用和源格余量不足。
            var small = PortalCastingTerrain.inspect(world.level, layout, layout.origin(), 16);
            check(!small.reserveObserved() && small.remainingLowerBound() == 4,
                    "a natural straight bank cannot bypass the connected source reserve");
            world.set(layout.cell(0, 0, 0), Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 1));
            check(!PortalCastingSurvey.atShore(world.level, layout), "flowing lava cannot be scooped as a source");
            world.set(layout.cell(0, 0, 0), Blocks.LAVA.defaultBlockState());
            check(!NavigationSafetyContext.withProtectedArea(List.of(layout.placeholder()), List.of(),
                    () -> PortalCastingSurvey.atShore(world.level, layout)), "explicit preservation applies to the selected template");
        }
        System.out.println("PortalCastingSurveyTest: shallow pool, source identity and preservation passed");
    }
}
