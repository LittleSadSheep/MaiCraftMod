// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.mcp;

import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.maiwithu.maicraft.intent.SemanticResultView;
import org.maiwithu.maicraft.core.inventory.InventoryComponentFacts;

/** 背包总量不变，带不同进度组件的同类物品保持可区分，未知大组件不能冒充空组件。 */
public final class InventoryComponentFactsTest {
    public static void main(String[] args) {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        ItemStack early = workpiece(1), late = workpiece(14);
        var before = early.copy();
        var rows = InventoryComponentFacts.inventory(List.of(early, early.copyWithCount(2), late, new ItemStack(Items.BRICK)), registries);
        var item = rows.get(0).getAsJsonObject(); var variants = item.getAsJsonArray("variants");
        check(item.get("count").getAsInt() == 5 && variants.size() == 3, "default and different component variants keep the registry total");
        check(variants.get(0).getAsJsonObject().get("count").getAsInt() == 3
                && !variants.get(0).getAsJsonObject().get("resource_id").equals(variants.get(1).getAsJsonObject().get("resource_id")),
                "only identical component identities merge their counts");
        var held = InventoryComponentFacts.observe(late, registries);
        check(held.get("resource_id").equals(variants.get(1).getAsJsonObject().get("resource_id"))
                && held.getAsJsonObject("components").toString().contains("14"), "held workpiece exposes the same exact identity and stage");
        check(ItemStack.matches(before, early), "read-only grouping does not mutate inventory stacks");
        var ordinary = InventoryComponentFacts.inventory(List.of(new ItemStack(Items.STICK, 4)), registries).get(0).getAsJsonObject();
        check(!ordinary.has("variants") && ordinary.get("count").getAsInt() == 4, "ordinary item summaries remain compact");
        ItemStack large = workpiece(1); CompoundTag tag = new CompoundTag(); tag.putString("large", "x".repeat(5000));
        large.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        var unknown = InventoryComponentFacts.observe(large, registries);
        check(unknown.get("identity_status").getAsString().equals("observed") && unknown.has("components") && unknown.has("resource_id"),
                "large observed components remain available without another tool call");
        // 两个原生库存即使显示同一种工件，也必须保留各自的成员与存储标识；独立位置字段继续按既有规则处理。
        var source = Map.of("storage_id", "504, 78, 104/items/view:up/0", "membership", "504, 78, 104",
                "resource_id", "items:minecraft:brick#native-key", "position", Map.of("x", 504, "y", 78, "z", 104));
        var clean = SemanticResultView.data(source);
        check(clean.get("storage_id").equals(source.get("storage_id")) && clean.get("membership").equals(source.get("membership"))
                && clean.get("position").equals(source.get("position")), "native identities and observed positions remain available together");
        var json = (Map<?, ?>) SemanticResultView.jsonValue(new Gson().toJsonTree(source));
        check(json.get("storage_id").equals(clean.get("storage_id")) && json.get("resource_id").equals(source.get("resource_id")),
                "JSON and map receipts preserve the same resource identities");
        System.out.println("InventoryComponentFactsTest: passed");
    }

    private static ItemStack workpiece(int step) {
        // 使用原版可序列化组件模拟模组工件进度，不在测试注册表里伪造 Create 物品。
        ItemStack stack = new ItemStack(Items.BRICK); CompoundTag data = new CompoundTag(); data.putInt("step", step);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data)); return stack;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
