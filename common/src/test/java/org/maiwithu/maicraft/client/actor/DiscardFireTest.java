// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.core.task.inventory.DiscardFireCleanup;
import org.maiwithu.maicraft.task.TaskState;

/** 用原生端口回放点火、实体移除、耐火余物与取消扑火；输入包和世界结果分开提供，绝不把调用本身当销毁。 */
public final class DiscardFireTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        run(false, false, false, false); run(true, false, false, false); run(false, true, false, false);
        run(false, false, true, false); run(false, true, false, true);
        System.out.println("DiscardFireTest: burn observation, native extinguish, resistant items, mixed stacks and cancellation passed");
    }
    private static void run(boolean survives, boolean cancel, boolean mixed, boolean late) throws Exception {
        try (var world = new InteractionWorldTestHarness()) {
            world.enableCraftingTransactions(); world.position(new Vec3(8.5, 1, 8.5)); world.h.minecraft.screen = null;
            world.inventory.setItem(0, new ItemStack(Items.FLINT_AND_STEEL));
            BlockPos cell = new BlockPos(8, 1, 11);
            if (mixed) ItemEntityReceiptsTest.item(world, 72, Vec3.atBottomCenterOf(cell), new ItemStack(Items.COBBLESTONE, 4));
            var watch = DiscardedItems.watch(world.player, new ItemStack(Items.COBBLESTONE, 40), 40);
            if (mixed) ((ItemEntity) world.level.entities.get(72)).getItem().grow(40);
            else {
                ItemEntityReceiptsTest.item(world, 72, Vec3.atBottomCenterOf(cell), new ItemStack(Items.COBBLESTONE, 40));
                ItemEntityReceipts.entityAdded(world.player, world.level, 72);
            }
            DiscardedItems.observe(world.player);
            Class<?> type = Class.forName("org.maiwithu.maicraft.core.task.inventory.DiscardFire");
            var constructor = type.getDeclaredConstructor(List.class); constructor.setAccessible(true);
            Object fire = constructor.newInstance(List.of(watch));
            Method tick = method(type, "tick", LocalPlayerContext.class), close = method(type, "close", LocalPlayerContext.class), result = method(type, "result");
            world.mode.beforeBlockUse = () -> { if (!late) world.set(cell, Blocks.FIRE.defaultBlockState()); };
            world.mode.breaking = target -> {
                check(target.equals(cell), "left-click extinguishes the actual fire, never its support block");
                world.set(target, Blocks.AIR.defaultBlockState());
            };
            TaskState state = TaskState.RUNNING; boolean cancelled = false; int cancelledAt = -1;
            for (int step = 0; step < 350 && !state.isTerminal(); step++) {
                world.nextTick(); world.level.acknowledgedSequence = world.level.blockSequence;
                var context = ClientRuntime.requireContext(world.player);
                if (!cancelled) {
                    state = (TaskState) tick.invoke(fire, context);
                    if (cancel && world.blockUses() > 0) {
                        close.invoke(fire, context); cancelled = true; cancelledAt = step;
                    }
                } else {
                    // 服务端点火包晚于取消回执时，收尾观察先让出身体，迟到的火到达后仍需执行原生扑灭。
                    if (late && step == cancelledAt + 4) world.set(cell, Blocks.FIRE.defaultBlockState());
                    if (!DiscardFireCleanup.tick(context) && (!late || step > cancelledAt + 4)) state = TaskState.SUCCESS;
                }
                // 只在点火实际出现在世界后提供实体烧毁观察；耐火场景让物品继续留在地面，必须最终扑火并报告余物。
                if (!survives && !cancel && world.level.getBlockState(cell).is(Blocks.FIRE)) world.level.entities.remove(72);
                align(world);
            }
            check(state == TaskState.SUCCESS, "fire handling has a bounded completion");
            check(!world.level.getBlockState(cell).is(Blocks.FIRE), "owned fire is extinguished on success and cancellation");
            check(world.level.getBlockState(cell.below()).is(Blocks.STONE), "extinguishing preserves the floor");
            if (mixed) check(world.blockUses() == 0 && world.level.entities.containsKey(72), "mixed preexisting items are never authorized for burning");
            else check(world.blockUses() == 1 && world.mode.breakStarts == 1, "one native ignition is followed by one native extinguish");
            Map<?, ?> evidence = (Map<?, ?>) result.invoke(fire);
            if (survives) check(((Number) evidence.get("remaining_discarded_entities")).intValue() == 1
                    && !((List<?>) evidence.get("issues")).isEmpty(), "unburned items are reported instead of claimed destroyed");
        } finally { DiscardedItems.observe(null); }
    }
    static void align(InteractionWorldTestHarness world) throws Exception {
        Float yaw = (Float) ActorControlTestHarness.field(DefaultBodyControlPort.class, "targetYaw").get(world.h.body);
        Float pitch = (Float) ActorControlTestHarness.field(DefaultBodyControlPort.class, "targetPitch").get(world.h.body);
        if (yaw != null && pitch != null) { world.player.setYRot(yaw); world.player.setXRot(pitch); }
    }
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters); method.setAccessible(true); return method;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
