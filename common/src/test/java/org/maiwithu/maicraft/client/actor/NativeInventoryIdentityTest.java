// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import com.mojang.serialization.Codec;
import java.util.IdentityHashMap;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;

/** 在独立回归进程注册终端身份，走真正的菜单端口，复现实机选圆石时终端电量同时更新。 */
public final class NativeInventoryIdentityTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        var energy = DataComponentType.<Double>builder().persistent(Codec.DOUBLE).build();
        register(BuiltInRegistries.DATA_COMPONENT_TYPE, "ae2:stored_energy", () -> energy);
        var terminal = register(BuiltInRegistries.ITEM, "ae2:wireless_crafting_terminal", () -> new Item(new Item.Properties()));
        verify(terminal, energy, "energy", true, MenuReceipt.Status.CONFIRMED_APPLIED);
        verify(terminal, energy, "energy", false, MenuReceipt.Status.CONFIRMED_APPLIED);
        verify(terminal, energy, "name", true, MenuReceipt.Status.DIVERGED);
        verify(terminal, energy, "count", true, MenuReceipt.Status.DIVERGED);
        verify(terminal, energy, "rejected", true, MenuReceipt.Status.CONFIRMED_NOT_APPLIED);
        verify(Items.COMPASS, energy, "energy", true, MenuReceipt.Status.DIVERGED);
        System.out.println("NativeInventoryIdentityTest: native swaps preserve terminal identity across energy updates; changed names, counts and other items still diverge");
    }
    private static void verify(Item item, DataComponentType<Double> energy, String change, boolean synchronizedClick,
            MenuReceipt.Status expected) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            var terminal = new ItemStack(item); terminal.set(energy, 100.0);
            terminal.set(DataComponents.CUSTOM_NAME, Component.literal("network-A"));
            var material = new ItemStack(Items.COBBLESTONE, 2);
            h.inventory.setItem(0, terminal.copy()); h.inventory.setItem(9, material.copy()); h.inventory.selected = 0;
            h.enableInventoryTransactions(synchronizedClick);
            boolean visible = false;
            for (int tick = 0; tick < 10 && !visible; tick++) {
                var context = ClientRuntime.requireContext(h.player); visible = context.menus().ensureVisible(context);
                if (!visible) { h.nextTick(); MenuVisibility.rendered(h.h.minecraft.screen); }
            }
            check(visible, "native inventory must be visibly rendered before the swap");
            var context = ClientRuntime.requireContext(h.player);
            var receipt = context.menus().swapInventoryToHotbar(context, 9, 0, 20);
            check(h.mode.menuClicks == 1, "exactly one native swap is submitted");
            // 只由夹具模拟后续服务端同步，不借生产确认逻辑移动物品或恢复能量。
            h.inventory.getItem(9).set(energy, 99.0);
            if (change.equals("name")) h.inventory.getItem(9).set(DataComponents.CUSTOM_NAME, Component.literal("network-B"));
            if (change.equals("count")) h.inventory.getItem(9).setCount(2);
            if (change.equals("rejected")) { h.inventory.setItem(9, material.copy()); h.inventory.setItem(0, terminal.copy()); h.inventory.getItem(0).set(energy, 98.0); }
            // 先直接读取本次槽位判据，使身份比较异常与菜单同步等待失败能够分别定位。
            var verdict = receipt.confirmation().observe(context, receipt);
            var expectedVerdict = expected == MenuReceipt.Status.CONFIRMED_APPLIED ? MenuConfirmation.Verdict.APPLIED
                    : expected == MenuReceipt.Status.CONFIRMED_NOT_APPLIED ? MenuConfirmation.Verdict.NOT_APPLIED : MenuConfirmation.Verdict.DIVERGED;
            check(verdict == expectedVerdict, "unexpected slot evidence: " + verdict + " for " + change);
            h.nextTick(); context = ClientRuntime.requireContext(h.player); context.menus().poll(context, receipt);
            if (!synchronizedClick) check(!receipt.terminal(), "client prediction alone cannot immediately confirm the slot swap");
            for (int tick = 0; tick < 40 && !receipt.terminal(); tick++) {
                h.nextTick(); context = ClientRuntime.requireContext(h.player); context.menus().poll(context, receipt);
            }
            check(receipt.status() == expected, "unexpected native swap result for " + change + ": " + receipt.status() + " / " + receipt.detail());
            check(h.mode.menuClicks == 1 && h.blockUses() == 0 && h.itemUses() == 0, "confirmation must never retry or use the displaced item");
        }
    }
    private static <T> T register(Registry<T> registry, String id, Supplier<T> factory) throws Exception {
        // 同名夹具仅存活于本测试进程；临时打开注册入口后立即恢复冻结状态，不修改任何真实世界文件。
        var frozen = ActorControlTestHarness.field(MappedRegistry.class, "frozen");
        var holders = ActorControlTestHarness.field(MappedRegistry.class, "unregisteredIntrusiveHolders");
        boolean wasFrozen = frozen.getBoolean(registry); Object before = holders.get(registry);
        try {
            frozen.setBoolean(registry, false);
            if (registry.equals(BuiltInRegistries.ITEM)) holders.set(registry, new IdentityHashMap<>());
            T value = Registry.register(registry, ResourceLocation.parse(id), factory.get());
            // 原生冻结过程会绑定组件持有者；只改 frozen 字段会留下有名字却无法读取的测试组件。
            registry.freeze();
            return value;
        } finally { frozen.setBoolean(registry, wasFrozen); holders.set(registry, before); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
