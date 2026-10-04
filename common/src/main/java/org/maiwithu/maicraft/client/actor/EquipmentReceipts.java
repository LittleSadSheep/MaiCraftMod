// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 只读观察服务器发给本玩家的装备同步包，报告耐久打空的物品消灭事实。
 * 耐久在服务端扣除，客户端没有事件可订阅；装备包是该事实到达客户端的唯一通道。
 * 只在物品打空消灭时发布事件，逐次耐久下降不打扰；不修改背包，也不预测耐久消耗。
 */
public final class EquipmentReceipts {
    private static LocalPlayer owner;
    private static ClientLevel world;
    private static final Map<EquipmentSlot, Observed> observed = new EnumMap<>(EquipmentSlot.class);
    private static final Map<EquipmentSlot, Integer> damageObserved = new EnumMap<>(EquipmentSlot.class);

    /** 槽位最近一次同步到的物品读数；itemId 为 null 表示空栈。 */
    private record Observed(String itemId, boolean damageable, int damage, int maxDamage) {}
    private EquipmentReceipts() {}

    /**
     * 处理一帧装备同步包；只关心本地玩家自己的槽位。
     * 服务端耐久打空后以空栈同步该槽位，这是物品消灭的显式证据。
     */
    public static void equipped(LocalPlayer player, ClientboundSetEquipmentPacket packet) {
        if (player == null || player.clientLevel == null || packet.getEntity() != player.getId()) return;
        observeWorld(player);
        List<Pair<EquipmentSlot, ItemStack>> slots = packet.getSlots();
        for (Pair<EquipmentSlot, ItemStack> entry : slots) {
            EquipmentSlot slot = entry.getFirst();
            ItemStack next = entry.getSecond();
            Observed previous = observed.get(slot);
            Observed current = describe(next);
            boolean continued = previous != null && sameItem(previous, current);
            if (previous != null && previous.damageable() && current.itemId() == null) {
                broken(player, slot, previous, damageObserved.getOrDefault(slot, 0));
            }
            int delta = continued ? Math.max(0, current.damage() - previous.damage()) : 0;
            damageObserved.put(slot, (continued ? damageObserved.getOrDefault(slot, 0) : 0) + delta);
            observed.put(slot, current);
        }
    }

    /** 换身体或换世界后清空旧基准：重生或重连后的首批装备包只建立基准，不补发旧变化。 */
    private static void observeWorld(LocalPlayer player) {
        if (owner != player || world != player.clientLevel) {
            observed.clear();
            damageObserved.clear();
            owner = player;
            world = player.clientLevel;
        }
    }

    private static Observed describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new Observed(null, false, 0, 0);
        return new Observed(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.isDamageableItem(),
                stack.getDamageValue(),
                stack.getMaxDamage());
    }

    private static boolean sameItem(Observed before, Observed after) {
        return before.itemId() != null && before.itemId().equals(after.itemId());
    }

    private static void broken(LocalPlayer player, EquipmentSlot slot, Observed item, int consumedTotal) {
        JsonObject data = new JsonObject();
        data.addProperty("item_id", item.itemId());
        data.addProperty("equipment_slot", slot.getName());
        data.addProperty("max_durability", item.maxDamage());
        data.addProperty("durability_last_seen", Math.max(0, item.maxDamage() - item.damage()));
        data.addProperty("damage_observed_total", Math.max(0, consumedTotal));
        data.addProperty("contains_internal_target_handles", false);
        data.add("position", position(player));
        TaskRecord current = CompanionTickDispatcher.current();
        if (current instanceof IntentTaskRecord intent && !intent.getState().isTerminal()) {
            data.addProperty("task_id", intent.externalId().toString());
        }
        IntentRuntime.get().gameEvent("agent.item_broken",
                "An equipped " + item.itemId() + " was destroyed; its durability ran out.", data);
    }

    private static JsonObject position(LocalPlayer player) {
        JsonObject result = new JsonObject();
        result.addProperty("x", player.getX());
        result.addProperty("y", player.getY());
        result.addProperty("z", player.getZ());
        result.addProperty("dimension", player.level().dimension().location().toString());
        return result;
    }
}
