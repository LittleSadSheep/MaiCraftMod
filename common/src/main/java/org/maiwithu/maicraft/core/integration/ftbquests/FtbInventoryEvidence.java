// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.integration.ftbquests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** 保存角色的全部非空物品与组件；相同物品合并数量，领取前后只报告实际变化，不把随机奖池当作到账。 */
public final class FtbInventoryEvidence {
    private FtbInventoryEvidence() {}
    static JsonObject capture(LocalPlayer player) {
        var stacks = new ArrayList<ItemStack>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) stacks.add(player.getInventory().getItem(i));
        // 鼠标上拿着的物品仍属于玩家；观察期内打开背包时不能把它误报成奖励丢失。
        if (player.containerMenu != null) stacks.add(player.containerMenu.getCarried());
        var grouped = new TreeMap<String, JsonObject>(); int unknown = 0;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            JsonObject row = new JsonObject(); row.addProperty("item_id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            row.addProperty("name", stack.getHoverName().getString()); String key;
            try {
                row.add("stack", FtbQuestData.json(stack.copyWithCount(1).save(player.registryAccess())));
                key = row.get("stack").toString();
            } catch (RuntimeException unavailable) {
                // 组件编码未知也保留真实物品和数量；不同的未知变体不擅自合并。
                row.addProperty("components_unavailable", true); key = "unknown-" + unknown++;
            }
            JsonObject previous = grouped.get(key);
            row.addProperty("count", stack.getCount() + (previous == null ? 0 : previous.get("count").getAsLong())); grouped.put(key, row);
        }
        JsonArray items = new JsonArray(); grouped.values().forEach(items::add);
        JsonObject result = new JsonObject(); result.add("items", items); result.addProperty("scope", "player_inventory_and_carried_stack");
        return result;
    }
    public static JsonObject difference(JsonObject before, JsonObject after) {
        // 按物品 ID 报告净数量变化；组件和变体的完整前后事实保留在快照，不能凭总数断言具体变体到账。
        Map<String, Long> oldCounts = counts(before), newCounts = counts(after); var ids = new TreeMap<>(oldCounts);
        newCounts.forEach(ids::putIfAbsent); JsonArray changes = new JsonArray();
        for (String id : ids.keySet()) {
            long oldCount = oldCounts.getOrDefault(id, 0L), newCount = newCounts.getOrDefault(id, 0L);
            if (oldCount == newCount) continue;
            JsonObject row = new JsonObject(); row.addProperty("item_id", id); row.addProperty("before", oldCount);
            row.addProperty("after", newCount); row.addProperty("change", newCount - oldCount); changes.add(row);
        }
        JsonObject out = new JsonObject(); out.add("item_count_changes", changes); out.addProperty("contents_changed", !before.equals(after));
        out.addProperty("scope", "observed_inventory_delta; world drops and arbitrary reward side effects are not verified"); return out;
    }
    private static Map<String, Long> counts(JsonObject snapshot) {
        Map<String, Long> result = new TreeMap<>();
        for (var value : snapshot.getAsJsonArray("items")) {
            JsonObject row = value.getAsJsonObject(); result.merge(row.get("item_id").getAsString(), row.get("count").getAsLong(), Long::sum);
        }
        return result;
    }
}
