// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.dimension;

import static org.maiwithu.maicraft.core.task.dimension.NetherPortalFrameTest.check;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.actor.InteractionWorldTestHarness;
import org.maiwithu.maicraft.core.scan.TargetIndex;

public final class PortalSurveyTest {
    public static void main(String[] args) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var frame = new NetherPortalFrame(new BlockPos(5, 2, 5), Direction.Axis.X, 2, 3);
            for (var pos : frame.frame()) world.set(pos, Blocks.OBSIDIAN.defaultBlockState());
            try (var survey = new PortalSiteSurvey(world.level, world.player.blockPosition(), 32, false)) {
                PortalPreparationSite site = null;
                for (int tick = 0; tick < 60 && site == null; tick++) {
                    world.nextTick(); TargetIndex.clientTick(world.level); site = survey.tick(false);
                }
                check(site != null && site.nether().equals(frame), "intact frames are reused without construction permission");
                var stance = PortalApproach.find(world.player, site, frame.origin().below(), Vec3.atBottomCenterOf(frame.origin()), Set.of());
                check(stance != null && !site.forbiddenBody().contains(stance), "ignition stance stays outside the portal");
                check(PortalPreparationSupplies.next(world.player, site).alternatives().equals(java.util.List.of(Items.FLINT_AND_STEEL)),
                        "an intact frame only needs ignition supply");
                world.inventory.setItem(0, new ItemStack(Items.FIRE_CHARGE));
                check(PortalPreparationSupplies.next(world.player, site) == null, "an existing fire charge avoids acquiring another ignition item");
            }
        }
        try (var world = new InteractionWorldTestHarness();
             var survey = new PortalSiteSurvey(world.level, new BlockPos(7, 1, 7), 32, false)) {
            PortalPreparationSite site = null;
            for (int tick = 0; tick < 300 && site == null; tick++) {
                world.nextTick(); TargetIndex.clientTick(world.level); site = survey.tick(true);
            }
            check(site != null && site.newSite(world.level), "empty loaded terrain yields a bounded safe construction site");
            check(PortalPreparationSupplies.next(world.player, site).count() == 10, "new frame supply counts ten missing blocks");
        }
        System.out.println("PortalSurveyTest: loaded-only reuse, safe stance, new site and actual shortages passed");
    }
}
