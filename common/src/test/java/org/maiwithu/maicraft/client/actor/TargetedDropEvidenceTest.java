// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Proxy;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.task.inventory.TargetedDropGeometry;

/** 检查投料数量、旧堆增量与有限轨迹；只驱动证据夹具，不把这些断言当作游戏加工成功。 */
public final class TargetedDropEvidenceTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); exactMergedCredit(); fluidRejectsDryRim(); geometryIsReadOnly();
        System.out.println("TargetedDropEvidenceTest: exact debit, merged increments and bounded trajectory guards passed");
    }
    private static void exactMergedCredit() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            BlockPos receiver = new BlockPos(5, 1, 5);
            world.inventory.setItem(0, new ItemStack(Items.QUARTZ, 3));
            var existing = ItemEntityReceiptsTest.item(world, 71, Vec3.atBottomCenterOf(receiver), new ItemStack(Items.QUARTZ, 4));
            var type = Class.forName("org.maiwithu.maicraft.core.task.inventory.TargetedDropReceipt");
            var constructor = type.getDeclaredConstructor(net.minecraft.client.player.LocalPlayer.class, BlockPos.class, ItemStack.class, int.class);
            constructor.setAccessible(true);
            var receipt = (NativeConfirmation) constructor.newInstance(world.player, receiver, world.inventory.getItem(0), 1);
            var context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                    (proxy, method, arguments) -> { if (method.getName().equals("player")) return world.player; throw new AssertionError("evidence cannot act: " + method.getName()); });
            world.inventory.getItem(0).shrink(1);
            check(receipt.observe(context) == NativeConfirmation.Verdict.PENDING, "an inventory debit alone is not receiving evidence");
            existing.getItem().grow(1);
            check(receipt.observe(context) == NativeConfirmation.Verdict.APPLIED, "an exact old-stack increase proves receiving without claiming its prior contents");
            var received = type.getDeclaredMethod("received"); received.setAccessible(true);
            Object credit = ((java.util.List<?>) received.invoke(receipt)).getFirst();
            var count = credit.getClass().getDeclaredMethod("count"); count.setAccessible(true);
            check(count.invoke(credit).equals(1), "a five-item merged entity credits only this drop's one-item increment");
            world.inventory.getItem(0).shrink(1);
            check(receipt.observe(context) == NativeConfirmation.Verdict.DIVERGED, "an extra source debit cannot be counted as the one requested item");
        }
    }
    private static void geometryIsReadOnly() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var receiver = new BlockPos(5, 1, 5);
            for (int x = 4; x <= 6; x++) for (int z = 4; z <= 6; z++) world.set(new BlockPos(x, 1, z), Blocks.WATER.defaultBlockState());
            Vec3 feet = new Vec3(3.5, 2, 5.5); world.set(new BlockPos(3, 1, 5), Blocks.STONE.defaultBlockState());
            Vec3 originalPosition = world.player.position(); float yaw = world.player.getYRot();
            check(TargetedDropGeometry.canReach(world.player, feet, receiver), "a raised nearby stance has a bounded native arc into the source cell");
            check(world.player.position().equals(originalPosition) && world.player.getYRot() == yaw && world.itemUses() == 0,
                    "stance probing never moves, turns or uses the player");
            for (int y = 1; y < 8; y++) for (int z = 0; z < 16; z++) world.set(new BlockPos(4, y, z), Blocks.STONE.defaultBlockState());
            check(!TargetedDropGeometry.canReach(world.player, feet, receiver), "a tall obstructing wall rejects every bounded trajectory before any drop");
        }
    }
    private static void fluidRejectsDryRim() throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            var receiver = new BlockPos(5, 1, 5); world.set(receiver, Blocks.WATER.defaultBlockState());
            world.inventory.setItem(0, new ItemStack(Items.QUARTZ, 2));
            var item = ItemEntityReceiptsTest.item(world, 72, new Vec3(4.9, 1, 5.5), new ItemStack(Items.QUARTZ, 4));
            var type = Class.forName("org.maiwithu.maicraft.core.task.inventory.TargetedDropReceipt");
            var constructor = type.getDeclaredConstructor(net.minecraft.client.player.LocalPlayer.class, BlockPos.class, ItemStack.class, int.class);
            constructor.setAccessible(true); var receipt = (NativeConfirmation) constructor.newInstance(world.player, receiver, world.inventory.getItem(0), 1);
            var context = (LocalPlayerContext) Proxy.newProxyInstance(LocalPlayerContext.class.getClassLoader(), new Class<?>[]{LocalPlayerContext.class},
                    (proxy, method, arguments) -> { if (method.getName().equals("player")) return world.player; throw new AssertionError(method.getName()); });
            world.inventory.getItem(0).shrink(1); item.getItem().grow(1);
            check(receipt.observe(context) == NativeConfirmation.Verdict.PENDING, "landing on the dry rim inside the broad search box does not prove fluid delivery");
            // 只移动夹具中的观察位置；真正任务从不移动地上物品，而是等待原生运动进入相容流体格。
            ActorControlTestHarness.field(net.minecraft.world.entity.Entity.class, "position").set(item, new Vec3(5.1, 1, 5.5));
            check(receipt.observe(context) == NativeConfirmation.Verdict.APPLIED, "the exact same increment is accepted only when its center enters water");
        }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
