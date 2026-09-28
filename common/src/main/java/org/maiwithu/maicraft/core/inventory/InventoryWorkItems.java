// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.core.inventory;

import com.google.gson.JsonElement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.maiwithu.maicraft.intent.Goal;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskState;

/** 整理期间保留当前取料、后续步骤及最近施工所声明的材料，补料子任务不会清掉主工程的输入。 */
public final class InventoryWorkItems {
    private static final ThreadLocal<Set<Item>> SCOPED = ThreadLocal.withInitial(Set::of);
    private static final Set<String> CONSTRUCTION = Set.of("maicraft:build_machine", "maicraft:modify_machine",
            "maicraft:build", "maicraft:build_structure", "maicraft:modify_structure");
    private InventoryWorkItems() {}

    public static <T> T within(Set<Item> materials, Supplier<T> work) {
        Set<Item> previous = SCOPED.get(); var combined = new HashSet<>(previous); combined.addAll(materials);
        SCOPED.set(Set.copyOf(combined));
        try { return work.get(); } finally { SCOPED.set(previous); }
    }

    public static Set<Item> current(Set<Item> requested) {
        var result = new HashSet<>(requested); result.addAll(SCOPED.get());
        if (CompanionTickDispatcher.current() instanceof IntentTaskRecord current)
            for (int i = current.stepIndex(); i < current.steps().size(); i++) referenced(current.steps().get(i), result);
        retainLatestConstruction(IntentRuntime.get().tasks(64), result);
        return Set.copyOf(result);
    }

    static void retainLatestConstruction(List<IntentTaskRecord> recentFirst, Set<Item> result) {
        // 补料失败后的独立取料仍属于近期施工准备；只保留最近方案，取消后的旧方案和更早版本不累加。
        for (var task : recentFirst) {
            if (!construction(task.goal())) continue;
            if (task.getState() != TaskState.CANCELLED) referenced(task.goal(), result);
            return;
        }
    }

    private static boolean construction(Goal goal) {
        return CONSTRUCTION.contains(goal.ability()) || goal.children().stream().anyMatch(InventoryWorkItems::construction);
    }

    static void referenced(Goal goal, Set<Item> result) {
        referenced(goal.parameters(), result);
        if (goal.target() != null && "item".equals(goal.target().kind())) add(goal.target().label(), result);
        goal.children().forEach(child -> referenced(child, result));
    }

    private static void referenced(JsonElement value, Set<Item> result) {
        if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(entry -> referenced(entry.getValue(), result));
        else if (value.isJsonArray()) value.getAsJsonArray().forEach(entry -> referenced(entry, result));
        else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) add(value.getAsString(), result);
    }

    private static void add(String value, Set<Item> result) {
        ResourceLocation id = ResourceLocation.tryParse(value); if (id == null) return;
        // 方块与放置物品可能不同名，例如附着漏斗；按实际注册关系保留其物品，不猜测名称替换规则。
        Item item = BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.get(id)
                : BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.get(id).asItem() : Items.AIR;
        if (item != Items.AIR) result.add(item);
    }
}
