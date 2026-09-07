// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** A two-high hatch can be closed only from its declared exterior, with the interior protected. */
public final class MachineSealingTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        BlockPos lower = new BlockPos(0, 64, 1), outside = new BlockPos(-2, 63, 1);
        var low = target(lower); var high = target(lower.above());
        var seal = new MachineSealingTaskRecord.Seal(List.of(high, low), outside);
        check(seal.targets().getFirst() == low && seal.outward() == Direction.WEST, "closure retains bottom-up order and exterior face");
        check(seal.onOutside(Vec3.atBottomCenterOf(outside)), "declared outside stance permits sealing");
        check(!seal.onOutside(Vec3.atBottomCenterOf(lower))
                && !seal.onOutside(Vec3.atBottomCenterOf(lower.east())), "doorway and interior cannot issue closing mutations");
        BlockPos inside = lower.east(), floor = lower.below();
        var protectedCells = MachineSealingTask.sealingProtection(
                List.of(lower, lower.above(), outside, outside.above(), inside, floor), seal);
        check(protectedCells.equals(List.of(inside, floor)), "only closing cells and the verified exterior body cells are exempt");
        try {
            new MachineSealingTaskRecord.Seal(List.of(low, target(lower.east())), outside);
            throw new AssertionError("nonvertical opening accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("MachineSealingTest: passed");
    }
    private static BuildTaskRecord.Target target(BlockPos pos) {
        return new BuildTaskRecord.Target(Blocks.IRON_BLOCK.defaultBlockState(), Items.IRON_BLOCK,
                pos, "matrix closure", null, null, null);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
