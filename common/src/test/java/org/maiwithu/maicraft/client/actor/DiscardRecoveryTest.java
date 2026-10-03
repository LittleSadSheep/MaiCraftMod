// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.List;
import java.lang.reflect.Method;
import net.minecraft.SharedConstants;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.core.pathing.execute.NavigationSafetyContext;
import org.maiwithu.maicraft.task.TaskState;

/** 走廊余物回收必须有同 UUID 的本人拾取包和实际入包；临时豁免只属于本批，取消后恢复遗留物避让。 */
public final class DiscardRecoveryTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (boolean cancel : new boolean[]{false, true}) try (var world = new InteractionWorldTestHarness()) {
            world.enableCraftingTransactions(); world.h.minecraft.screen = null; world.position(new Vec3(8.5, 1, 8.5));
            var watch = DiscardedItems.watch(world.player, new ItemStack(Items.COBBLESTONE, 40), 40);
            var drop = ItemEntityReceiptsTest.item(world, 90, world.player.position(), new ItemStack(Items.COBBLESTONE, 40));
            drop.setDeltaMovement(Vec3.ZERO);
            ActorControlTestHarness.field(Entity.class, "blockPosition").set(drop, world.player.blockPosition());
            ActorControlTestHarness.field(Entity.class, "level").set(drop, world.level);
            ItemEntityReceipts.entityAdded(world.player, world.level, 90); DiscardedItems.observe(world.player);
            DiscardedItems.watch(world.player, new ItemStack(Items.GOLD_INGOT), 1);
            ItemEntityReceiptsTest.item(world, 91, new Vec3(4.5, 1, 4.5), new ItemStack(Items.GOLD_INGOT));
            ItemEntityReceipts.entityAdded(world.player, world.level, 91); DiscardedItems.observe(world.player);
            Class<?> type = Class.forName("org.maiwithu.maicraft.core.task.inventory.DiscardRecovery");
            var constructor = type.getDeclaredConstructor(LocalPlayer.class, String.class, List.class, List.class); constructor.setAccessible(true);
            Object recovery = constructor.newInstance(world.player, "recovery-test", List.of(watch), watch.remaining());
            Method tick = method(type, "tick", LocalPlayer.class), close = method(type, "close", LocalPlayer.class), collected = method(type, "collected");
            tick.invoke(recovery, world.player);
            check(!NavigationSafetyContext.forbidsBody(world.player.blockPosition()), "only authorized recovery can approach its discarded stack");
            check(NavigationSafetyContext.forbidsBody(new BlockPos(4, 1, 4)), "other discarded items remain protected during recovery");
            world.nextTick(); tick.invoke(recovery, world.player);
            if (cancel) {
                close.invoke(recovery, world.player);
                check(NavigationSafetyContext.forbidsBody(world.player.blockPosition()), "cancellation restores the remaining stack's avoidance");
                continue;
            }
            // 原生收取包与背包增长在本轮提供，只有实体消失的回放不应被当作已经拿回四十个。
            ItemEntityReceipts.taking(world.player, world.level, new ClientboundTakeItemEntityPacket(90, world.player.getId(), 40));
            world.level.entities.remove(90); world.inventory.setItem(2, new ItemStack(Items.COBBLESTONE, 40));
            TaskState state = TaskState.RUNNING;
            for (int step = 0; step < 30 && !state.isTerminal(); step++) { world.nextTick(); state = (TaskState) tick.invoke(recovery, world.player); }
            check(state == TaskState.SUCCESS && collected.invoke(recovery).equals(40), "native pickup confirms all forty recovered items");
            check(watch.remaining().isEmpty(), "confirmed recovery removes the old route obstacle before selecting another site");
        } finally { DiscardedItems.observe(null); }
        System.out.println("DiscardRecoveryTest: exact pickup, scoped approach and cancellation restoration passed");
    }
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters); method.setAccessible(true); return method;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
