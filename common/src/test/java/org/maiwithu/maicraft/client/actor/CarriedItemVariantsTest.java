// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import org.maiwithu.maicraft.core.inventory.CarriedItemVariants;
import org.maiwithu.maicraft.core.task.MouseButton;
import org.maiwithu.maicraft.core.task.interact.InteractAtCompanionTask;
import org.maiwithu.maicraft.core.task.interact.InteractAtTaskRecord;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;
import org.maiwithu.maicraft.task.TaskState;

/** 取出的高进度工件优先保持在手，显式身份穿过语义契约后控制实际原生出手所用的堆栈。 */
public final class CarriedItemVariantsTest {
    public static void main(String[] args) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
            ActorControlTestHarness.field(Level.class, "registryAccess").set(h.level, registries);
            var early = workpiece(1); var late = workpiece(14);
            h.inventory.setItem(0, early); h.inventory.setItem(2, late); h.inventory.selected = 2;
            String earlyKey = ResourceIdentity.key(ResourceIdentity.item(early, registries));
            String lateKey = ResourceIdentity.key(ResourceIdentity.item(late, registries));
            check(CarriedItemVariants.find(h.inventory, Items.BRICK, null, registries) == 2, "default use preserves the current workpiece");
            check(CarriedItemVariants.find(h.inventory, Items.BRICK, earlyKey, registries) == 0, "explicit identity can choose a different observed stage");
            check(CarriedItemVariants.find(h.inventory, Items.STICK, earlyKey, registries) == -1, "resource identity cannot override the requested item type");
            early.setCount(3);
            check(CarriedItemVariants.find(h.inventory, Items.BRICK, earlyKey, registries) == 0, "count changes do not alter component identity");
            h.inventory.setItem(0, workpiece(2));
            check(CarriedItemVariants.find(h.inventory, Items.BRICK, earlyKey, registries) == -1, "changed components never fall back to another same-name stack");

            var at = new BlockPos(0, 1, 0); h.set(at, Blocks.STONE.defaultBlockState());
            // 把当前手切成另一进度，再让真实交互任务选回指定变体；核对点击真正提交时的主手，而不是仅测查询函数。
            h.inventory.selected = 0;
            var record = new InteractAtTaskRecord("exact-workpiece", 500, MouseButton.RIGHT, at, 0, Items.BRICK).withItemResourceId(lateKey);
            var task = new InteractAtCompanionTask(h.player, record); task.start(h.player);
            for (int tick = 0; h.blockUses() == 0 && tick < 80; tick++) {
                check(task.tick(h.player) != TaskState.FAILED, "exact variant selection must reach native use");
                ActorControlTestHarness.field(DefaultBodyControlPort.class, "lastLookUpdateNanos").setLong(h.h.body, System.nanoTime() - 50_000_000L);
                h.h.body.endTick(h.h.context); h.nextTick();
            }
            check(h.blockUses() == 1 && CarriedItemVariants.matches(h.player.getMainHandItem(), Items.BRICK, lateKey, registries),
                    "one native use consumes the selected component variant only");
            task.result(TaskState.CANCELLED);
        }
        System.out.println("CarriedItemVariantsTest: passed");
    }
    private static ItemStack workpiece(int step) {
        // 原版自定义数据即可回放有不同装配进度的同名物品，不需要在测试中伪造模组注册表。
        ItemStack stack = new ItemStack(Items.BRICK); CompoundTag data = new CompoundTag(); data.putInt("step", step);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data)); return stack;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
