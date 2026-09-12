// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;

public final class PortalPreparationSiteTest {
    public static void main(String[] args) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var frame = new NetherPortalFrame(new BlockPos(5, 2, 5), Direction.Axis.X, 2, 3);
            var site = new PortalPreparationSite(frame, null);
            check(site.newSite(world.level), "a raised frame on clear solid ground is buildable");
            check(site.missingBlocks(world.level).size() == 10, "new construction supplies precisely the required frame");
            for (var pos : frame.frame().subList(0, 6)) world.set(pos, Blocks.OBSIDIAN.defaultBlockState());
            check(site.missingBlocks(world.level).size() == 4, "repair preserves six existing blocks");
            BlockPos missing = site.missingBlocks(world.level).getFirst().pos();
            world.set(missing, Blocks.CHEST.defaultBlockState());
            check(!site.valid(world.level), "a newly occupied target invalidates construction");
            world.set(missing, Blocks.AIR.defaultBlockState());
            check(!NavigationSafetyContext.withProtectedArea(List.of(frame.origin()), List.of(), () -> site.valid(world.level)),
                    "activation cannot replace a protected interior cell");
            var support = frame.cell(0, -2).south();
            world.set(support, Blocks.LAVA.defaultBlockState());
            check(!site.newSite(world.level), "a lava-side approach is unsuitable");
            var unloaded = new PortalPreparationSite(new NetherPortalFrame(new BlockPos(15, 2, 5), Direction.Axis.X, 2, 3), null);
            check(!unloaded.newSite(world.level), "surveying never reads across an unloaded chunk boundary");
        }
        System.out.println("PortalPreparationSiteTest: bounded construction, repair and live protection passed");
    }
}
