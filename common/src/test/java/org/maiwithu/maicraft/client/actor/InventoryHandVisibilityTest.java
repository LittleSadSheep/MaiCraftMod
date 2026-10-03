// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.client.runtime.ClientRuntime;
import org.maiwithu.maicraft.mcp.MaiCraftRuntimeFacade;

/** 经真实副手交换后读取 MCP 使用的库存摘要：火把位置改变，但数量不会消失或被主手重复计入。 */
public final class InventoryHandVisibilityTest {
    public static void main(String[] args) throws Exception {
        try (var h = new InteractionWorldTestHarness()) {
            h.enableInventoryTransactions(true); h.h.minecraft.screen = null;
            h.inventory.setItem(0, new ItemStack(Items.IRON_PICKAXE));
            h.inventory.setItem(12, new ItemStack(Items.TORCH, 64));
            h.inventory.setItem(40, new ItemStack(Items.SHIELD));
            h.inventory.setItem(36, new ItemStack(Items.DIAMOND_BOOTS));
            var before = summary(h.player);
            check(row(before, "minecraft:torch").get("count").getAsInt() == 64, "背包火把原数量可见");
            var context = ClientRuntime.requireContext(h.player);
            var swap = context.menus().swapInventoryToOffhand(context, 12, 40);
            h.nextTick(); context = ClientRuntime.requireContext(h.player);
            check(context.menus().poll(context, swap).status() == MenuReceipt.Status.CONFIRMED_APPLIED, "原生交换已确认");
            var after = summary(h.player); var torch = row(after, "minecraft:torch");
            check(torch.get("count").getAsInt() == 64 && torch.getAsJsonObject("location_counts").get("off_hand").getAsInt() == 64,
                    "只看 inventory 也能看到副手火把及准确数量");
            check(row(after, "minecraft:iron_pickaxe").get("count").getAsInt() == 1, "选中主手镐只统计一次");
            check(row(after, "minecraft:shield").getAsJsonObject("location_counts").get("backpack").getAsInt() == 1,
                    "换下的副手物品保留在背包中");
            check(row(after, "minecraft:diamond_boots").getAsJsonObject("location_counts").get("armor").getAsInt() == 1,
                    "穿戴位置也属于随身总量");
            h.inventory.setItem(13, new ItemStack(Items.TORCH, 16));
            torch = row(summary(h.player), "minecraft:torch");
            check(torch.get("count").getAsInt() == 80 && torch.getAsJsonObject("location_counts").get("backpack").getAsInt() == 16,
                    "背包和副手同类物品合计，同时保留分布");
        }
        System.out.println("InventoryHandVisibilityTest: passed");
    }

    private static JsonArray summary(LocalPlayer player) throws Exception {
        var method = MaiCraftRuntimeFacade.class.getDeclaredMethod("inventorySummary", LocalPlayer.class);
        method.setAccessible(true); return (JsonArray) method.invoke(null, player);
    }
    private static JsonObject row(JsonArray rows, String id) {
        for (var value : rows) if (value.getAsJsonObject().get("item_id").getAsString().equals(id)) return value.getAsJsonObject();
        throw new AssertionError("missing carried item: " + id);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
