// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.pathing.baritone;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Exercise the same block capability check used to dispatch actual native door interactions. */
public final class NavigationActionPolicyTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        check(EmbeddedBaritoneActionBridge.isHandOpenable(Blocks.OAK_DOOR.defaultBlockState()), "wood door");
        check(EmbeddedBaritoneActionBridge.isHandOpenable(Blocks.COPPER_DOOR.defaultBlockState()), "copper door");
        check(EmbeddedBaritoneActionBridge.isHandOpenable(Blocks.WAXED_OXIDIZED_COPPER_DOOR.defaultBlockState()), "waxed copper door");
        check(EmbeddedBaritoneActionBridge.isHandOpenable(Blocks.OAK_FENCE_GATE.defaultBlockState()), "fence gate");
        check(!EmbeddedBaritoneActionBridge.isHandOpenable(Blocks.IRON_DOOR.defaultBlockState()), "iron door requires power");
        check(!EmbeddedBaritoneActionBridge.isHandOpenable(Blocks.STONE.defaultBlockState()), "solid obstacle");
        var pickaxe = new ItemStack(Items.IRON_PICKAXE);
        var blocks = new ItemStack(Items.COBBLESTONE);
        for (var passage : new net.minecraft.world.level.block.Block[]{
                Blocks.OAK_DOOR, Blocks.COPPER_DOOR, Blocks.OAK_FENCE_GATE}) {
            check(EmbeddedBaritoneActionBridge.chooseUseHand(passage.defaultBlockState(), false,
                    pickaxe, blocks) == InteractionHand.MAIN_HAND, "offhand blocks must not replace door use");
            check(EmbeddedBaritoneActionBridge.chooseUseHand(passage.defaultBlockState(), false,
                    ItemStack.EMPTY, blocks) == InteractionHand.MAIN_HAND, "empty main hand still opens doors");
            check(EmbeddedBaritoneActionBridge.chooseUseHand(passage.defaultBlockState(), false,
                    blocks, ItemStack.EMPTY) == InteractionHand.MAIN_HAND, "main-hand blocks use door before placement");
        }
        check(!EmbeddedBaritoneActionBridge.passageUseReady(false, true), "wait for actual unsneak");
        check(!EmbeddedBaritoneActionBridge.passageUseReady(true, false), "respect requested sneak");
        check(EmbeddedBaritoneActionBridge.passageUseReady(false, false), "unsneaked passage use");
        check(EmbeddedBaritoneActionBridge.chooseUseHand(Blocks.STONE.defaultBlockState(), false,
                pickaxe, blocks) == InteractionHand.OFF_HAND, "ordinary permitted offhand placement remains available");
        var progress = new EmbeddedBaritoneActionBridge.BreakProgress();
        var stone = Blocks.STONE.defaultBlockState();
        progress.begin(BlockPos.ZERO, stone);
        check(progress.observe(0.8F), "initial excavation advances");
        progress.begin(BlockPos.ZERO, stone);
        check(!progress.observe(0.7F), "retry must not reset high-water progress");
        progress.reset();
        progress.begin(BlockPos.ZERO, stone);
        check(progress.observe(0.1F), "replacement after confirmed removal gets a fresh progress episode");
        check(!progress.observe(Float.NaN), "invalid progress is not evidence");
        System.out.println("NavigationActionPolicyTest: passed");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
