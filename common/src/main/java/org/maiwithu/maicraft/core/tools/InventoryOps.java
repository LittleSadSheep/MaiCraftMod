package org.maiwithu.maicraft.core.tools;

import org.maiwithu.maicraft.agent.tool.ToolArgs;
import org.maiwithu.maicraft.agent.tool.api.ToolContext;
import org.maiwithu.maicraft.task.TaskRecord;
import org.maiwithu.maicraft.core.task.collect.CollectItemsTaskRecord;
import org.maiwithu.maicraft.core.task.inventory.DropItemsTaskRecord;
import org.maiwithu.maicraft.core.task.inventory.EatItemTaskRecord;
import org.maiwithu.maicraft.core.task.inventory.EquipTaskRecord;
import org.maiwithu.maicraft.core.task.inventory.UnequipTaskRecord;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把装备、进食、丢弃和拾取的内部参数转换成任务单，并给出默认范围和初始时间预算。
 * 这里不执行菜单点击；执行过程中真正能否完成，还要由任务读取玩家和世界状态。
 */
public final class InventoryOps {

    private static final long EQUIP_TIMEOUT_TICKS = 5 * 20;   // instant; generous floor

    /** Generous — covers any food's eat duration (most ~1.6s) plus buffer. */
    private static final long EAT_TIMEOUT_TICKS = 15 * 20;

    private static final int DROP_MAX_COUNT = 999;
    private static final long DROP_TIMEOUT_TICKS = 10 * 20;

    private static final int COLLECT_DEFAULT_RADIUS = 16;
    private static final int COLLECT_MAX_RADIUS = 48;
    private static final long COLLECT_TIMEOUT_TICKS = 60 * 20;   // 1 min

    // 穿戴和卸下共用这个入口；只有 action=unequip 走卸下分支，其他值在本层都按穿戴处理。
    public TaskRecord equipItem(
String action,
String item_id,
String slot,
            ToolContext ctx) {
        if ("unequip".equalsIgnoreCase(action)) {
            return new UnequipTaskRecord(ctx.toolCallId(), ctx.deadline(EQUIP_TIMEOUT_TICKS),
                    readUnequipSlots(slot), slot.toLowerCase());
        }
        if (item_id == null || item_id.isBlank()) {
            throw new IllegalArgumentException(
                    "item_id is required to equip (to take gear off, use action=unequip with a slot)");
        }
        EquipmentSlot equipSlot = readSlot(slot);

        Item item = ToolArgs.parseItem(item_id);
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new EquipTaskRecord(ctx.toolCallId(), ctx.deadline(EQUIP_TIMEOUT_TICKS), item, equipSlot, label);
    }

    /** Parse the optional slot; {@code null} means auto-route. */
    private static EquipmentSlot readSlot(String slot) {
        if (slot == null) {
            return null;
        }
        String name = slot.toLowerCase();
        return switch (name) {
            case "mainhand", "hand" -> EquipmentSlot.MAINHAND;
            case "offhand" -> EquipmentSlot.OFFHAND;
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "armor" -> throw new IllegalArgumentException(
                    "slot=armor is only for action=unequip (it means all four armor pieces)");
            case "feet" -> EquipmentSlot.FEET;
            default -> throw new IllegalArgumentException("unknown slot: " + name);
        };
    }

    /** 脱哪些槽:必填;{@code armor} 展开为四件甲。 */
    // 卸下必须指明栏位；armor 展开为头、胸、腿、脚四个栏位，其余名称只对应一个。
    private static List<EquipmentSlot> readUnequipSlots(String slot) {
        if (slot == null || slot.isBlank()) {
            throw new IllegalArgumentException(
                    "slot is required for unequip ('armor' takes all four armor pieces off)");
        }
        if ("armor".equalsIgnoreCase(slot)) {
            return List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
                    EquipmentSlot.LEGS, EquipmentSlot.FEET);
        }
        return List.of(readSlot(slot));
    }

    // 这里仅解析物品编号并给初始时限，是否属于能吃的食物由执行任务检查。
    public TaskRecord eatItem(
String item_id,
            ToolContext ctx) {
        Item item = ToolArgs.parseItem(item_id);
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new EatItemTaskRecord(ctx.toolCallId(), ctx.deadline(EAT_TIMEOUT_TICKS), item, label);
    }

    // 丢弃数量在本层压到 1～999；实际拥有量与逐次丢弃由任务判断。
    public TaskRecord dropItems(
String item_id,
int count,
            ToolContext ctx) {
        Item item = ToolArgs.parseItem(item_id);
        count = Math.clamp(count, 1, DROP_MAX_COUNT);
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new DropItemsTaskRecord(ctx.toolCallId(), ctx.deadline(DROP_TIMEOUT_TICKS),
                item, count, label);
    }

    // 无效物品编号被略过，过滤集合空时变成收集所有物品；全写错也会走这个分支，范围会意外扩大（D12）。
    public TaskRecord collectItems(
List<String> item_ids,
Integer radius,
            ToolContext ctx) {
        // Lenient set from the id list: unparseable / unknown ids are skipped, and
        // an absent list yields an empty set — the "match everything" filter.
        Set<Item> filter = new LinkedHashSet<>();
        if (item_ids != null) {
            for (String el : item_ids) {
                if (el == null) continue;
                ResourceLocation id = ResourceLocation.tryParse(el);
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                    filter.add(BuiltInRegistries.ITEM.get(id));
                }
            }
        }

        int searchRadius = COLLECT_DEFAULT_RADIUS;
        if (radius != null) {
            searchRadius = radius;
            if (searchRadius < 1) searchRadius = 1;
            if (searchRadius > COLLECT_MAX_RADIUS) searchRadius = COLLECT_MAX_RADIUS;
        }

        String label = filter.isEmpty() ? "all items" : labelFor(filter);
        return new CollectItemsTaskRecord(ctx.toolCallId(), ctx.deadline(COLLECT_TIMEOUT_TICKS),
                filter, searchRadius, label);
    }

    private static String labelFor(Set<Item> filter) {
        Item first = filter.iterator().next();
        String path = BuiltInRegistries.ITEM.getKey(first).getPath();
        return filter.size() == 1 ? path : path + "+" + (filter.size() - 1);
    }

}
