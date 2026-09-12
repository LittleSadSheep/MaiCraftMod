// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.create;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

/** Real vanilla ingredient/component semantics; the native recipe lookup is an explicit policy fixture. */
public final class CreatePressInputInspectionTest {
    private CreatePressInputInspectionTest() {}
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var inputs = List.of(Ingredient.of(Items.IRON_INGOT));
        var output = new ItemStack(Items.PAPER);
        Function<ItemStack, Optional<?>> unused = stack -> { throw new AssertionError("Expected input/output must not depend on another lookup"); };
        for (ItemStack held : List.of(ItemStack.EMPTY, new ItemStack(Items.IRON_INGOT), new ItemStack(Items.IRON_INGOT, 64), output)) {
            ItemStack before = held.copy();
            var result = CreatePressInputInspection.inspect(held, inputs, List.of(output), registries, unused);
            check(result.get("status").getAsString().equals("not_blocked"), "Empty, valid input or pending output became blocked");
            check(ItemStack.matches(before, held), "Inspection changed the held stack");
        }
        var cobble = new ItemStack(Items.COBBLESTONE);
        var blocked = CreatePressInputInspection.inspect(cobble, inputs, List.of(output), registries, stack -> Optional.empty());
        check(blocked.get("status").getAsString().equals("blocked") && blocked.get("amount").getAsInt() == 1
                        && blocked.getAsJsonObject("identity").get("id").getAsString().equals("minecraft:cobblestone"),
                "A known unrelated unprocessable item lost its obstruction identity");
        var processable = CreatePressInputInspection.inspect(cobble, inputs, List.of(output), registries, stack -> Optional.of("fixture recipe"));
        check(processable.get("status").getAsString().equals("not_blocked"), "Another native-processable batch was called a permanent obstruction");
        var changed = output.copy(); changed.set(DataComponents.CUSTOM_NAME, Component.literal("Different output components"));
        check(CreatePressInputInspection.inspect(changed, inputs, List.of(output), registries, stack -> Optional.empty())
                .get("status").getAsString().equals("blocked"), "Different output components were silently accepted");
        check(CreatePressInputInspection.inspect(cobble, inputs, List.of(output), registries,
                stack -> { throw new IllegalStateException("fixture native API unavailable"); }).get("status").getAsString().equals("unknown"),
                "Unavailable native evidence became a known obstruction");
        CreatePressInputInspection.inspect(cobble, inputs, List.of(output), registries, stack -> { stack.shrink(1); return Optional.empty(); });
        check(cobble.getCount() == 1, "Native lookup received the original live held stack");
        System.out.println("CreatePressInputInspectionTest: empty/input/output/foreign-item policy and read-only component handling passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
