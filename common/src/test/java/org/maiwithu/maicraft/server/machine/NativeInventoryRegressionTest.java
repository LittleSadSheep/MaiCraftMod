// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.server.inventory.InventoryTransfer;
import org.maiwithu.maicraft.server.inventory.NativeItemPort;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

/** Exercises real vanilla stacks and inventories without a client, world, renderer or fabricated transaction result. */
public final class NativeInventoryRegressionTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        componentAwareCapacity();
        sidedTransfersConserveItems();
        identityExcludesCount();
        transientComponentsRemainUnknown();
        System.out.println("Native inventory conservation, component and sided simulation regressions passed");
    }

    private static void componentAwareCapacity() {
        ItemStack ordinary = new ItemStack(Items.IRON_INGOT, 16);
        ItemStack named = ordinary.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("Owner's configured part"));
        check(InventoryTransfer.room(ordinary, named) == 0, "Different item components would be merged");
        check(InventoryTransfer.room(ordinary, ordinary) == 48, "Remaining player slot capacity is wrong");
        check(InventoryTransfer.room(ItemStack.EMPTY, new ItemStack(Items.DIAMOND_PICKAXE)) == 1, "Unstackable capacity was invented");
    }

    private static void sidedTransfersConserveItems() {
        SidedInventory inventory = new SidedInventory();
        NativeItemPort input = new NativeItemPort.ContainerPort(inventory, Direction.NORTH);
        NativeItemPort output = new NativeItemPort.ContainerPort(inventory, Direction.SOUTH);
        ItemStack offer = new ItemStack(Items.IRON_INGOT, 40);
        ItemStack simulated = input.insert(0, offer, true);
        check(simulated.isEmpty() && inventory.isEmpty() && offer.getCount() == 40, "Simulated insertion mutated real inventory");
        ItemStack remainder = input.insert(0, offer, false);
        check(remainder.isEmpty() && inventory.getItem(0).getCount() == 40, "Insertion did not conserve supplied items");
        check(output.insert(0, offer, true).getCount() == 40, "A disabled input face accepted material");
        check(input.extract(0, 10, true).isEmpty(), "An input-only face exposed extraction");
        ItemStack preview = output.extract(0, 10, true);
        check(preview.getCount() == 10 && inventory.getItem(0).getCount() == 40, "Extraction simulation consumed material");
        ItemStack extracted = output.extract(0, 10, false);
        check(extracted.getCount() + inventory.getItem(0).getCount() == 40, "Native extraction failed conservation");
        ItemStack distinct = new ItemStack(Items.IRON_INGOT, 1);
        distinct.set(DataComponents.CUSTOM_NAME, Component.literal("Different component identity"));
        check(input.insert(0, distinct, false).getCount() == 1 && inventory.getItem(0).getCount() == 30,
                "Component-mismatched machine insertion changed existing material");
    }

    private static void identityExcludesCount() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        ItemStack first = new ItemStack(Items.IRON_INGOT, 1);
        String identity = ResourceIdentity.key(ResourceIdentity.item(first, registries));
        check(identity.equals(ResourceIdentity.key(ResourceIdentity.item(first.copyWithCount(64), registries))),
                "Stack count changed resource identity");
        first.set(DataComponents.CUSTOM_NAME, Component.literal("Configuration"));
        check(!identity.equals(ResourceIdentity.key(ResourceIdentity.item(first, registries))), "Serialized data components were omitted");
    }

    private static final class SidedInventory extends SimpleContainer implements WorldlyContainer {
        SidedInventory() { super(1); }
        @Override public int[] getSlotsForFace(Direction face) { return new int[]{0}; }
        @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction face) { return face == Direction.NORTH; }
        @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction face) { return face == Direction.SOUTH; }
    }

    private static void transientComponentsRemainUnknown() {
        var component = net.minecraft.core.component.DataComponentType.<String>builder()
                .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8).build();
        ItemStack stack = new ItemStack(Items.IRON_INGOT);
        stack.set(component, "not-persistable-but-gameplay-significant");
        try {
            ResourceIdentity.item(stack, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            throw new AssertionError("Transient components silently became ordinary resource identity");
        } catch (IllegalArgumentException expected) { /* The caller must surface unknown instead of merging it. */ }
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
