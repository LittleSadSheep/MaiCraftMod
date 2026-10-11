// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.player;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 装备耐久打空的事实：手里的工具、身上的盔甲用坏消灭了，由这里认出来并告诉监听的一方。
 *
 * <p>耐久在服务端扣，客户端没有事件可订阅，观察走两条通道：装备同步包（只认本玩家的槽位），
 * 以及原版打空耐久时广播的实体事件（47–52、65 号）。装备增量包对本玩家自己永远不会到达，
 * 打空事件才是本玩家装备消灭的必达信号；所以每刻扫一遍身上的装备维护耐久账，
 * 打空事件到达时据此说清坏的是哪一件、满耐久多少、最后看到剩多少。只在打空时发一次，逐次耐久下降不打扰；
 * 不改背包，也不预测耐久消耗。
 */
public final class EquipmentConfirmations {

    private static final Logger LOG = LoggerFactory.getLogger(EquipmentConfirmations.class);

    /** 原版打空耐久的实体事件号：哪个槽位坏了。 */
    private static final Map<EquipmentSlot, Byte> BREAK_EVENT_IDS = Map.of(
            EquipmentSlot.MAINHAND, (byte) 47,
            EquipmentSlot.OFFHAND, (byte) 48,
            EquipmentSlot.HEAD, (byte) 49,
            EquipmentSlot.CHEST, (byte) 50,
            EquipmentSlot.LEGS, (byte) 51,
            EquipmentSlot.FEET, (byte) 52,
            EquipmentSlot.BODY, (byte) 65);

    /** 一件装备用坏了：是什么、在哪个槽位、满耐久多少、最后看到剩多少、这次观察到一共扣了多少。 */
    public record Broken(String itemId, String slot, int maxDurability, int durabilityLastSeen, int damageObservedTotal) {}

    /** 槽位最近一次看到的物品读数；itemId 为 null 表示空。 */
    private record Observed(String itemId, boolean damageable, int damage, int maxDamage) {}

    private final Consumer<Broken> listener;
    private LocalPlayer owner;
    private ClientLevel world;
    private final Map<EquipmentSlot, Observed> observed = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, Observed> lastDamageable = new EnumMap<>(EquipmentSlot.class);
    private final Map<EquipmentSlot, Integer> damageObserved = new EnumMap<>(EquipmentSlot.class);

    /** @param listener 装备坏了告诉谁（发事件、记提醒）。 */
    public EquipmentConfirmations(Consumer<Broken> listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /** 处理一帧装备同步包；只关心本地玩家自己的槽位。服务端打空后以空栈同步该槽位，这是消灭的显式证据。 */
    public void equipped(LocalPlayer player, ClientboundSetEquipmentPacket packet) {
        if (player == null || player.clientLevel == null || packet.getEntity() != player.getId()) return;
        observeWorld(player);
        List<Pair<EquipmentSlot, ItemStack>> slots = packet.getSlots();
        for (Pair<EquipmentSlot, ItemStack> entry : slots) {
            EquipmentSlot slot = entry.getFirst();
            Observed previous = observed.get(slot);
            Observed current = describe(entry.getSecond());
            boolean continued = previous != null && sameItem(previous, current);
            if (previous != null && previous.damageable() && current.itemId() == null) {
                lastDamageable.remove(slot);
                broken(slot, previous, damageObserved.getOrDefault(slot, 0));
            }
            if (current.damageable()) lastDamageable.put(slot, current);
            int delta = continued ? Math.max(0, current.damage() - previous.damage()) : 0;
            damageObserved.put(slot, accumulation(slot, continued, current, delta));
            observed.put(slot, current);
        }
    }

    /** 逐刻基准扫描：本玩家的装备增量包从不发给自己，这里直接读身上装备维护耐久账，供打空事件引用。 */
    public void observe(LocalPlayer player) {
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

    /** 实体事件通道：原版打空耐久时广播实体事件（含本人），这是本玩家装备消灭的唯一必达信号。 */
    public void entityEvent(LocalPlayer player, ClientboundEntityEventPacket packet) {
        EquipmentSlot slot = BREAK_EVENT_IDS.entrySet().stream()
                .filter(entry -> entry.getValue() == packet.getEventId())
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (slot == null || player == null || player.clientLevel == null) return;
        if (packet.getEntity(player.clientLevel) != player) return;
        observeWorld(player);
        Observed previous = lastDamageable.remove(slot);
        if (previous == null) {
            // 没有基准（刚绑定还没扫过）不臆造是哪一件，留待下一条。
            LOG.warn("装备打空事件到了，但 {} 槽位没有扫描基准，说不出坏的是哪一件", slot.getName());
            return;
        }
        broken(slot, previous, damageObserved.getOrDefault(slot, 0));
    }

    // 耐久账的清账时机：槽位变空保持旧账，直到打空事件消费或换上别的物品再清零；打空事件与空槽同步不保证先后。
    private int accumulation(EquipmentSlot slot, boolean continued, Observed current, int delta) {
        if (current.itemId() == null) return damageObserved.getOrDefault(slot, 0);
        return (continued ? damageObserved.getOrDefault(slot, 0) : 0) + delta;
    }

    // 换身体或换世界后清空旧基准：重生或重连后的首批观察只建立基准，不补发旧变化。
    private void observeWorld(LocalPlayer player) {
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
        return new Observed(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                stack.isDamageableItem(), stack.getDamageValue(), stack.getMaxDamage());
    }

    private static boolean sameItem(Observed before, Observed after) {
        return before.itemId() != null && before.itemId().equals(after.itemId());
    }

    private void broken(EquipmentSlot slot, Observed item, int consumedTotal) {
        LOG.info("装备用坏了：{} 在 {}", item.itemId(), slot.getName());
        listener.accept(new Broken(item.itemId(), slot.getName(), item.maxDamage(),
                Math.max(0, item.maxDamage() - item.damage()), Math.max(0, consumedTotal)));
    }
}
