// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.machine;

import java.util.UUID;

/** Standalone pure regression checks; no Minecraft or optional mod runtime is needed. */
public final class MachineMenuPolicyTest {
    private static int checks;
    private MachineMenuPolicyTest() {}

    public static void main(String[] args) {
        check(MachineMenuPolicy.dedicatedStorageMenu("appeng.menu.me.items.ItemTerminalMenu"), "AE2 uses its dedicated supply path");
        check(!MachineMenuPolicy.dedicatedStorageMenu("appeng.menu.implementations.DriveMenu"), "AE2 physical storage-cell slots use observed native inventory transfers");
        check(MachineMenuPolicy.dedicatedStorageMenu("appeng.menu.implementations.SomeDriveMenu"), "unknown AE2 menus do not inherit the drive exception");
        check(MachineMenuPolicy.dedicatedStorageMenu("com.refinedmods.refinedstorage.api.Menu"), "RS virtual inventory is not raw slot storage");
        check(!MachineMenuPolicy.dedicatedStorageMenu("some.mod.TerminalMachineMenu"), "terminal word alone does not reject a real menu");
        check(!MachineMenuPolicy.dedicatedStorageMenu("some.mod.CraftingMachineMenu"), "crafting word alone does not reject a real menu");
        check(!MachineMenuPolicy.dedicatedStorageMenu("mekanism.common.inventory.container.tile.MekanismTileContainer"), "Mekanism native machine menu is supported by slot evidence");
        for (String marker : new String[]{"Ghost", "Phantom", "Virtual", "Filter", "Pattern"}) {
            check(MachineMenuPolicy.virtualEntryName("some.mod." + marker + "Slot"), "reject nonphysical entry marker " + marker);
        }
        check(!MachineMenuPolicy.virtualEntryName("mekanism.common.inventory.container.slot.InventoryContainerSlot"), "Mekanism real slots proceed to backing evidence");
        check(!MachineMenuPolicy.virtualEntryName("modded.machine.CustomOutputSlot"), "unknown output classes are not rejected by names alone");
        check(MachineMenuPolicy.validTransferBounds(0, 1), "minimum transfer bounds accepted");
        check(MachineMenuPolicy.validTransferBounds(511, 64), "maximum transfer bounds accepted");
        for (int entry : new int[]{-1, 512, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            check(!MachineMenuPolicy.validTransferBounds(entry, 1), "reject unobserved entry " + entry);
        }
        for (int count : new int[]{0, -1, 65, Integer.MAX_VALUE}) {
            check(!MachineMenuPolicy.validTransferBounds(0, count), "reject nonexact bounded amount " + count);
        }
        String receipt = UUID.randomUUID().toString();
        check(MachineMenuPolicy.validReceiptId(receipt), "issued receipt shape accepted");
        check(MachineMenuPolicy.validReceiptId(receipt.toUpperCase()), "UUID case does not change receipt identity");
        for (String invalid : new String[]{"", "1-1-1-1-1", "not-a-receipt", receipt + " ", null}) {
            check(!MachineMenuPolicy.validReceiptId(invalid), "reject invented receipt syntax");
        }
        var whole = MachineMenuPolicy.pickup(64, 64, false);
        check(whole.supported() && whole.button() == 0 && whole.amount() == 64, "whole output transfer needs no return");
        var half = MachineMenuPolicy.pickup(63, 32, false);
        check(half.supported() && half.button() == 1 && half.amount() == 32, "odd source halves round up natively");
        check(!MachineMenuPolicy.pickup(64, 1, false).supported(), "one item cannot be withdrawn by returning cursor to output-only entry");
        check(!MachineMenuPolicy.pickup(63, 31, false).supported(), "floor half is not a native exact pickup");
        var returnable = MachineMenuPolicy.pickup(64, 1, true);
        check(returnable.supported() && returnable.button() == 0 && returnable.amount() == 64, "ordinary input/container can receive the remainder");
        check(!MachineMenuPolicy.pickup(1, 2, true).supported(), "cannot take more than present");
        check(!MachineMenuPolicy.pickup(0, 1, true).supported(), "empty sources do not produce phantom pickups");
        check(!MachineMenuPolicy.pickup(Integer.MAX_VALUE, 1, false).supported(), "half-count arithmetic does not overflow");
        System.out.println("MachineMenuPolicyTest: " + checks + " checks passed");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
}
