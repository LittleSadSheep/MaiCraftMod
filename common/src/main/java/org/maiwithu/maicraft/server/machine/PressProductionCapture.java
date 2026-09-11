// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Captures the native call's own output suffix, including rolled and sequenced data components. */
public final class PressProductionCapture {
    private record Capture(BlockEntity producer, List<ItemStack> output, int start, ItemStack input, String recipe) {}
    private static final ThreadLocal<ArrayDeque<Capture>> CALLS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final String PRESS = "com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity";
    private PressProductionCapture() {}

    public static void begin(BlockEntity producer, Object transported, List<ItemStack> output, boolean simulate) {
        if (!(producer.getLevel() instanceof ServerLevel)) return;
        ArrayDeque<Capture> calls = CALLS.get();
        if (calls.size() >= 32) calls.clear(); // Bound abandoned frames if a third-party native call threw.
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
                // Instrumentation must never affect gameplay. Missing native evidence cannot prove production.
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
            // Output delivery remains native; a failed observer is not allowed to interrupt it.
        }
    }
}
