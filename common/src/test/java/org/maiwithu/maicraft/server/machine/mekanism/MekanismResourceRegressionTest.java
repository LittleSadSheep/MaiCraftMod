// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.server.machine.mekanism;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.server.inventory.ResourceIdentity;

/** Real vanilla stack evidence remains exact when copied and counted by Mekanism production observers. */
public final class MekanismResourceRegressionTest {
    private MekanismResourceRegressionTest() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        ItemStack offered = new ItemStack(Items.IRON_INGOT, 7);
        Object captured = MekanismResourceStacks.copy(offered);
        offered.shrink(3);
        check(MekanismResourceStacks.amount(captured) == 7 && offered.getCount() == 4,
                "A native mutation altered the pre-operation evidence");
        check(MekanismResourceStacks.sameIdentity(captured, offered, registries), "Amount changes corrupted component identity");
        var original = MekanismResourceStacks.resource(captured, registries);
        check(original.get("medium").getAsString().equals("items"), "A real item was assigned another medium");
        check(original.get("id").getAsString().equals(ResourceIdentity.key(MekanismResourceStacks.identity(offered, registries))),
                "One resource obtained conflicting query/event identities");
        offered.set(DataComponents.CUSTOM_NAME, Component.literal("Different machine product"));
        check(!MekanismResourceStacks.sameIdentity(captured, offered, registries),
                "Component-distinct outputs would be combined in the native production event");
        check(!original.get("id").getAsString().equals(ResourceIdentity.key(MekanismResourceStacks.identity(offered, registries))),
                "The component difference was lost while serializing the resource");
        var transientType = net.minecraft.core.component.DataComponentType.<String>builder()
                .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8).build();
        offered.set(transientType, "unpersisted effect");
        boolean unknown = false;
        try { MekanismResourceStacks.identity(offered, registries); } catch (IllegalArgumentException expected) { unknown = true; }
        check(unknown, "Unrepresentable components must remain unknown instead of becoming an ordinary output");
        System.out.println("MekanismResourceRegressionTest: copy, component identity and unknown-component boundaries passed");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
