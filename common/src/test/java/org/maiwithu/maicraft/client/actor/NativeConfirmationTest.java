// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** 测试原生挖掘回执轮询实际使用的替换判定。 */
public final class NativeConfirmationTest {
    public static void main(String[] args) throws Exception {
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
        var h=new ActorControlTestHarness();
        var factory=DefaultNativeActionPort.class.getDeclaredMethod("oneShot",NativeActionReceipt.Kind.class,
                LocalPlayerContext.class,NativeConfirmation.class,int.class); factory.setAccessible(true);
        NativeConfirmation observed=c -> NativeConfirmation.Verdict.APPLIED;
        var normal=(NativeActionReceipt)factory.invoke(h.actions,NativeActionReceipt.Kind.USE_ITEM,h.context,observed,10);
        if(h.actions.poll(h.context,normal).terminal()) throw new AssertionError("ordinary predictions still need their dwell");
        h.nextTick(true);
        if(!h.actions.poll(h.context,normal).terminal()) throw new AssertionError("ordinary observation settles after its second tick");
        var entity=(NativeActionReceipt)factory.invoke(h.actions,NativeActionReceipt.Kind.USE_ITEM,h.context,
                NativeConfirmation.serverObservedEntity(observed),10);
        if(h.actions.poll(h.context,entity).status()!=NativeActionReceipt.Status.CONFIRMED_APPLIED)
            throw new AssertionError("a server-observed entity does not wait an extra render or actor tick");
        delayedInstantUseIsNotAbandoned();
        System.out.println("NativeConfirmationTest: passed");
    }

    private static void delayedInstantUseIsNotAbandoned() throws Exception {
        // 无线终端等瞬时右键不进入持续持用；模拟服务端六刻后确认，期间只能等待，不能误报松手。
        try (var world = new InteractionWorldTestHarness()) {
            world.inventory.setItem(0, new ItemStack(Items.STICK));
            var context = ClientRuntime.requireContext(world.player);
            long observedAt = context.tickRevision() + 6;
            var receipt = context.actions().useItem(context, InteractionHand.MAIN_HAND,
                    current -> current.tickRevision() >= observedAt
                            ? NativeConfirmation.Verdict.APPLIED : NativeConfirmation.Verdict.PENDING, 12);
            advanceNative(world, 4);
            if (receipt.terminal()) throw new AssertionError("instant item use was retired before its confirmation window");
            advanceNative(world, 4);
            if (receipt.status() != NativeActionReceipt.Status.CONFIRMED_APPLIED || world.itemUses() != 1)
                throw new AssertionError("delayed confirmation must settle exactly one native click");
            // 真正没有回执时仍在原有期限内结束，不把瞬时使用改成无限等待。
            context = ClientRuntime.requireContext(world.player);
            var expired = context.actions().useItem(context, InteractionHand.MAIN_HAND,
                    current -> NativeConfirmation.Verdict.PENDING, 4);
            advanceNative(world, 6);
            if (expired.status() != NativeActionReceipt.Status.UNCERTAIN
                    || !expired.detail().contains("confirmation window expired"))
                throw new AssertionError("unconfirmed instant use must retain its bounded deadline");
        }
    }

    private static void advanceNative(InteractionWorldTestHarness world, int ticks) throws Exception {
        // 模拟客户端每刻先推进动作端口，再让持有任务读取同一份回执。
        for (int tick = 0; tick < ticks; tick++) {
            world.nextTick();
            world.h.actions.advance(ClientRuntime.requireContext(world.player));
        }
    }

    private static void expect(BlockState before, BlockState live, NativeConfirmation.Verdict expected) {
        var actual = NativeConfirmation.breakReplacementVerdict(before, live);
        if (actual != expected) throw new AssertionError("Expected " + expected + " for " + before
                + " -> " + live + ", got " + actual);
    }
}
