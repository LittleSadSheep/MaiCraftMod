// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.client.actor;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 只记服务器新增掉落实体和明确发给本玩家的拾取包；不修改物品、速度或背包，也不把同类库存增长当拾取来源。 */
public final class ItemEntityReceipts {
    private static final int MAX_EVENTS = 2048;
    private static final long RETAIN_TICKS = 1200;
    private static LocalPlayer owner;
    private static ClientLevel world;
    private static long sequence, lastTick, sessionFloor;
    private static final Map<UUID, Birth> births = new LinkedHashMap<>();
    private static final ArrayDeque<Pickup> pickups = new ArrayDeque<>();
    private record Birth(long cursor, long tick) {}
    public record ObservedDrop(int entityId, UUID uuid, ItemStack stack, Vec3 position) {
        public ObservedDrop { stack = stack.copy(); }
        @Override public ItemStack stack() { return stack.copy(); }
    }
    public record Pickup(long cursor, long tick, UUID uuid, int entityId, int amount, ItemStack stack) {
        public Pickup { stack = stack.copy(); }
        @Override public ItemStack stack() { return stack.copy(); }
    }
    private ItemEntityReceipts() {}

    public static void observeWorld(LocalPlayer player) {
        // 死亡换身体、维度切换或断线后清空旧证据；游标保持递增，旧会话数字不能碰巧指向新会话事件。
        ClientLevel level = player == null ? null : player.clientLevel;
        long now = level == null ? 0 : level.getGameTime();
        if (owner != player || world != level || now < lastTick) {
            births.clear(); pickups.clear(); owner = player; world = level; sessionFloor = ++sequence;
        }
        lastTick = now;
        births.entrySet().removeIf(entry -> now - entry.getValue().tick() > RETAIN_TICKS);
        while (!pickups.isEmpty() && now - pickups.getFirst().tick() > RETAIN_TICKS) pickups.removeFirst();
    }

    public static long cursor(LocalPlayer player) { observeWorld(player); return sequence; }

    public static List<ObservedDrop> snapshot(LocalPlayer player, AABB box) {
        observeWorld(player);
        if (world == null) return List.of();
        // 每次读取实体当前完整组件和位置，不把首次看到的原料类型永久套在后来发生原生转化的同一实体上。
        return world.getEntitiesOfClass(ItemEntity.class, box, item -> !item.isRemoved() && !item.getItem().isEmpty())
                .stream().map(item -> new ObservedDrop(item.getId(), item.getUUID(), item.getItem(), item.position())).toList();
    }

    public static boolean spawnedAfter(LocalPlayer player, UUID uuid, long afterCursor) {
        observeWorld(player); var birth = births.get(uuid);
        return afterCursor >= sessionFloor && birth != null && birth.cursor() > afterCursor;
    }

    public static List<Pickup> pickups(LocalPlayer player, long afterCursor) {
        observeWorld(player);
        return afterCursor < sessionFloor ? List.of() : pickups.stream().filter(event -> event.cursor() > afterCursor).toList();
    }

    public static int pickedUp(LocalPlayer player, UUID uuid, long afterCursor) {
        long count = pickups(player, afterCursor).stream().filter(event -> event.uuid().equals(uuid)).mapToLong(Pickup::amount).sum();
        return (int) Math.min(Integer.MAX_VALUE, count);
    }

    public static void entityAdded(LocalPlayer player, ClientLevel level, int entityId) {
        if (player == null || player.clientLevel != level) return;
        observeWorld(player);
        if (!(level.getEntity(entityId) instanceof ItemEntity item)) return;
        births.put(item.getUUID(), new Birth(++sequence, level.getGameTime()));
        while (births.size() > MAX_EVENTS) births.remove(births.keySet().iterator().next());
    }

    public static void taking(LocalPlayer player, ClientLevel level, ClientboundTakeItemEntityPacket packet) {
        if (player == null || player.clientLevel != level || packet.getPlayerId() != player.getId() || packet.getAmount() <= 0) return;
        observeWorld(player);
        // 必须在原版处理前保存 UUID；处理后原实体可能被移除。未知收取者绝不沿用原版视觉效果的“默认本玩家”回退。
        if (!(level.getEntity(packet.getItemId()) instanceof ItemEntity item)) return;
        // 物品组件包偶尔晚于拾取包；UUID 和收取数量仍是服务器事实，组件未知时保留空快照而不漏掉这次归因。
        pickups.addLast(new Pickup(++sequence, level.getGameTime(), item.getUUID(), item.getId(), packet.getAmount(), item.getItem()));
        while (pickups.size() > MAX_EVENTS) pickups.removeFirst();
    }
}
