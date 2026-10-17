// SPDX-License-Identifier: GPL-3.0-only
package org.maiwithu.maicraft.game.world;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;

/**
 * 掉落物的事实确认：只记服务器新增的掉落实体，和明确发给本玩家的拾取包（谁收的、收了几件）。
 * 不改物品、速度或背包，也不把背包里同类东西变多当成捡到——别人捡走的、并进旧堆的都分得清。
 *
 * <p>换身体、换维度、断线后清空旧证据；序号只增不减，旧会话的序号对不上新会话的事件。
 * 由网络包的 Mixin 在客户端线程喂，捡掉落物、战斗拾荒按序号问"这之后本玩家收了什么"。
 */
public final class DropConfirmations {

    private static final int MAX_EVENTS = 2048;
    private static final long RETAIN_TICKS = 1200;

    private LocalPlayer owner;
    private ClientLevel world;
    private long sequence;
    private long lastTick;
    private long sessionFloor;
    private final Map<UUID, Birth> births = new LinkedHashMap<>();
    private final ArrayDeque<Pickup> pickups = new ArrayDeque<>();

    private record Birth(long cursor, long tick) {}

    /** 一次本玩家的拾取：服务器说收了哪件（UUID 与实体编号）、收了几件、是什么。 */
    public record Pickup(long cursor, long tick, UUID uuid, int entityId, int amount, String itemId) {}

    /** 此刻的序号：动手前记一份，之后只看序号更大的事件。 */
    public long cursor(LocalPlayer player) {
        observeWorld(player);
        return sequence;
    }

    /** 这件掉落物是不是在记下的序号之后才出现的（这一下带出来的）。 */
    public boolean spawnedAfter(LocalPlayer player, UUID uuid, long afterCursor) {
        observeWorld(player);
        Birth birth = births.get(uuid);
        return afterCursor >= sessionFloor && birth != null && birth.cursor() > afterCursor;
    }

    /** 记下的序号之后本玩家的拾取。 */
    public List<Pickup> pickups(LocalPlayer player, long afterCursor) {
        observeWorld(player);
        return afterCursor < sessionFloor ? List.of()
                : pickups.stream().filter(event -> event.cursor() > afterCursor).toList();
    }

    /** 记下的序号之后本玩家从这个实体编号上收了几件；没收过为 0。 */
    public int pickedUpEntity(LocalPlayer player, int entityId, long afterCursor) {
        long count = pickups(player, afterCursor).stream()
                .filter(event -> event.entityId() == entityId).mapToLong(Pickup::amount).sum();
        return (int) Math.min(Integer.MAX_VALUE, count);
    }

    /** 服务器新增了一个实体：是掉落物就记下它出生的序号。 */
    public void entityAdded(LocalPlayer player, ClientLevel level, int entityId) {
        if (player == null || player.clientLevel != level) return;
        observeWorld(player);
        // 掉落物，和射出去的箭（打完要捡回来的，也按开打前后分新旧）。
        Entity added = level.getEntity(entityId);
        if (!(added instanceof ItemEntity) && !(added instanceof AbstractArrow)) return;
        births.put(added.getUUID(), new Birth(++sequence, level.getGameTime()));
        while (births.size() > MAX_EVENTS) births.remove(births.keySet().iterator().next());
    }

    /** 服务器说有人收了一件掉落物：只记发给本玩家的那些。 */
    public void taking(LocalPlayer player, ClientLevel level, ClientboundTakeItemEntityPacket packet) {
        if (player == null || player.clientLevel != level || packet.getPlayerId() != player.getId()
                || packet.getAmount() <= 0) return;
        observeWorld(player);
        // 必须在原版处理前读：处理后原实体可能已被移除。收取者不是本玩家的一概不记，不沿用原版视觉效果的默认。
        // 收的是掉落物，或是插在地上的箭（走过去捡回来的）。
        Entity taken = level.getEntity(packet.getItemId());
        ItemStack stack = taken instanceof ItemEntity item ? item.getItem() : ArrowPickups.item(taken);
        if (taken == null || stack.isEmpty()) return;
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        pickups.addLast(new Pickup(++sequence, level.getGameTime(), taken.getUUID(), taken.getId(),
                packet.getAmount(), itemId));
        while (pickups.size() > MAX_EVENTS) pickups.removeFirst();
    }

    // 死亡换身体、换维度或断线后清空旧证据；序号保持递增，旧会话的序号不会碰巧指向新会话的事件。
    private void observeWorld(LocalPlayer player) {
        ClientLevel level = player == null ? null : player.clientLevel;
        long now = level == null ? 0 : level.getGameTime();
        if (owner != player || world != level || now < lastTick) {
            births.clear();
            pickups.clear();
            owner = player;
            world = level;
            sessionFloor = ++sequence;
        }
        lastTick = now;
        births.entrySet().removeIf(entry -> now - entry.getValue().tick() > RETAIN_TICKS);
        while (!pickups.isEmpty() && now - pickups.getFirst().tick() > RETAIN_TICKS) pickups.removeFirst();
    }
}
