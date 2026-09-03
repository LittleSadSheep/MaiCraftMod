// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Tests the exact replacement verdict used by native mining receipt polling. */
public final class NativeConfirmationTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockState dryFence = Blocks.OAK_FENCE.defaultBlockState();
        BlockState wetFence = dryFence.setValue(BlockStateProperties.WATERLOGGED, true);
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        expect(Blocks.STONE.defaultBlockState(), air, NativeConfirmation.Verdict.APPLIED);
        expect(wetFence, water, NativeConfirmation.Verdict.APPLIED);
        expect(Blocks.SEAGRASS.defaultBlockState(), water, NativeConfirmation.Verdict.APPLIED);
        expect(wetFence, air, NativeConfirmation.Verdict.APPLIED);
        expect(wetFence, wetFence, NativeConfirmation.Verdict.PENDING);
        expect(water, water, NativeConfirmation.Verdict.PENDING);
        expect(wetFence, dryFence, NativeConfirmation.Verdict.DIVERGED);
        expect(dryFence, water, NativeConfirmation.Verdict.DIVERGED);
        expect(wetFence, Blocks.LAVA.defaultBlockState(), NativeConfirmation.Verdict.DIVERGED);
        expect(wetFence, Blocks.STONE.defaultBlockState(), NativeConfirmation.Verdict.DIVERGED);
        System.out.println("NativeConfirmationTest: passed");
    }

    private static void expect(BlockState before, BlockState live, NativeConfirmation.Verdict expected) {
        var actual = NativeConfirmation.breakReplacementVerdict(before, live);
        if (actual != expected) throw new AssertionError("Expected " + expected + " for " + before
                + " -> " + live + ", got " + actual);
    }
}
