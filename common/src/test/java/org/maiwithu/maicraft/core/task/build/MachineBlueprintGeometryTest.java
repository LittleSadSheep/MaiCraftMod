// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.task.build;

import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Regression: generic integer-progress fallback must not bypass explicit machine property requirements. */
public final class MachineBlueprintGeometryTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        BlockState wanted = Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES, 4);
        BlockState wrong = wanted.setValue(BlockStateProperties.RESPAWN_ANCHOR_CHARGES, 1);
        BuildTaskRecord.Target exact = new BuildTaskRecord.Target(wanted, Items.RESPAWN_ANCHOR,
                BlockPos.ZERO, "test", null, null, null, false, Set.of("charges"), true);
        if (BuildPlacementGeometry.isProgress(exact, Blocks.AIR.defaultBlockState(), wrong)) {
            throw new AssertionError("integer progress must not certify a mismatching explicitly requested machine state");
        }
        if (!BuildPlacementGeometry.isProgress(exact, Blocks.AIR.defaultBlockState(), wanted)) {
            throw new AssertionError("matching exact state should remain a valid native receipt");
        }
        System.out.println("MachineBlueprintGeometryTest: passed");
    }
}
