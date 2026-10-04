// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;

/** clear 子任务的接近导航继承接单 goal 的地形授权；withApproach(false) 仍会接近，只禁改地形。 */
public final class PortalCastingClearPermissionTest {
    public static void main(String[] args) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var target = new BlockPos(3, 1, 3);
            var stone = Blocks.STONE.defaultBlockState();
            check(PortalCastingStep.clear(world.player, "clear-authorized", 1000L, target, stone, true).mayAlterTerrain,
                    "authorized casting clear permits terrain work on the approach");
            check(!PortalCastingStep.clear(world.player, "clear-unauthorized", 1000L, target, stone, false).mayAlterTerrain,
                    "unauthorized casting clear keeps the terrain-preserving approach");
            check(PortalCastingStep.clear(world.player, "clear-approach", 1000L, target, stone, false).approachTarget,
                    "clear always walks to the target block; the flag only governs terrain work");
        }
        System.out.println("PortalCastingClearPermissionTest: approach permission inheritance passed");
    }
}
