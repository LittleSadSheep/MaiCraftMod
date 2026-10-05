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
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.maiwithu.maicraft.intent.IntentRuntime;
import org.maiwithu.maicraft.intent.IntentTaskRecord;
import org.maiwithu.maicraft.task.CompanionTickDispatcher;
import org.maiwithu.maicraft.task.TaskRecord;

/**
 * 报告本地玩家装备耐久打空的物品消灭事实。耐久在服务端扣除，客户端没有事件可订阅；
 * 观察走两条通道：装备同步包（其他实体也会收到，只认本玩家），以及原版
 * {@code onEquippedItemBroken} 的实体事件（47-52/65）——装备增量包走
 * {@code ChunkSource.broadcast}，跟踪名单不含玩家自己，**本玩家的装备包永远不会到达**；
 * 打空事件走 {@code broadcastAndSend}，必达本人（057 实机两轮零事件的根因）。
 * 逐刻基准扫描维护耐久账与最后见到的物品读数；打空事件到达时据此发布，
 * 只在打空消灭时发布事件，逐次耐久下降不打扰；不修改背包，也不预测耐久消耗。
 */
public final class EquipmentReceipts {
    private static final Map<EquipmentSlot, Integer> BREAK_EVENT_IDS =
            Map.of(EquipmentSlot.MAINHAND, 47, EquipmentSlot.OFFHAND, 48, EquipmentSlot.HEAD, 49,
                    EquipmentSlot.CHEST, 50, EquipmentSlot.LEGS, 51, EquipmentSlot.FEET, 52,
                    EquipmentSlot.BODY, 65);
    private static LocalPlayer owner;
    private static ClientLevel world;
    private static final Map<EquipmentSlot, Observed> observed = new EnumMap<>(EquipmentSlot.class);
    private static final Map<EquipmentSlot, Observed> lastDamageable = new EnumMap<>(EquipmentSlot.class);
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
        boolean newlyBound = owner != player || world != player.clientLevel;
        observeWorld(player);
        if (newlyBound) {
            // 057 实机复测曾"零事件"且无法区分 jar 缺修复与包路失效；绑定即在日志留痕，
            // 下一轮实机看这行有没有，就能把两类原因分开。
            org.maiwithu.maicraft.core.Constants.LOG.info(
                    "[maicraft-equip] equipment observation bound for local player");
        }
        List<Pair<EquipmentSlot, ItemStack>> slots = packet.getSlots();
        for (Pair<EquipmentSlot, ItemStack> entry : slots) {
            EquipmentSlot slot = entry.getFirst();
            ItemStack next = entry.getSecond();
            Observed previous = observed.get(slot);
            Observed current = describe(next);
            boolean continued = previous != null && sameItem(previous, current);
            if (previous != null && previous.damageable() && current.itemId() == null) {
                lastDamageable.remove(slot);
                org.maiwithu.maicraft.core.Constants.LOG.info(
                        "[maicraft-equip] item broken: {} in {}", previous.itemId(), slot.getName());
                broken(player, slot, previous, damageObserved.getOrDefault(slot, 0));
            }
            if (current.damageable()) lastDamageable.put(slot, current);
            int delta = continued ? Math.max(0, current.damage() - previous.damage()) : 0;
            damageObserved.put(slot, accumulation(slot, continued, current, delta));
            observed.put(slot, current);
        }
    }

    /**
     * 耐久账的清账时机：槽位变空（打空同步或玩家移走）保持旧账，直到打空事件消费或
     * 换上别的物品再清零；打空事件与空槽同步不保证先后，账目不能在事件发布前被清掉。
     */
    private static int accumulation(EquipmentSlot slot, boolean continued, Observed current, int delta) {
        if (current.itemId() == null) return damageObserved.getOrDefault(slot, 0);
        return (continued ? damageObserved.getOrDefault(slot, 0) : 0) + delta;
    }

    /**
     * 逐刻基准扫描：本玩家的装备增量包从不发给自己（见类注释），这里直接读身上装备
     * 维护观察账目，供装备损坏实体事件发布时引用。每刻 6 个槽位的栈描述成本可忽略。
     */
    public static void observe(LocalPlayer player) {
        if (player == null || player.clientLevel == null) return;
        observeWorld(player);
        for (EquipmentSlot slot : BREAK_EVENT_IDS.keySet()) {
            Observed current = describe(player.getItemBySlot(slot));
            Observed previous = observed.get(slot);
            if (current.damageable()) lastDamageable.put(slot, current);
            boolean continued = previous != null && sameItem(previous, current);
            int delta = continued ? Math.max(0, current.damage() - previous.damage()) : 0;
            damageObserved.put(slot, accumulation(slot, continued, current, delta));
            observed.put(slot, current);
        }
    }

    /**
     * 实体事件通道：原版打空耐久时 {@code onEquippedItemBroken} 以 47-52/65 号实体事件
     * 广播（含本人）。这是本玩家装备消灭的唯一必达信号，发布依据是扫描基准里最后
     * 见到的物品读数；无基准（绑定后尚未扫描）不臆造物品身份，留待下一条。
     */
    public static void entityEvent(LocalPlayer player, ClientboundEntityEventPacket packet) {
        EquipmentSlot slot = BREAK_EVENT_IDS.entrySet().stream()
                .filter(entry -> entry.getValue() == packet.getEventId())
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (slot == null || player == null || player.clientLevel == null) return;
        if (packet.getEntity(player.clientLevel) != player) return;
        observeWorld(player);
        Observed previous = lastDamageable.remove(slot);
        if (previous == null) {
            org.maiwithu.maicraft.core.Constants.LOG.warn(
                    "[maicraft-equip] break event for {} without a scan baseline; item identity unknown", slot.getName());
            return;
        }
        org.maiwithu.maicraft.core.Constants.LOG.info(
                "[maicraft-equip] item broken: {} in {}", previous.itemId(), slot.getName());
        broken(player, slot, previous, damageObserved.getOrDefault(slot, 0));
    }

    /** 换身体或换世界后清空旧基准：重生或重连后的首批观察只建立基准，不补发旧变化。 */
    private static void observeWorld(LocalPlayer player) {
        if (owner != player || world != player.clientLevel) {
            observed.clear();
            lastDamageable.clear();
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
