// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 捕获原生调用自身产生的输出后缀，包括卷制和序列化数据组件。 */
public final class PressProductionCapture {
    private record Capture(BlockEntity producer, List<ItemStack> output, int start, ItemStack input, String recipe) {}
    private static final ThreadLocal<ArrayDeque<Capture>> CALLS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final String PRESS = "com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity";
    private PressProductionCapture() {}

    public static void begin(BlockEntity producer, Object transported, List<ItemStack> output, boolean simulate) {
        if (!(producer.getLevel() instanceof ServerLevel)) return;
        ArrayDeque<Capture> calls = CALLS.get();
        if (calls.size() >= 32) calls.clear(); // 第三方原生调用抛错时，限制未完成帧的累积数量。
        Capture capture = new Capture(producer, output, output == null ? 0 : output.size(), ItemStack.EMPTY, null);
        if (!simulate && output != null) {
            try {
                ItemStack input = ((ItemStack) NativeApi.field(transported,
                        "com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack", "stack")).copy();
                if (!NativeApi.truth(NativeApi.call(producer, PRESS, "canProcessInBulk"))) input.setCount(1);
                Optional<?> recipe = (Optional<?>) NativeApi.call(producer, PRESS, "getRecipe", input);
                if (recipe.isPresent() && recipe.get() instanceof RecipeHolder<?> holder) {
                    capture = new Capture(producer, output, output.size(), input, holder.id().toString());
                }
            } catch (RuntimeException | LinkageError unsupported) {
                // 埋点绝不能影响游戏行为；缺少原生证据时不能证明发生了生产。
            }
        }
        calls.addLast(capture);
    }

    public static void finish(BlockEntity producer, List<ItemStack> output, boolean simulate, boolean applied) {
        if (!(producer.getLevel() instanceof ServerLevel)) return;
        ArrayDeque<Capture> calls = CALLS.get();
        Capture capture = calls.pollLast();
        if (capture == null || simulate || !applied || capture.recipe() == null || capture.producer() != producer
                || capture.output() != output || capture.start() > output.size()) return;
        try {
            ServerProductionEvents.pressed(producer, capture.recipe(), capture.input(),
                    output.subList(capture.start(), output.size()).stream().map(ItemStack::copy).toList());
        } catch (RuntimeException | LinkageError unsupported) {
            // 输出交付仍由原生逻辑负责；观察器失败时不能中断交付。
        }
    }
}
